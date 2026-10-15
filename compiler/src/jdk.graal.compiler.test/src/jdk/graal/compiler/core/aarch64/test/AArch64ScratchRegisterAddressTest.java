/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.core.aarch64.test;

import static org.junit.Assume.assumeTrue;

import org.junit.Assert;
import org.junit.Test;

import jdk.graal.compiler.asm.aarch64.AArch64Address;
import jdk.graal.compiler.asm.aarch64.AArch64Assembler.ExtendType;
import jdk.graal.compiler.core.aarch64.AArch64AddressLoweringByUse;
import jdk.graal.compiler.core.aarch64.AArch64AddressNode;
import jdk.graal.compiler.core.aarch64.AArch64LIRKindTool;
import jdk.graal.compiler.core.aarch64.AArch64PointerAddNode;
import jdk.graal.compiler.core.common.LIRKind;
import jdk.graal.compiler.core.common.memory.BarrierType;
import jdk.graal.compiler.core.common.memory.MemoryOrderMode;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.core.test.GraalCompilerTest;
import jdk.graal.compiler.lir.aarch64.AArch64AddressValue;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.NamedLocationIdentity;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.StructuredGraph.AllowAssumptions;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.calc.AddNode;
import jdk.graal.compiler.nodes.calc.LeftShiftNode;
import jdk.graal.compiler.nodes.calc.SignExtendNode;
import jdk.graal.compiler.nodes.calc.ZeroExtendNode;
import jdk.graal.compiler.nodes.memory.ReadNode;
import jdk.graal.compiler.nodes.memory.WriteNode;
import jdk.graal.compiler.nodes.memory.address.AddressNode;
import jdk.graal.compiler.nodes.memory.address.OffsetAddressNode;
import jdk.vm.ci.aarch64.AArch64;
import jdk.vm.ci.aarch64.AArch64Kind;
import jdk.vm.ci.meta.JavaKind;

/**
 * Tests the address form {@code base + (extend(index) << shift) + displacement} that
 * {@link AArch64AddressLoweringByUse} produces for plain accesses when derived references are not
 * supported, and its emission through a scratch register.
 */
public class AArch64ScratchRegisterAddressTest extends GraalCompilerTest {

    public static char loadChar(char[] a, int i) {
        return a[i];
    }

    public static long loadLong(long[] a, long i) {
        return a[(int) i];
    }

    private static final class Graphs {
        final StructuredGraph graph;
        final ValueNode base;
        final ValueNode index;

        Graphs(StructuredGraph graph) {
            this.graph = graph;
            this.base = graph.getParameter(0);
            this.index = graph.getParameter(1);
        }

        ValueNode offset(ValueNode scaled, long displacement) {
            return graph.addOrUnique(new AddNode(scaled, ConstantNode.forLong(displacement, graph)));
        }

        ValueNode shifted(ValueNode value, int shift) {
            return graph.addOrUnique(new LeftShiftNode(value, ConstantNode.forInt(shift, graph)));
        }

        ReadNode read(AddressNode address, JavaKind kind, MemoryOrderMode order) {
            ReadNode read = graph.add(new ReadNode(address, NamedLocationIdentity.getArrayLocation(kind), StampFactory.forKind(kind), BarrierType.NONE, order));
            graph.addAfterFixed(graph.start(), read);
            return read;
        }
    }

    private Graphs graphs(String method) {
        assumeTrue("AArch64 specific test", getTarget().arch instanceof AArch64);
        return new Graphs(parseEager(method, AllowAssumptions.NO));
    }

    private static AArch64AddressLoweringByUse lowering(boolean supportsDerivedReference) {
        return new AArch64AddressLoweringByUse(new AArch64LIRKindTool(), supportsDerivedReference);
    }

    private static AArch64AddressNode lower(ReadNode read, AddressNode address, boolean supportsDerivedReference) {
        AddressNode lowered = lowering(supportsDerivedReference).lower(read, read.getAccessStamp(NodeView.DEFAULT), address);
        Assert.assertTrue(lowered.toString(), lowered instanceof AArch64AddressNode);
        return (AArch64AddressNode) lowered;
    }

