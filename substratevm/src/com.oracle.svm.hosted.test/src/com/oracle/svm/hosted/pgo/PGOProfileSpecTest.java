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
package com.oracle.svm.hosted.pgo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Assert;
import org.junit.Test;

import com.oracle.svm.hosted.pgo.PGOProfileUseFeature.ProfileSpec;

/**
 * Unit tests for how {@code --pgo} splits one element {@code path[:weight]} of its value into a
 * file and a weight.
 */
public class PGOProfileSpecTest {

    /**
     * Checks that {@code spec} splits into the file {@code path} and the weight {@code weight}, and
     * that the text reported as an ignored weight is {@code ignoredWeight} ({@code null} for none).
     */
    private static void assertSpec(String spec, String path, double weight, String ignoredWeight) {
        ProfileSpec parsed = ProfileSpec.parse(spec);
        Assert.assertEquals(spec, path, parsed.path());
        Assert.assertEquals(spec, weight, parsed.weight(), 0.0);
        Assert.assertEquals(spec, ignoredWeight, parsed.ignoredWeight());
    }

    @Test
    public void pathWithoutWeightHasWeightOne() {
        assertSpec("/profiles/a.iprof", "/profiles/a.iprof", 1.0, null);
    }

    @Test
    public void numberAfterTheLastColonIsTheWeight() {
        assertSpec("/profiles/a.iprof:2", "/profiles/a.iprof", 2.0, null);
        assertSpec("/profiles/a.iprof:0.25", "/profiles/a.iprof", 0.25, null);
    }

    @Test
    public void textAfterTheLastColonThatIsNoNumberIsReportedAndKeptInThePath() {
        assertSpec("/profiles/a.iprof:x2", "/profiles/a.iprof:x2", 1.0, "x2");
    }

    @Test
    public void colonFollowedByAPathSeparatorBelongsToThePath() {
        assertSpec("C:\\profiles\\a.iprof", "C:\\profiles\\a.iprof", 1.0, null);
        assertSpec("C:/profiles/a.iprof", "C:/profiles/a.iprof", 1.0, null);
    }

    @Test
    public void existingFileWithAColonInItsNameIsAPath() throws IOException {
        Path directory = Files.createTempDirectory("pgo-spec");
        Path file = Files.createFile(directory.resolve("run:2"));
        try {
            assertSpec(file.toString(), file.toString(), 1.0, null);
        } finally {
            Files.delete(file);
            Files.delete(directory);
        }
    }
}
