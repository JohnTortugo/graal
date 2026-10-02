/*
 * Copyright (c) 2015, 2022, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2017, Red Hat Inc. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package jdk.graal.compiler.core.aarch64;

import jdk.graal.compiler.asm.aarch64.AArch64Address;
import jdk.graal.compiler.asm.aarch64.AArch64Address.AddressingMode;
import jdk.graal.compiler.asm.aarch64.AArch64Assembler.ExtendType;
import jdk.graal.compiler.core.common.LIRKind;
import jdk.graal.compiler.core.common.NumUtil;
import jdk.graal.compiler.core.common.memory.MemoryOrderMode;
import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.calc.AddNode;
import jdk.graal.compiler.nodes.calc.LeftShiftNode;
import jdk.graal.compiler.nodes.calc.SignExtendNode;
import jdk.graal.compiler.nodes.calc.ZeroExtendNode;
import jdk.graal.compiler.nodes.memory.OrderedMemoryAccess;
import jdk.graal.compiler.nodes.memory.ReadNode;
import jdk.graal.compiler.nodes.memory.WriteNode;
import jdk.graal.compiler.nodes.memory.address.AddressNode;
import jdk.graal.compiler.nodes.memory.address.OffsetAddressNode;
import jdk.graal.compiler.phases.common.AddressLoweringByUsePhase;

import jdk.vm.ci.aarch64.AArch64Kind;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;

public class AArch64AddressLoweringByUse extends AddressLoweringByUsePhase.AddressLoweringByUse {
    private AArch64LIRKindTool kindtool;
    private boolean supportsDerivedReference;

    public AArch64AddressLoweringByUse(AArch64LIRKindTool kindtool, boolean supportsDerivedReference) {
        this.kindtool = kindtool;
        this.supportsDerivedReference = supportsDerivedReference;
    }

    @Override
    public AddressNode lower(ValueNode use, Stamp stamp, AddressNode address) {
        if (address instanceof OffsetAddressNode) {
            OffsetAddressNode offsetAddress = (OffsetAddressNode) address;
            return doLower(address.hasExactlyOneUsage() ? use : null, stamp, offsetAddress.getBase(), offsetAddress.getOffset());
        } else {
            // must be an already transformed AArch64AddressNode
            return address;
        }
    }

    @Override
    public AddressNode lower(AddressNode address) {
        return lower(null, null, address);
    }

    private AddressNode doLower(Stamp stamp, ValueNode base, ValueNode index) {
        return doLower(null, stamp, base, index);
    }

    private AddressNode doLower(ValueNode use, Stamp stamp, ValueNode base, ValueNode index) {
        AArch64Kind aarch64Kind = (stamp == null ? null : getAArch64Kind(stamp));
        int bitMemoryTransferSize = aarch64Kind == null ? AArch64Address.ANY_SIZE : aarch64Kind.getSizeInBytes() * Byte.SIZE;
        AArch64AddressNode ret = new AArch64AddressNode(bitMemoryTransferSize, base, index);

        // improve the address as much as possible
        boolean changed;
        do {
            changed = improve(aarch64Kind, ret, use);
        } while (changed);

        // avoid duplicates
        return base.graph().unique(ret);
    }

    private boolean improve(AArch64Kind kind, AArch64AddressNode ret, ValueNode use) {
        AddressingMode mode = ret.getAddressingMode();
        // if we have already set a displacement or set to base only mode then we are done
        if (isDisplacementMode(mode) || isBaseOnlyMode(mode) || ret.needsScratchRegister()) {
            return false;
        }
        ValueNode base = ret.getBase();
        ValueNode index = ret.getIndex();

        // avoid a constant or null base if possible
        if (base == null) {
            ret.setBase(index);
            ret.setIndex(base);
            return true;
        }
        // make sure any integral JavaConstant
        // is the index rather than the base
        // strictly we don't need the conditions on index
        // as we ought not to see two JavaConstant values
        if (base.isJavaConstant() && base.asJavaConstant().getJavaKind().isNumericInteger() &&
                        index != null && !index.isJavaConstant()) {
            ret.setBase(index);
            ret.setIndex(base);
            return true;
        }

        // if the base is an add then move it up
        if (index == null && base instanceof AddNode) {
            AddNode add = (AddNode) base;
            ret.setBase(add.getX());
            ret.setIndex(add.getY());
            return true;
        }

        // we can try to fold a JavaConstant index into a displacement
        if (index != null && index.isJavaConstant()) {
            JavaConstant javaConstant = index.asJavaConstant();
            if (javaConstant.getJavaKind().isNumericInteger()) {
                long disp = javaConstant.asLong();
                mode = immediateMode(kind, disp);
                if (isDisplacementMode(mode)) {
                    index = null;
                    // we can fold this in as a displacement
                    // but first see if we can pull up any additional
                    // constants added into the base
                    boolean tryNextBase = (base instanceof AddNode);
                    while (tryNextBase) {
                        AddNode add = (AddNode) base;
                        tryNextBase = false;
                        ValueNode child = add.getX();
                        if (child.isJavaConstant() && child.asJavaConstant().getJavaKind().isNumericInteger()) {
                            long newDisp = disp + child.asJavaConstant().asLong();
                            AddressingMode newMode = immediateMode(kind, newDisp);
                            if (newMode != AddressingMode.REGISTER_OFFSET) {
                                disp = newDisp;
                                mode = newMode;
                                base = add.getY();
                                ret.setBase(base);
                                tryNextBase = (base instanceof AddNode);
                            }
                        } else {
                            child = add.getY();
                            if (child.isJavaConstant() && child.asJavaConstant().getJavaKind().isNumericInteger()) {
                                long newDisp = disp + child.asJavaConstant().asLong();
                                AddressingMode newMode = immediateMode(kind, newDisp);
                                if (newMode != AddressingMode.REGISTER_OFFSET) {
                                    disp = newDisp;
                                    mode = newMode;
                                    base = add.getX();
                                    ret.setBase(base);
                                    tryNextBase = (base instanceof AddNode);
                                }
                            }
                        }
                    }
                    if (disp != 0) {
                        // ok now set the displacement in place of an index
                        ret.setIndex(null);
                        int scaleFactor = computeScaleFactor(kind, mode);
                        ret.setDisplacement(disp, scaleFactor, mode);
                    } else {
                        // reset to base register only
                        ret.setIndex(null);
                        ret.setDisplacement(0, 1, AddressingMode.BASE_REGISTER_ONLY);
                    }
                    return true;
                }
            }
        }

        /*
         * Without derived references, (OffsetAddress base (Add (LeftShift (Ext i) k) #imm)) is
         * otherwise emitted as "mov tmp, #imm; add tmp, tmp, i, ext #k; ldr [base, tmp]" because no
         * addressing mode takes both an index register and an immediate. Folding the index addition
         * into the access, "add scratch, base, i, ext #k; ldr [scratch, #imm]", saves one instruction
         * per access; the pointer into the object lives only inside the access instruction, so no
         * reference map has to describe it. An index shared by several accesses (a load and a store
         * of the same element, the card table lookup of a write barrier) stays a register offset:
         * computing it once and reusing it is cheaper than one addition per access.
         */
        if (!supportsDerivedReference && kind != null && allowsScratchRegisterForm(use) && index instanceof AddNode add && index.getStackKind() == JavaKind.Long && add.hasExactlyOneUsage()) {
            ValueNode constant;
            ValueNode scaled;
            if (isIntegerConstant(add.getY())) {
                constant = add.getY();
                scaled = add.getX();
            } else if (isIntegerConstant(add.getX())) {
                constant = add.getX();
                scaled = add.getY();
            } else {
                constant = null;
                scaled = null;
            }
            if (constant != null && !isIntegerConstant(scaled)) {
                long disp = constant.asJavaConstant().asLong();
                if (disp != 0 && isDisplacementMode(immediateMode(kind, disp))) {
                    int shift = 0;
                    ValueNode inner = scaled;
                    if (inner instanceof LeftShiftNode leftShift && leftShift.getY().isJavaConstant()) {
                        int amount = leftShift.getY().asJavaConstant().asInt();
                        if (amount >= 0 && amount <= 4) {
                            shift = amount;
                            inner = leftShift.getX();
                        }
                    }
                    ExtendType extend = null;
                    if (inner instanceof ZeroExtendNode zeroExtend && zeroExtend.getInputBits() == 32 && zeroExtend.getResultBits() == 64) {
                        extend = ExtendType.UXTW;
                        inner = zeroExtend.getValue();
                    } else if (inner instanceof SignExtendNode signExtend && signExtend.getInputBits() == 32 && signExtend.getResultBits() == 64) {
                        extend = ExtendType.SXTW;
                        inner = signExtend.getValue();
                    }
                    if (inner.getStackKind() == (extend == null ? JavaKind.Long : JavaKind.Int)) {
                        ret.setScratchRegisterForm(inner, extend, shift, disp);
                        return true;
                    }
                }
            }
        }

        // We try to convert (OffsetAddress base (Add (LeftShift (Ext i) k) #imm))
        // to (AArch64AddressNode (AArch64PointerAdd (base (LeftShift (Ext i) k)) #imm)
        if (supportsDerivedReference && index != null && index instanceof AddNode && index.getStackKind().isNumericInteger()) {
            ValueNode x = ((AddNode) index).getX();
            ValueNode y = ((AddNode) index).getY();
            ValueNode objHeadOffset = null;
            ValueNode scaledIndex = null;
            if (x.isConstant()) {
                objHeadOffset = x;
                scaledIndex = y;
            } else if (y.isConstant()) {
                objHeadOffset = y;
                scaledIndex = x;
            }

            if (scaledIndex == null || objHeadOffset == null) {
                return false;
            }

            ZeroExtendNode wordIndex = null;
            if (scaledIndex instanceof LeftShiftNode) {
                ValueNode var = ((LeftShiftNode) scaledIndex).getX();
                ValueNode amount = ((LeftShiftNode) scaledIndex).getY();
                if (amount.isConstant() && var instanceof ZeroExtendNode) {
                    int s = amount.asJavaConstant().asInt();
                    if (s >= 0 && s <= 4) {
                        wordIndex = (ZeroExtendNode) var;
                    }
                }
            } else if (scaledIndex instanceof ZeroExtendNode) {
                wordIndex = (ZeroExtendNode) scaledIndex;
            }

            if (wordIndex != null) {
                AArch64PointerAddNode addP = base.graph().unique(new AArch64PointerAddNode(base, scaledIndex));
                ret.setBase(addP);
                ret.setIndex(objHeadOffset);
                return true;
            }
        }

        // nope cannot improve this any more
        return false;
    }

    private static boolean isIntegerConstant(ValueNode node) {
        return node.isJavaConstant() && node.asJavaConstant().getJavaKind().isNumericInteger();
    }

    /**
     * The scratch register form is only emitted by the plain load and store instructions; ordered
     * accesses and every other user of an address need the address in a register.
     */
    private static boolean allowsScratchRegisterForm(ValueNode use) {
        if (use instanceof ReadNode || use instanceof WriteNode) {
            return !MemoryOrderMode.ordersMemoryAccesses(((OrderedMemoryAccess) use).getMemoryOrder());
        }
        return false;
    }

    private AArch64Kind getAArch64Kind(Stamp stamp) {
        LIRKind lirKind = stamp.getLIRKind(kindtool);
        if (!lirKind.isValue()) {
            if (!lirKind.isReference(0) || lirKind.getReferenceCount() != 1) {
                return null;
            }
        }

        return (AArch64Kind) lirKind.getPlatformKind();
    }

    private static AddressingMode immediateMode(AArch64Kind kind, long immediate) {
        if (kind != null && NumUtil.isInt(immediate)) {
            int bitMemoryTransferSize = kind.getSizeInBytes() * Byte.SIZE;
            if (AArch64Address.isValidImmediateAddress(bitMemoryTransferSize, AddressingMode.IMMEDIATE_UNSIGNED_SCALED, NumUtil.safeToInt(immediate))) {
                return AddressingMode.IMMEDIATE_UNSIGNED_SCALED;
            }
        }

        // we can try for a 9 bit unscaled offset
        if (NumUtil.isSignedNbit(9, immediate)) {
            return AddressingMode.IMMEDIATE_SIGNED_UNSCALED;
        }

        // nope this index needs to be passed via offset register
        return AddressingMode.REGISTER_OFFSET;
    }

    private static int computeScaleFactor(AArch64Kind kind, AddressingMode mode) {
        if (mode == AddressingMode.IMMEDIATE_UNSIGNED_SCALED) {
            return kind.getSizeInBytes();
        }
        return 1;
    }

    private static boolean isBaseOnlyMode(AddressingMode addressingMode) {
        return addressingMode == AddressingMode.BASE_REGISTER_ONLY;
    }

    private static boolean isDisplacementMode(AddressingMode addressingMode) {
        switch (addressingMode) {
            case IMMEDIATE_POST_INDEXED:
            case IMMEDIATE_PRE_INDEXED:
            case IMMEDIATE_UNSIGNED_SCALED:
            case IMMEDIATE_SIGNED_UNSCALED:
                return true;
        }
        return false;
    }
}