    @Test
    public void zeroExtendedIndexUsesScratchRegister() {
        Graphs g = graphs("loadChar");
        ValueNode zeroExtended = g.graph.addOrUnique(new ZeroExtendNode(g.index, 64));
        OffsetAddressNode address = g.graph.addOrUnique(new OffsetAddressNode(g.base, g.offset(g.shifted(zeroExtended, 1), 16)));
        ReadNode read = g.read(address, JavaKind.Char, MemoryOrderMode.PLAIN);

        AArch64AddressNode lowered = lower(read, address, false);
        Assert.assertTrue(lowered.needsScratchRegister());
        Assert.assertSame(g.base, lowered.getBase());
        Assert.assertSame(g.index, lowered.getIndex());
        Assert.assertEquals(ExtendType.UXTW, lowered.getIndexExtend());
        Assert.assertEquals(1, lowered.getIndexShift());
        Assert.assertEquals(16, lowered.getDisplacement());
        Assert.assertEquals(AArch64Address.AddressingMode.REGISTER_OFFSET, lowered.getAddressingMode());
    }

    @Test
    public void signExtendedIndexUsesScratchRegister() {
        Graphs g = graphs("loadChar");
        ValueNode signExtended = g.graph.addOrUnique(new SignExtendNode(g.index, 64));
        OffsetAddressNode address = g.graph.addOrUnique(new OffsetAddressNode(g.base, g.offset(g.shifted(signExtended, 1), 16)));
        ReadNode read = g.read(address, JavaKind.Char, MemoryOrderMode.PLAIN);

        AArch64AddressNode lowered = lower(read, address, false);
        Assert.assertTrue(lowered.needsScratchRegister());
        Assert.assertSame(g.index, lowered.getIndex());
        Assert.assertEquals(ExtendType.SXTW, lowered.getIndexExtend());
        Assert.assertEquals(1, lowered.getIndexShift());
    }

    @Test
    public void longIndexWithoutExtension() {
        Graphs g = graphs("loadLong");
        OffsetAddressNode address = g.graph.addOrUnique(new OffsetAddressNode(g.base, g.offset(g.shifted(g.index, 3), 16)));
        ReadNode read = g.read(address, JavaKind.Long, MemoryOrderMode.PLAIN);

        AArch64AddressNode lowered = lower(read, address, false);
        Assert.assertTrue(lowered.needsScratchRegister());
        Assert.assertSame(g.index, lowered.getIndex());
        Assert.assertNull(lowered.getIndexExtend());
        Assert.assertEquals(3, lowered.getIndexShift());
        Assert.assertEquals(16, lowered.getDisplacement());
    }

    @Test
    public void unshiftedLongIndex() {
        Graphs g = graphs("loadLong");
        OffsetAddressNode address = g.graph.addOrUnique(new OffsetAddressNode(g.base, g.offset(g.index, 16)));
        ReadNode read = g.read(address, JavaKind.Byte, MemoryOrderMode.PLAIN);

        AArch64AddressNode lowered = lower(read, address, false);
        Assert.assertTrue(lowered.needsScratchRegister());
        Assert.assertSame(g.index, lowered.getIndex());
        Assert.assertNull(lowered.getIndexExtend());
        Assert.assertEquals(0, lowered.getIndexShift());
    }

    @Test
    public void sharedAddressKeepsRegisterOffset() {
        Graphs g = graphs("loadChar");
        ValueNode zeroExtended = g.graph.addOrUnique(new ZeroExtendNode(g.index, 64));
        ValueNode offset = g.offset(g.shifted(zeroExtended, 1), 16);
        OffsetAddressNode address = g.graph.addOrUnique(new OffsetAddressNode(g.base, offset));
        ReadNode read = g.read(address, JavaKind.Char, MemoryOrderMode.PLAIN);
        WriteNode write = g.graph.add(new WriteNode(address, NamedLocationIdentity.getArrayLocation(JavaKind.Char), read, BarrierType.NONE, MemoryOrderMode.PLAIN));
        g.graph.addAfterFixed(read, write);

        AArch64AddressNode lowered = lower(read, address, false);
        Assert.assertFalse(lowered.needsScratchRegister());
        Assert.assertSame(offset, lowered.getIndex());
        Assert.assertEquals(0, lowered.getDisplacement());
    }

    @Test
    public void sharedIndexKeepsRegisterOffset() {
        Graphs g = graphs("loadChar");
        ValueNode zeroExtended = g.graph.addOrUnique(new ZeroExtendNode(g.index, 64));
        ValueNode offset = g.offset(g.shifted(zeroExtended, 1), 16);
        OffsetAddressNode address = g.graph.addOrUnique(new OffsetAddressNode(g.base, offset));
        ReadNode read = g.read(address, JavaKind.Char, MemoryOrderMode.PLAIN);
        /* A second user of the index: the sum is cheaper to compute once. */
        g.graph.addOrUnique(new AddNode(offset, g.graph.addOrUnique(new ZeroExtendNode(read, 64))));

        AArch64AddressNode lowered = lower(read, address, false);
        Assert.assertFalse(lowered.needsScratchRegister());
        Assert.assertSame(offset, lowered.getIndex());
    }

