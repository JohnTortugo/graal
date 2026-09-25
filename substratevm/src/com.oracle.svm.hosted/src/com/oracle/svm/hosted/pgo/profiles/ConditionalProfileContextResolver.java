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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.HostedUniverse;
import com.oracle.svm.hosted.pgo.IprofConditionalParser.ConditionalEntry;
import com.oracle.svm.hosted.pgo.IprofConditionalParser.ContextFrame;
import com.oracle.svm.hosted.pgo.IprofConditionalParser.MethodDescriptor;
import com.oracle.svm.hosted.pgo.IprofConditionalParser.ParsedProfile;
import com.oracle.svm.hosted.pgo.profiles.SimpleConditionalProfilesLookup.FrameKey;

import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.Signature;

/**
 * Resolves a {@link ParsedProfile}'s conditional entries against a {@link HostedUniverse}, producing
 * the immutable table consumed by {@link SimpleConditionalProfilesLookup}.
 *
 * <p>
 * The core idea is a single canonical method-identity string in JVM-descriptor form, computed the
 * same way from two sources:
 * <ul>
 * <li>from the iprof {@code methods}/{@code types} tables ({@link #descriptorForProfileMethod}), and
 * <li>from a resolved method at query time and during universe indexing
 * ({@link #methodDescriptor(ResolvedJavaMethod)}).
 * </ul>
 * Because both paths yield identical strings, calling contexts recorded in the profile line up with
 * the {@link jdk.vm.ci.code.BytecodePosition} chains the compiler passes to the lookup, without
 * relying on {@code NodeSourcePosition} identity.
 */
public final class ConditionalProfileContextResolver {

    private ConditionalProfileContextResolver() {
    }

