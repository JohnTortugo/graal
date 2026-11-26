/*
 * Copyright (c) 2026, 2026, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.hosted.pgo.profiles;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * Unit tests for {@link SamplingHotness}: how it counts the self samples of methods from sampled
 * stacks, which stacks it treats as idle, and the per-frame suffixes it builds.
 */
public class SamplingHotnessTest {
    @Test
    public void countsSelfSamplesAndExcludesIdleStacks() {
        SamplingHotness.SampleAccounting<String> accounting = SamplingHotness.accountSamples(Map.of(
                        List.of("outer", "recursive", "recursive", "leaf"), 6L,
                        List.of("outer", "otherLeaf"), 4L,
                        List.of("outer", "idle"), 10L),
                        frame -> frame, "idle"::equals);

        Assert.assertEquals(10, accounting.nonIdleSamples());
        Assert.assertEquals(10, accounting.idleSamples());
        Assert.assertEquals(Map.of("leaf", 6L, "otherLeaf", 4L), accounting.selfSamples());
    }

    @Test
    public void buildsOneSuffixPerFrameAndPreservesRecursion() {
        List<String> stack = List.of("outer", "recursive", "recursive", "leaf");

        Assert.assertEquals(List.of(
                        List.of("outer", "recursive", "recursive", "leaf"),
                        List.of("recursive", "recursive", "leaf"),
                        List.of("recursive", "leaf"),
                        List.of("leaf")), SamplingHotness.sampledSuffixes(stack));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptySampledStack() {
        SamplingHotness.accountSamples(Map.of(List.of(), 1L), frame -> frame, _ -> false);
    }

    /**
     * A thread parked in {@code Object.wait} uses no processor time. The resolver matches the
     * sampler's frame name {@code waitSubst} to {@code Object.wait}, so such stacks end in
     * {@code Object.wait}, and they must not count as self time.
     */
    @Test
    public void countsObjectWaitAsIdle() {
        ResolvedJavaType object = jvmciProxy(ResolvedJavaType.class, Map.of("getName", "Ljava/lang/Object;"));
        /* BytecodePosition accepts any bci for a code size of 0. */
        ResolvedJavaMethod wait = jvmciProxy(ResolvedJavaMethod.class, Map.of("getName", "wait", "getDeclaringClass", object, "getCodeSize", 0));
        SamplingHotness hotness = new SamplingHotness(Map.of(new NodeSourcePosition(null, wait, 2), 4L));

        Assert.assertEquals(4, hotness.idleSamples());
        Assert.assertEquals(0, hotness.nonIdleSamples());
    }

    /**
     * A mock of the JVMCI interface {@code type} whose methods named in {@code answers} return the
     * given values. Default methods, such as {@code toJavaName()}, run their JVMCI code.
     */
    private static <T> T jvmciProxy(Class<T> type, Map<String, Object> answers) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (instance, method, arguments) -> {
            if (answers.containsKey(method.getName())) {
                return answers.get(method.getName());
            }
            return switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(instance);
                case "equals" -> instance == arguments[0];
                default -> InvocationHandler.invokeDefault(instance, method, arguments);
            };
        }));
    }
}