    @Test
    public void orderedAccessKeepsRegisterOffset() {
        Graphs g = graphs("loadChar");
        ValueNode zeroExtended = g.graph.addOrUnique(new ZeroExtendNode(g.index, 64));
        ValueNode offset = g.offset(g.shifted(zeroExtended, 1), 16);
        OffsetAddressNode address = g.graph.addOrUnique(new OffsetAddressNode(g.base, offset));
        ReadNode read = g.read(address, JavaKind.Char, MemoryOrderMode.VOLATILE);

        AArch64AddressNode lowered = lower(read, address, false);
        Assert.assertFalse(lowered.needsScratchRegister());
        Assert.assertSame(offset, lowered.getIndex());
    }

    @Test
    public void largeDisplacementKeepsRegisterOffset() {
        Graphs g = graphs("loadChar");
        ValueNode zeroExtended = g.graph.addOrUnique(new ZeroExtendNode(g.index, 64));
        ValueNode offset = g.offset(g.shifted(zeroExtended, 1), 1 << 20);
        OffsetAddressNode address = g.graph.addOrUnique(new OffsetAddressNode(g.base, offset));
        ReadNode read = g.read(address, JavaKind.Char, MemoryOrderMode.PLAIN);

        AArch64AddressNode lowered = lower(read, address, false);
        Assert.assertFalse(lowered.needsScratchRegister());
        Assert.assertSame(offset, lowered.getIndex());
    }

    @Test
    public void derivedReferencesUsePointerAdd() {
        Graphs g = graphs("loadChar");
        ValueNode zeroExtended = g.graph.addOrUnique(new ZeroExtendNode(g.index, 64));
        OffsetAddressNode address = g.graph.addOrUnique(new OffsetAddressNode(g.base, g.offset(g.shifted(zeroExtended, 1), 16)));
        ReadNode read = g.read(address, JavaKind.Char, MemoryOrderMode.PLAIN);

        AArch64AddressNode lowered = lower(read, address, true);
        Assert.assertFalse(lowered.needsScratchRegister());
        Assert.assertTrue(lowered.getBase() instanceof AArch64PointerAddNode);
        Assert.assertEquals(16, lowered.getDisplacement());
    }

    @Test
    public void emissionAddsIntoScratchAndAccessesWithImmediate() {
        assumeTrue("AArch64 specific test", getTarget().arch instanceof AArch64);
        AArch64TestMacroAssembler masm = new AArch64TestMacroAssembler(getTarget());
        AArch64AddressValue value = new AArch64AddressValue(LIRKind.value(AArch64Kind.QWORD), 16, AArch64.r0.asValue(), AArch64.r1.asValue(), 16, 1,
                        AArch64Address.AddressingMode.REGISTER_OFFSET, ExtendType.UXTW, 1);
        Assert.assertTrue(value.needsScratchRegister());

        AArch64Address address = value.toAddress(masm, AArch64.r8);
        Assert.assertEquals("one add instruction", 4, masm.position());
        /* add x8, x0, w1, uxtw #1 */
        Assert.assertEquals(0x8b214408, masm.getInt(0));
        Assert.assertEquals(AArch64Address.AddressingMode.IMMEDIATE_UNSIGNED_SCALED, address.getAddressingMode());
        Assert.assertEquals(AArch64.r8, address.getBase());
        Assert.assertEquals(16 / 2, address.getImmediateRaw());

        AArch64AddressValue unaligned = new AArch64AddressValue(LIRKind.value(AArch64Kind.QWORD), 32, AArch64.r0.asValue(), AArch64.r1.asValue(), 13, 1,
                        AArch64Address.AddressingMode.REGISTER_OFFSET, null, 0);
        AArch64Address unalignedAddress = unaligned.toAddress(masm, AArch64.r9);
        /* add x9, x0, x1 */
        Assert.assertEquals(0x8b010009, masm.getInt(4));
        Assert.assertEquals(AArch64Address.AddressingMode.IMMEDIATE_SIGNED_UNSCALED, unalignedAddress.getAddressingMode());
        Assert.assertEquals(13, unalignedAddress.getImmediateRaw());
    }
}
