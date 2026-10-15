/*
 * Copyright (c) 2016, 2020, Oracle and/or its affiliates. All rights reserved.
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
import jdk.graal.compiler.asm.aarch64.AArch64Assembler;
import jdk.graal.compiler.core.common.LIRKind;
import jdk.graal.compiler.core.common.NumUtil;
import jdk.graal.compiler.debug.Assertions;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.lir.aarch64.AArch64AddressValue;
import jdk.graal.compiler.lir.gen.LIRGeneratorTool;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.memory.address.AddressNode;
import jdk.graal.compiler.nodes.spi.LIRLowerable;
import jdk.graal.compiler.nodes.spi.NodeLIRBuilderTool;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.Value;

/**
 * Represents an AArch64 address in the graph.
 */
@NodeInfo
public class AArch64AddressNode extends AddressNode implements LIRLowerable {

    public static final NodeClass<AArch64AddressNode> TYPE = NodeClass.create(AArch64AddressNode.class);

    @OptionalInput private ValueNode base;

    @OptionalInput private ValueNode index;
    private AArch64Address.AddressingMode addressingMode;

    private final int bitMemoryTransferSize;
    private int displacement;
    private int scaleFactor;
    /**
     * Extension of a 32-bit index and shift of the extended index, for the form
     * {@code base + (extend(index) << indexShift) + displacement} that is emitted through a scratch
     * register, see {@link AArch64AddressValue#needsScratchRegister()}.
     */
    private AArch64Assembler.ExtendType indexExtend;
    private int indexShift;

    public AArch64AddressNode(int bitMemoryTransferSize, ValueNode base, ValueNode index) {
        super(TYPE);
        this.bitMemoryTransferSize = bitMemoryTransferSize;
        this.base = base;
        this.index = index;
        this.addressingMode = AddressingMode.REGISTER_OFFSET;
        this.displacement = 0;
        this.scaleFactor = 1;
    }

    @Override
    public void generate(NodeLIRBuilderTool gen) {
        assert verify();

        LIRGeneratorTool tool = gen.getLIRGeneratorTool();

        AllocatableValue baseValue = base == null ? Value.ILLEGAL : tool.asAllocatable(gen.operand(base));
        AllocatableValue indexValue = index == null ? Value.ILLEGAL : tool.asAllocatable(gen.operand(index));

        AllocatableValue baseReference = LIRKind.derivedBaseFromValue(baseValue);
        AllocatableValue indexReference;
        if (index == null || LIRKind.isValue(indexValue.getValueKind())) {
            indexReference = null;
        } else {
            indexReference = Value.ILLEGAL;
        }

        LIRKind kind = LIRKind.combineDerived(tool.getLIRKind(stamp(NodeView.DEFAULT)), baseReference, indexReference);
        gen.setResult(this, new AArch64AddressValue(kind, bitMemoryTransferSize, baseValue, indexValue, displacement, scaleFactor, addressingMode, indexExtend, indexShift));
    }

    @Override
    public boolean verifyNode() {
        assertTrue(bitMemoryTransferSize == AArch64Address.ANY_SIZE || bitMemoryTransferSize == 8 || bitMemoryTransferSize == 16 || bitMemoryTransferSize == 32 || bitMemoryTransferSize == 64 ||
                        bitMemoryTransferSize == 128, "Invalid memory transfer size.");
        switch (addressingMode) {
            case IMMEDIATE_SIGNED_UNSCALED:
                assertTrue(scaleFactor == 1, "Should not have scale factor.");
                assertTrue(index == null, "Immediate address cannot use index register.");
                break;
            case IMMEDIATE_UNSIGNED_SCALED:
                assertTrue(bitMemoryTransferSize / Byte.SIZE == scaleFactor, "Invalid scale factor.");
                assertTrue(index == null, "Immediate address cannot use index register.");
                break;
            case BASE_REGISTER_ONLY:
                assertTrue(scaleFactor == 1, "Should not have scale factor.");
                assertTrue(displacement == 0 && index == null, "Base register only mode cannot have either a displacement or index register.");
                break;
            case REGISTER_OFFSET:
                assertTrue(index != null, "Register based mode needs an index register.");
                if (displacement != 0) {
                    assertTrue(scaleFactor == 1, "Scratch register form cannot scale the displacement.");
                    assertTrue(indexShift >= 0 && indexShift <= 4, "Invalid index shift.");
                    assertTrue(indexExtend == null || index.getStackKind() == JavaKind.Int, "Only a 32-bit index is extended.");
                } else {
                    assertTrue(scaleFactor == 1 || bitMemoryTransferSize / Byte.SIZE == scaleFactor, "Invalid scale factor.");
                    assertTrue(indexExtend == null && indexShift == 0, "Register offset mode cannot extend or shift the index.");
                }
                break;
            case EXTENDED_REGISTER_OFFSET:
                assertTrue(scaleFactor == 1 || bitMemoryTransferSize / Byte.SIZE == scaleFactor, "Invalid scale factor.");
                assertTrue(displacement == 0 && index != null, "Register based mode cannot have a displacement.");
                break;
            default:
                fail("Pairwise and post/pre index addressing modes should not be present.");
        }
        return super.verifyNode();
    }

    @Override
    public ValueNode getBase() {
        return base;
    }

    public void setBase(ValueNode base) {
        // allow modification before inserting into the graph
        if (isAlive()) {
            updateUsages(this.base, base);
        }
        this.base = base;
    }

    @Override
    public ValueNode getIndex() {
        return index;
    }

    public void setIndex(ValueNode index) {
        // allow modification before inserting into the graph
        if (isAlive()) {
            updateUsages(this.index, index);
        }
        this.index = index;
    }

    public long getDisplacement() {
        return displacement;
    }

    public void setDisplacement(long displacement, int scaleFactor, AArch64Address.AddressingMode addressingMode) {
        assert scaleFactor == 1 || bitMemoryTransferSize / Byte.SIZE == scaleFactor : Assertions.errorMessageContext("scaleFactor", scaleFactor, "bitMemoryTransfeSize", bitMemoryTransferSize);
        this.displacement = NumUtil.safeToInt(displacement);
        this.scaleFactor = scaleFactor;
        this.addressingMode = addressingMode;
    }

    /**
     * Turns this address into {@code base + (extend(index) << indexShift) + displacement}, which is
     * emitted as an add into a scratch register followed by an immediate access. {@code index} is
     * the unextended, unshifted index.
     */
    public void setScratchRegisterForm(ValueNode newIndex, AArch64Assembler.ExtendType extend, int shift, long newDisplacement) {
        assert newDisplacement != 0 && shift >= 0 && shift <= 4 : Assertions.errorMessageContext("displacement", newDisplacement, "shift", shift);
        setIndex(newIndex);
        this.indexExtend = extend;
        this.indexShift = shift;
        this.displacement = NumUtil.safeToInt(newDisplacement);
        this.scaleFactor = 1;
        this.addressingMode = AddressingMode.REGISTER_OFFSET;
    }

    public boolean needsScratchRegister() {
        return addressingMode == AddressingMode.REGISTER_OFFSET && displacement != 0;
    }

    public AArch64Assembler.ExtendType getIndexExtend() {
        return indexExtend;
    }

    public int getIndexShift() {
        return indexShift;
    }

    @Override
    public long getMaxConstantDisplacement() {
        return displacement;
    }

    public AddressingMode getAddressingMode() {
        return addressingMode;
    }
}
