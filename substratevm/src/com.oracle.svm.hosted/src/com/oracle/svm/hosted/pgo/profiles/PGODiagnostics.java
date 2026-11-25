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

import java.util.List;

/**
 * What {@link IprofProfileResolver} found when it matched a profile to the image, for the build
 * output: for each kind of entry, how many entries matched and how many did not; how many
 * descriptors name more than one image method or type, so that no entry is applied to them; how
 * many prefix sums were left out because their counts do not fit in a long; and, when asked for, a
 * readable form of every dropped entry. It is built once and does not change.
 * {@link IprofProfilesLookup} counts how the compiler then uses the profile.
 *
 * @param conditional the branch and switch entries
 * @param virtualInvoke the receiver entries
 * @param sampling the stack samples
 * @param callCount the call counts
 * @param ambiguousImageMethods how many method descriptors name more than one image method
 * @param ambiguousImageTypes how many type descriptors name more than one image type
 * @param overflowedPrefixSums how many prefixes of branch and switch contexts the context fallback
 *            leaves out, because their summed counts do not fit in a long
 * @param droppedContexts the dropped entries, such as
 *            {@code receiver type com.example.Old not found in the image: Lcom/example/Foo;.bar()V:9};
 *            empty unless the resolver was asked to collect them
 */
public record PGODiagnostics(ConditionalProfileDiagnostics conditional, IprofProfileResolver.VirtualInvokeDiagnostics virtualInvoke,
                IprofProfileResolver.SamplingDiagnostics sampling, IprofProfileResolver.CallCountDiagnostics callCount,
                int ambiguousImageMethods, int ambiguousImageTypes, int overflowedPrefixSums, List<String> droppedContexts) {

    public PGODiagnostics {
        droppedContexts = List.copyOf(droppedContexts);
    }
}
