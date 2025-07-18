/*
 * Copyright (c) 2024, 2024, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.imagelayer;

import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.c.type.WordPointer;
import org.graalvm.word.Pointer;

import com.oracle.svm.core.c.CGlobalData;
import com.oracle.svm.core.layeredimagesingleton.LayeredImageSingleton;

import jdk.graal.compiler.api.replacements.Fold;

/**
 * With layered images, this is a section that each layer has and that is present at runtime. It
 * contains the addresses of various important locations and information about values to patch at
 * runtime. See {@code ImageLayerSectionFeature} for details.
 */
public abstract class ImageLayerSection implements LayeredImageSingleton {

    protected final CGlobalData<Pointer> initialSectionStart;
    protected final CGlobalData<WordPointer> cachedImageFDs;
    protected final CGlobalData<WordPointer> cachedImageHeapOffsets;
    protected final CGlobalData<WordPointer> cachedImageHeapRelocations;

    protected ImageLayerSection(CGlobalData<Pointer> initialSectionStart, CGlobalData<WordPointer> cachedImageFDs, CGlobalData<WordPointer> cachedImageHeapOffsets,
                    CGlobalData<WordPointer> cachedImageHeapRelocations) {
        this.initialSectionStart = initialSectionStart;
        this.cachedImageFDs = cachedImageFDs;
        this.cachedImageHeapOffsets = cachedImageHeapOffsets;
        this.cachedImageHeapRelocations = cachedImageHeapRelocations;
    }

    public enum SectionEntries {
        HEAP_BEGIN,
        HEAP_END,
        HEAP_RELOCATABLE_BEGIN,
        HEAP_RELOCATABLE_END,
        HEAP_WRITEABLE_BEGIN,
        HEAP_WRITEABLE_END,
        HEAP_WRITEABLE_PATCHED_BEGIN,
        HEAP_WRITEABLE_PATCHED_END,
        CODE_START,
        NEXT_SECTION,
        VARIABLY_SIZED_DATA,
        FIRST_SINGLETON,
    }

    private static ImageLayerSection singleton() {
        return ImageSingletons.lookup(ImageLayerSection.class);
    }

    @Fold
    public static int getEntryOffset(SectionEntries entry) {
        return singleton().getEntryOffsetInternal(entry);
    }

    @Fold
    public static CGlobalData<Pointer> getInitialLayerSection() {
        return singleton().initialSectionStart;
    }

    @Fold
    public static CGlobalData<WordPointer> getCachedImageFDs() {
        return singleton().cachedImageFDs;
    }

    @Fold
    public static CGlobalData<WordPointer> getCachedImageHeapOffsets() {
        return singleton().cachedImageHeapOffsets;
    }

    @Fold
    public static CGlobalData<WordPointer> getCachedImageHeapRelocations() {
        return singleton().cachedImageHeapRelocations;
    }

    protected abstract int getEntryOffsetInternal(SectionEntries entry);

}