    /**
     * Canonical identity of a resolved method: {@code <declaringClassDescriptor>.<name><signature>}
     * where the declaring class and every signature type are rendered as JVM type descriptors (for
     * example {@code Ljava/lang/String;}, {@code [I}, {@code I}). This matches
     * {@link #descriptorForProfileMethod} exactly.
     */
    public static String methodDescriptor(ResolvedJavaMethod method) {
        if (method == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(method.getDeclaringClass().getName());
        sb.append('.').append(method.getName());
        Signature signature = method.getSignature();
        sb.append('(');
        int count = signature.getParameterCount(false);
        for (int i = 0; i < count; i++) {
            sb.append(typeDescriptor(signature.getParameterType(i, null)));
        }
        sb.append(')');
        sb.append(typeDescriptor(signature.getReturnType(null)));
        return sb.toString();
    }

    private static String typeDescriptor(JavaType type) {
        // JVMCI JavaType.getName() already returns the JVM descriptor form.
        return type.getName();
    }

    /**
     * Builds the canonical descriptor for an iprof method entry, converting the profile's type
     * names (dotted FQNs, primitive keywords, or {@code [L...;} array names) into JVM descriptors so
     * the result matches {@link #methodDescriptor(ResolvedJavaMethod)}.
     *
     * @return the descriptor, or {@code null} if a referenced type id is missing.
     */
    static String descriptorForProfileMethod(MethodDescriptor method, Map<Integer, String> typeNamesById) {
        String declaring = descriptorForTypeId(method.declaringTypeId(), typeNamesById);
        String returnType = descriptorForTypeId(method.returnTypeId(), typeNamesById);
        if (declaring == null || returnType == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(declaring).append('.').append(method.name()).append('(');
        for (int paramTypeId : method.parameterTypeIds()) {
            String param = descriptorForTypeId(paramTypeId, typeNamesById);
            if (param == null) {
                return null;
            }
            sb.append(param);
        }
        sb.append(')').append(returnType);
        return sb.toString();
    }

    private static String descriptorForTypeId(int typeId, Map<Integer, String> typeNamesById) {
        String name = typeNamesById.get(typeId);
        return name == null ? null : toDescriptor(name);
    }

    /**
     * Converts an iprof type name into a JVM type descriptor.
     * <ul>
     * <li>primitive keyword ({@code int}, {@code boolean}, {@code void}, ...) -&gt; single-letter
     * descriptor;
     * <li>array name ({@code [Z}, {@code [Ljava.lang.String;}) -&gt; descriptor with dots replaced
     * by slashes inside the class part;
     * <li>class FQN ({@code java.lang.String}) -&gt; {@code Ljava/lang/String;}.
     * </ul>
     *
     * @param iprofTypeName a non-empty iprof type name.
     * @throws IllegalArgumentException if {@code iprofTypeName} is {@code null} or empty. The parser
     *             already rejects empty type names strictly (see {@code IprofConditionalParser}),
     *             so this guard converts any remaining empty input into a clear failure rather than
     *             an opaque {@link String#charAt(int)} {@link StringIndexOutOfBoundsException}.
     */
    static String toDescriptor(String iprofTypeName) {
        if (iprofTypeName == null || iprofTypeName.isEmpty()) {
            throw new IllegalArgumentException("Cannot convert an empty iprof type name to a JVM descriptor");
        }
        switch (iprofTypeName) {
            case "boolean":
                return "Z";
            case "byte":
                return "B";
            case "short":
                return "S";
            case "char":
                return "C";
            case "int":
                return "I";
            case "long":
                return "J";
            case "float":
                return "F";
            case "double":
                return "D";
            case "void":
                return "V";
            default:
                break;
        }
        if (iprofTypeName.charAt(0) == '[') {
            // Array: JVM descriptor already, but object element FQNs use dots -> convert to slashes.
            return iprofTypeName.replace('.', '/');
        }
        // Object type: dotted FQN -> Lbinary/name;
        return 'L' + iprofTypeName.replace('.', '/') + ';';
    }

    /**
     * Resolves the parsed profile against the universe and builds the lookup table.
     *
     * @return a fully constructed, immutable lookup.
     */
    public static SimpleConditionalProfilesLookup resolve(ParsedProfile profile, HostedUniverse universe) {
        return buildLookup(profile, indexUniverseMethods(universe).keySet());
    }

    /**
     * Builds the lookup and diagnostics from a parsed profile and the set of canonical method
     * descriptors present in the image. This is the universe-independent core of {@link #resolve}
     * and is directly unit-testable.
     *
     * @param profile the parsed profile.
     * @param presentDescriptors canonical descriptors (see {@link #methodDescriptor}) of methods
     *            available in the image.
     */
    static SimpleConditionalProfilesLookup buildLookup(ParsedProfile profile, Set<String> presentDescriptors) {
        Map<Integer, String> descriptorByMethodId = buildProfileMethodDescriptors(profile);

        Map<List<FrameKey>, long[]> table = new HashMap<>();
        int resolved = 0;
        int unresolved = 0;
        int duplicates = 0;
        int singleFrame = 0;
        int inlined = 0;

        for (ConditionalEntry entry : profile.conditionalEntries()) {
            List<FrameKey> key = canonicalKey(entry, descriptorByMethodId, presentDescriptors);
            if (key == null) {
                unresolved++;
                continue;
            }
            if (table.putIfAbsent(key, entry.records()) != null) {
                duplicates++;
                continue;
            }
            resolved++;
            if (key.size() == 1) {
                singleFrame++;
            } else {
                inlined++;
            }
        }

        ConditionalProfileDiagnostics diagnostics = new ConditionalProfileDiagnostics(
                        profile.version(), profile.conditionalEntries().size(), resolved, unresolved, duplicates, singleFrame, inlined);
        return new SimpleConditionalProfilesLookup(table, diagnostics);
    }

    /**
     * Builds a canonical {@link FrameKey} chain (innermost frame first) for a conditional entry, or
     * {@code null} if any frame's method is absent from the image. Each frame's method identity is
     * confirmed present so that only contexts fully present in this image are stored.
     */
    private static List<FrameKey> canonicalKey(ConditionalEntry entry, Map<Integer, String> descriptorByMethodId, Set<String> presentDescriptors) {
        List<FrameKey> key = new ArrayList<>(entry.context().size());
        for (ContextFrame frame : entry.context()) {
            String descriptor = descriptorByMethodId.get(frame.methodId());
            if (descriptor == null || !presentDescriptors.contains(descriptor)) {
                return null;
            }
            key.add(new FrameKey(descriptor, frame.bci()));
        }
        return key;
    }

    private static Map<Integer, String> buildProfileMethodDescriptors(ParsedProfile profile) {
        Map<Integer, String> descriptorByMethodId = new HashMap<>(profile.methodsById().size());
        for (MethodDescriptor method : profile.methodsById().values()) {
            String descriptor = descriptorForProfileMethod(method, profile.typeNamesById());
            if (descriptor != null) {
                descriptorByMethodId.put(method.methodId(), descriptor);
            }
        }
        return descriptorByMethodId;
    }

    /**
     * Indexes universe methods by their canonical descriptor. Descriptor collisions (which should
     * not occur for a fully-qualified signature) resolve first-wins; such contexts simply match the
     * first-registered method, and any mismatch degrades to a lookup miss handled gracefully by the
     * phase.
     */
    private static Map<String, HostedMethod> indexUniverseMethods(HostedUniverse universe) {
        Map<String, HostedMethod> methodsByDescriptor = new HashMap<>();
        for (HostedMethod method : universe.getMethods()) {
            if (!method.isOriginalMethod()) {
                continue;
            }
            methodsByDescriptor.putIfAbsent(methodDescriptor(method), method);
        }
        return methodsByDescriptor;
    }
}
