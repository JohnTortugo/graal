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

import jdk.graal.compiler.core.common.CompressEncoding;
import jdk.graal.compiler.core.test.GraalCompilerTest;
import jdk.graal.compiler.lir.aarch64.AArch64Move;
import jdk.vm.ci.aarch64.AArch64;

/**
 * Checks the code emitted to decode a compressed reference relative to a heap base register.
 */
public class AArch64UncompressPointerTest extends GraalCompilerTest {

    @Test
    public void nonNull32BitInputUsesOneExtendedAdd() {
        assumeTrue("AArch64 specific test", getTarget().arch instanceof AArch64);
        AArch64TestMacroAssembler masm = new AArch64TestMacroAssembler(getTarget());
        AArch64Move.UncompressPointerOp.emitUncompressCode(masm, AArch64.r1, AArch64.r0, new CompressEncoding(0x1000, 3), true, AArch64.r27, true);
        Assert.assertEquals("one instruction", 4, masm.position());
        /* add x0, x27, w1, uxtw #3 */
        Assert.assertEquals(0x8b214f60, masm.getInt(0));
    }

    @Test
    public void nonNull64BitInputUsesShiftedAdd() {
        assumeTrue("AArch64 specific test", getTarget().arch instanceof AArch64);
        AArch64TestMacroAssembler masm = new AArch64TestMacroAssembler(getTarget());
        AArch64Move.UncompressPointerOp.emitUncompressCode(masm, AArch64.r1, AArch64.r0, new CompressEncoding(0x1000, 3), true, AArch64.r27, false);
        Assert.assertEquals("one instruction", 4, masm.position());
        /* add x0, x27, x1, lsl #3 */
        Assert.assertEquals(0x8b010f60, masm.getInt(0));
    }

    @Test
    public void withoutBaseKeepsWideningMove() {
        assumeTrue("AArch64 specific test", getTarget().arch instanceof AArch64);
        AArch64TestMacroAssembler masm = new AArch64TestMacroAssembler(getTarget());
        AArch64Move.UncompressPointerOp.emitUncompressCode(masm, AArch64.r1, AArch64.r0, new CompressEncoding(0, 3), true, null, true);
        Assert.assertEquals("mov and add", 8, masm.position());
    }
}
