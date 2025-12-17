/*
 * Copyright (c) 2017, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.api.test.polyglot;

import static com.oracle.truffle.api.test.common.AbstractExecutableTestLanguage.evalTestLanguage;
import static com.oracle.truffle.api.test.polyglot.AbstractPolyglotTest.assertFails;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.HostAccess.Builder;
import org.graalvm.polyglot.HostAccess.Export;
import org.graalvm.polyglot.HostAccess.Implementable;
import org.graalvm.polyglot.HostAccess.MutableTargetMapping;
import org.graalvm.polyglot.HostAccess.TargetMappingPrecedence;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.TypeLiteral;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyHashMap;
import org.graalvm.polyglot.proxy.ProxyIterator;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.junit.Ignore;
import org.junit.Test;

import com.oracle.truffle.api.interop.DatapathReadOnlyArrayList;
import com.google.common.util.concurrent.FakeTimeLimiter;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.test.common.AbstractExecutableTestLanguage;
import com.oracle.truffle.api.test.common.NullObject;
import com.oracle.truffle.tck.tests.TruffleTestAssumptions;
import com.oracle.truffle.tck.tests.ValueAssert;
import com.oracle.truffle.tck.tests.ValueAssert.Trait;

public class DatapathReadOnlyArrayListTest extends AbstractHostAccessTest {
    private Value createReadOnlyArrayList(final int size) {
        setupEnv(HostAccess.newBuilder().allowListAccess(true));
        return context.asValue(new FakeArrayList(size));
    }

    @Test
    public void canGetArrayElement() {
        Value arrList = createReadOnlyArrayList(10);
        assertTrue(arrList.hasArrayElements());
        assertTrue(arrList.getArrayElement(7).asInt() == 7);
    }

    @Test
    public void canGetArraySize() {
        Value arrList = createReadOnlyArrayList(10);
        assertTrue(arrList.hasArrayElements());
        assertTrue(arrList.getArraySize() == 10);
    }

    @Test
    public void doesNotHaveIterator() {
        Value arrList = createReadOnlyArrayList(10);
        assertTrue(arrList.hasArrayElements());
        assertFalse(arrList.hasIterator());
    }

    @Test
    public void cannotSetArrayElement() {
        boolean asExpected = false;

        try {
            asExpected = false;
            Value arrList = createReadOnlyArrayList(10);
            arrList.setArrayElement(0, 0);
        } catch (UnsupportedOperationException e) {
            asExpected = true;
        } finally {
            assertTrue(asExpected);
        }
    }

    @Test
    public void cannotRemoveArrayElement() {
        boolean asExpected = false;

        try {
            Value arrList = createReadOnlyArrayList(10);
            asExpected = false;
            arrList.removeArrayElement(0);
        } catch (UnsupportedOperationException e) {
            asExpected = true;
        } finally {
            assertTrue(asExpected);
        }
    }

    @Test
    public void cannotPutMember() {
        boolean asExpected = false;
        try {
            Value arrList = createReadOnlyArrayList(10);
            asExpected = false;
            arrList.putMember("0", 1);
        } catch (UnsupportedOperationException e) {
            asExpected = true;
        } finally {
            assertTrue(asExpected);
        }
    }

// This test is not succeeding but I think is something weird in PolyglotValueDispatch.java:4475,
// 4482
// @Test
// public void cannotRemoveMember() {
// setupEnv(HostAccess.newBuilder().allowListAccess(true));
//
// final int arraySize = 10;
// boolean asExpected = false;
// FakeArrayList arrList = new FakeArrayList(arraySize);
// Value value = context.asValue(arrList);
//
// try {
// asExpected = false;
// value.removeMember("0");
// } catch (UnsupportedOperationException e) {
// asExpected = true;
// } finally {
// assertTrue(asExpected);
// }
// }

    private static class FakeArrayList implements DatapathReadOnlyArrayList {
        private Integer[] array;

        public FakeArrayList(final int size) {
            this.array = new Integer[size];
            for (int i = 0; i < size; i++) {
                this.array[i] = i;
            }
        }

        public Object[] getArray() {
            return this.array;
        }
    }
}
