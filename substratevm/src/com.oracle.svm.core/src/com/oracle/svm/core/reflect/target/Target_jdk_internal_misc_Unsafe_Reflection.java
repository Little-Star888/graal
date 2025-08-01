/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
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

package com.oracle.svm.core.reflect.target;

import java.lang.reflect.Field;

import com.oracle.svm.core.StaticFieldsSupport;
import com.oracle.svm.core.SubstrateUtil;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.imagelayer.ImageLayerBuildingSupport;
import com.oracle.svm.core.layeredimagesingleton.MultiLayeredImageSingleton;
import com.oracle.svm.core.reflect.UnsafeFieldUtil;

@TargetClass(className = "jdk.internal.misc.Unsafe")
@SuppressWarnings({"static-method"})
public final class Target_jdk_internal_misc_Unsafe_Reflection {

    @Substitute
    public long objectFieldOffset(Target_java_lang_reflect_Field field) {
        return UnsafeFieldUtil.getFieldOffset(field);
    }

    @Substitute
    public long staticFieldOffset(Target_java_lang_reflect_Field field) {
        return UnsafeFieldUtil.getFieldOffset(field);
    }

    @Substitute
    public Object staticFieldBase(Target_java_lang_reflect_Field field) {
        if (field == null) {
            throw new NullPointerException();
        }
        int layerNumber = ImageLayerBuildingSupport.buildingImageLayer() ? field.installedLayerNumber : MultiLayeredImageSingleton.UNUSED_LAYER_NUMBER;
        if (SubstrateUtil.cast(field, Field.class).getType().isPrimitive()) {
            return StaticFieldsSupport.getStaticPrimitiveFieldsAtRuntime(layerNumber);
        } else {
            return StaticFieldsSupport.getStaticObjectFieldsAtRuntime(layerNumber);
        }
    }

    @Substitute
    public long objectFieldOffset(Class<?> c, String name) {
        if (c == null || name == null) {
            throw new NullPointerException();
        }
        try {
            Field field = c.getDeclaredField(name);
            Target_java_lang_reflect_Field cast = SubstrateUtil.cast(field, Target_java_lang_reflect_Field.class);
            return objectFieldOffset(cast);
        } catch (NoSuchFieldException nse) {
            throw new InternalError();
        }
    }
}
