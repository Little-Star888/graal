/*
 * Copyright (c) 2016, 2025, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.core.common.spi;

import java.util.Arrays;

import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.nodes.spi.CanonicalizerTool;
import jdk.graal.compiler.options.Option;
import jdk.graal.compiler.options.OptionKey;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * Utility for default constant folding semantics for Java fields.
 */
public abstract class JavaConstantFieldProvider implements ConstantFieldProvider {

    static class Options {
        @Option(help = "Determines whether to treat final fields with default values as constant.")//
        public static final OptionKey<Boolean> TrustFinalDefaultFields = new OptionKey<>(true);
    }

    protected JavaConstantFieldProvider(MetaAccessProvider metaAccess) {
        try {
            ResolvedJavaType stringType = metaAccess.lookupJavaType(String.class);
            stringValueField = findField(stringType, "value");
            stringHashField = findField(stringType, "hash");
        } catch (SecurityException e) {
            throw new GraalError(e);
        }
    }

    private static ResolvedJavaField findField(ResolvedJavaType type, String fieldName) {
        ResolvedJavaField[] stringFields = type.getInstanceFields(false);
        for (ResolvedJavaField field : stringFields) {
            if (field.getName().equals(fieldName)) {
                return field;
            }
        }
        throw new GraalError("missing field \"" + fieldName + "\" " + Arrays.toString(stringFields));
    }

    @Override
    public <T> T readConstantField(ResolvedJavaField field, ConstantFieldTool<T> tool) {
        if (isStableField(field, tool)) {
            JavaConstant value = tool.readValue();
            if (value != null && isStableFieldValueConstant(field, value, tool)) {
                return foldStableArray(value, field, tool);
            }
        }
        if (isFinalField(field, tool)) {
            JavaConstant value = tool.readValue();
            if (value != null && isFinalFieldValueConstant(field, value, tool)) {
                return tool.foldConstant(value);
            }
        }
        return null;
    }

    protected <T> T foldStableArray(JavaConstant value, ResolvedJavaField field, ConstantFieldTool<T> tool) {
        return tool.foldStableArray(value, getArrayDimension(field.getType()), isDefaultStableField(field, tool));
    }

    private static int getArrayDimension(JavaType type) {
        int dimensions = 0;
        JavaType componentType = type;
        while ((componentType = componentType.getComponentType()) != null) {
            dimensions++;
        }
        return dimensions;
    }

    private static boolean isArray(ResolvedJavaField field) {
        JavaType fieldType = field.getType();
        return fieldType instanceof ResolvedJavaType && fieldType.isArray();
    }

    @SuppressWarnings("unused")
    protected boolean isStableFieldValueConstant(ResolvedJavaField field, JavaConstant value, ConstantFieldTool<?> tool) {
        return !value.isDefaultForKind();
    }

    @SuppressWarnings("unused")
    protected boolean isFinalFieldValueConstant(ResolvedJavaField field, JavaConstant value, ConstantFieldTool<?> tool) {
        return !value.isDefaultForKind() || Options.TrustFinalDefaultFields.getValue(tool.getOptions());
    }

    @SuppressWarnings("unused")
    protected boolean isStableField(ResolvedJavaField field, ConstantFieldTool<?> tool) {
        if (isPrimitiveBoxingCacheField(field)) {
            return true;
        }
        if (isSyntheticEnumSwitchMap(field)) {
            return true;
        }
        if (isWellKnownImplicitStableField(field)) {
            return true;
        }
        if (field.equals(stringHashField)) {
            return true;
        }
        return false;
    }

    protected boolean isPrimitiveBoxingCacheField(ResolvedJavaField field) {
        if (isArray(field) && field.isFinal() && field.getName().equals("cache")) {
            ResolvedJavaType type = field.getDeclaringClass();
            String typeName = type.getName();
            if (typeName.equals("Ljava/lang/Character$CharacterCache;") ||
                            typeName.equals("Ljava/lang/Byte$ByteCache;") ||
                            typeName.equals("Ljava/lang/Short$ShortCache;") ||
                            typeName.equals("Ljava/lang/Integer$IntegerCache;") ||
                            typeName.equals("Ljava/lang/Long$LongCache;")) {
                return true;
            }
        }
        return false;
    }

    protected boolean isDefaultStableField(ResolvedJavaField field, ConstantFieldTool<?> tool) {
        assert isStableField(field, tool);
        if (isSyntheticEnumSwitchMap(field)) {
            return true;
        }
        return false;
    }

    @SuppressWarnings("unused")
    protected boolean isFinalField(ResolvedJavaField field, ConstantFieldTool<?> tool) {
        return field.isFinal();
    }

    protected boolean isSyntheticEnumSwitchMap(ResolvedJavaField field) {
        if (field.isSynthetic() && field.isStatic() && isArray(field)) {
            String name = field.getName();
            if (field.isFinal() && name.equals("$VALUES") || name.equals("ENUM$VALUES")) {
                // generated int[] field for EnumClass::values()
                return true;
            } else if (name.startsWith("$SwitchMap$") || name.startsWith("$SWITCH_TABLE$")) {
                // javac and ecj generate a static field in an inner class for a switch on an enum
                // named $SwitchMap$p$k$g$EnumClass and $SWITCH_TABLE$p$k$g$EnumClass, respectively
                return true;
            }
        }
        return false;
    }

    private final ResolvedJavaField stringValueField;
    private final ResolvedJavaField stringHashField;

    protected boolean isWellKnownImplicitStableField(ResolvedJavaField field) {
        if (isArray(field) && field.isFinal() && field.getName().equals("cache")) {
            ResolvedJavaType type = field.getDeclaringClass();
            String typeName = type.getName();
            if (typeName.equals("Ljdk/incubator/vector/VectorOperators$ImplCache;")) {
                return true;
            }
        }
        if (field.getName().equals("dummyVector")) {
            ResolvedJavaType type = field.getDeclaringClass();
            String typeName = type.getName();
            if (typeName.equals("Ljdk/incubator/vector/AbstractSpecies;")) {
                return true;
            }
        }
        if (field.getName().equals("asIntegral") || field.getName().equals("asFloating")) {
            ResolvedJavaType type = field.getDeclaringClass();
            String typeName = type.getName();
            if (typeName.equals("Ljdk/incubator/vector/LaneType;")) {
                return true;
            }
        }
        return field.equals(stringValueField);
    }

    @Override
    public boolean isTrustedFinal(CanonicalizerTool tool, ResolvedJavaField field) {
        return false;
    }
}
