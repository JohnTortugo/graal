/*
 * Copyright (c) 2007, 2025, Oracle and/or its affiliates. All rights reserved.
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
/*
 */
package jdk.graal.compiler.jtt.except;

import jdk.graal.compiler.jtt.JTTTest;
import jdk.graal.compiler.phases.OptimisticOptimizations;
import org.junit.Test;

public class BC_getfield1 extends JTTTest {

    @Override
    protected OptimisticOptimizations getOptimisticOptimizations() {
        // Disable profile based optimizations
        return OptimisticOptimizations.NONE;
    }

    private static final class TestClass {
        private int field = 13;
    }

    public static void test(TestClass arg) {
        @SuppressWarnings("unused")
        int i = arg.field;
    }

    @Test
    public void run0() throws Throwable {
        runTest("test", (Object) null);
    }

    @Test
    public void run1() throws Throwable {
        // tests that the null check isn't removed along with the read
        runTest("test", (Object) null);
    }

    @Test
    public void run2() throws Throwable {
        runTest("test", new TestClass());
    }

}
