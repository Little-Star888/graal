/*
 * Copyright (c) 2015, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.code;

import static com.oracle.svm.core.code.CodeInfoDecoder.FrameInfoState.NO_SUCCESSOR_INDEX_MARKER;

import java.util.Arrays;

import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.c.NonmovableArray;
import com.oracle.svm.core.c.NonmovableArrays;
import com.oracle.svm.core.c.NonmovableObjectArray;
import com.oracle.svm.core.code.CodeInfoDecoder.FrameInfoState;
import com.oracle.svm.core.code.FrameInfoQueryResult.ValueInfo;
import com.oracle.svm.core.code.FrameInfoQueryResult.ValueType;
import com.oracle.svm.core.heap.RestrictHeapAccess;
import com.oracle.svm.core.jdk.UninterruptibleUtils;
import com.oracle.svm.core.log.Log;
import com.oracle.svm.core.meta.SharedMethod;
import com.oracle.svm.core.meta.SubstrateObjectConstant;
import com.oracle.svm.core.util.NonmovableByteArrayTypeReader;
import com.oracle.svm.core.util.VMError;

import jdk.graal.compiler.core.common.type.CompressibleConstant;
import jdk.graal.compiler.core.common.util.TypeConversion;
import jdk.graal.compiler.nodes.FrameState;
import jdk.graal.compiler.nodes.FrameState.StackState;
import jdk.vm.ci.code.BytecodeFrame;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;

public class FrameInfoDecoder {

    /**
     * Shift of raw bci values to make room for the {@link #ENCODED_BCI_DURING_CALL_MASK} and
     * {@link #ENCODED_BCI_RETHROW_EXCEPTION_MASK} flags.
     */
    protected static final int ENCODED_BCI_SHIFT = 2;
    /**
     * Added to raw bci values to make them non-negative. Some of the negative marker values defined
     * in {@link BytecodeFrame} can show up as bci values. The encoded bci is stored as an unsigned
     * value to avoid wasting one bit on the sign.
     */
    protected static final int ENCODED_BCI_ADDEND = 4;
    /**
     * Flag in the encoded bci to preserve {@link StackState#duringCall} information.
     */
    protected static final int ENCODED_BCI_DURING_CALL_MASK = 2;
    /**
     * Flag in the encoded bci to preserve {@link FrameState#rethrowException()} information.
     */
    protected static final int ENCODED_BCI_RETHROW_EXCEPTION_MASK = 1;

    /**
     * There cannot be any frame state with both the {@link #ENCODED_BCI_DURING_CALL_MASK} and
     * {@link #ENCODED_BCI_RETHROW_EXCEPTION_MASK} flag set, so we can use that combination as a
     * marker value.
     */
    protected static final int ENCODED_BCI_NO_CALLER = ENCODED_BCI_DURING_CALL_MASK | ENCODED_BCI_RETHROW_EXCEPTION_MASK;

    /**
     * Differentiates between compressed and uncompressed frame slices. See
     * {@link CompressedFrameDecoderHelper#isCompressedFrameSlice(int)} for more information.
     */
    protected static final int UNCOMPRESSED_FRAME_SLICE_MARKER = -1;
    /**
     * Value added to source line to guarantee the value is greater than zero.
     */
    protected static final int COMPRESSED_FRAME_POINTER_ADDEND = 2;
    /**
     * Value subtracted from the negated method name index when there is a unique shared frame
     * successor to guarantee the value is less than zero.
     */
    protected static final int COMPRESSED_UNIQUE_SUCCESSOR_ADDEND = 1;
    /**
     * Value added to source line to guarantee the value is greater than zero.
     */
    protected static final int COMPRESSED_SOURCE_LINE_ADDEND = 3;

    protected static boolean isFrameInfoMatch(long frameInfoIndex, NonmovableArray<Byte> frameInfoEncodings, long searchEncodedBci) {
        NonmovableByteArrayTypeReader readBuffer = new NonmovableByteArrayTypeReader(frameInfoEncodings, frameInfoIndex);
        int firstValue = readBuffer.getSVInt();
        if (CompressedFrameDecoderHelper.isCompressedFrameSlice(firstValue)) {
            /* Compressed frame slices have no local bci information. */
            return false;
        }

        /* Read encoded bci from uncompressed frame slice. */
        long actualEncodedBci = readBuffer.getUV();
        assert actualEncodedBci != ENCODED_BCI_NO_CALLER : actualEncodedBci;

        return actualEncodedBci == searchEncodedBci;
    }

    public interface FrameInfoQueryResultAllocator {
        @RestrictHeapAccess(reason = "Whitelisted because some implementations can allocate.", access = RestrictHeapAccess.Access.UNRESTRICTED)
        FrameInfoQueryResult newFrameInfoQueryResult();
    }

    static class HeapBasedFrameInfoQueryResultAllocator implements FrameInfoQueryResultAllocator {
        @Override
        public FrameInfoQueryResult newFrameInfoQueryResult() {
            return new FrameInfoQueryResult();
        }
    }

    public static final ConstantAccess SubstrateConstantAccess = new ConstantAccess();

    public static class ConstantAccess {
        public JavaConstant forObject(Object object, boolean isCompressedReference) {
            return SubstrateObjectConstant.forObject(object, isCompressedReference);
        }

        public Object asObject(JavaConstant constant) {
            return SubstrateObjectConstant.asObject(constant);
        }
    }

    static final HeapBasedFrameInfoQueryResultAllocator HeapBasedFrameInfoQueryResultAllocator = new HeapBasedFrameInfoQueryResultAllocator();

    public interface ValueInfoAllocator {
        ValueInfo newValueInfo();

        ValueInfo[] newValueInfoArray(int len);

        ValueInfo[][] newValueInfoArrayArray(int len);

        void decodeConstant(ValueInfo valueInfo, NonmovableObjectArray<?> frameInfoObjectConstants, ConstantAccess constantAccess);
    }

    static class HeapBasedValueInfoAllocator implements ValueInfoAllocator {
        @Override
        @RestrictHeapAccess(reason = "Whitelisted because some implementations can allocate.", access = RestrictHeapAccess.Access.UNRESTRICTED)
        public ValueInfo newValueInfo() {
            return new ValueInfo();
        }

        @Override
        @RestrictHeapAccess(reason = "Whitelisted because some implementations can allocate.", access = RestrictHeapAccess.Access.UNRESTRICTED)
        public ValueInfo[] newValueInfoArray(int len) {
            return new ValueInfo[len];
        }

        @Override
        @RestrictHeapAccess(reason = "Whitelisted because some implementations can allocate.", access = RestrictHeapAccess.Access.UNRESTRICTED)
        public ValueInfo[][] newValueInfoArrayArray(int len) {
            return new ValueInfo[len][];
        }

        @Override
        @RestrictHeapAccess(reason = "Whitelisted because some implementations can allocate.", access = RestrictHeapAccess.Access.UNRESTRICTED)
        public void decodeConstant(ValueInfo valueInfo, NonmovableObjectArray<?> frameInfoObjectConstants, ConstantAccess constantAccess) {
            switch (valueInfo.type) {
                case DefaultConstant:
                    switch (valueInfo.kind) {
                        case Object:
                            valueInfo.value = constantAccess.forObject(null, valueInfo.isCompressedReference);
                            assert valueInfo.isCompressedReference == CompressibleConstant.isCompressed(valueInfo.value);
                            assert valueInfo.value.isDefaultForKind() : valueInfo;
                            break;
                        default:
                            valueInfo.value = JavaConstant.defaultForKind(valueInfo.kind);
                    }
                    break;
                case Constant:
                    switch (valueInfo.kind) {
                        case Object:
                            valueInfo.value = constantAccess.forObject(NonmovableArrays.getObject(frameInfoObjectConstants, TypeConversion.asS4(valueInfo.data)),
                                            valueInfo.isCompressedReference);
                            assert valueInfo.isCompressedReference == CompressibleConstant.isCompressed(valueInfo.value);
                            break;
                        case Float:
                            valueInfo.value = JavaConstant.forFloat(Float.intBitsToFloat(TypeConversion.asS4(valueInfo.data)));
                            break;
                        case Double:
                            valueInfo.value = JavaConstant.forDouble(Double.longBitsToDouble(valueInfo.data));
                            break;
                        default:
                            assert valueInfo.kind.isNumericInteger() : valueInfo;
                            valueInfo.value = JavaConstant.forIntegerKind(valueInfo.kind, valueInfo.data);
                    }
                    break;
            }
        }
    }

    static final HeapBasedValueInfoAllocator HeapBasedValueInfoAllocator = new HeapBasedValueInfoAllocator();

    private static final class CompressedFrameDecoderHelper {
        /**
         * Differentiates between compressed and uncompressed frame slices. Uncompressed frame
         * slices start with {@link #UNCOMPRESSED_FRAME_SLICE_MARKER}.
         */
        @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
        private static boolean isCompressedFrameSlice(int firstValue) {
            return firstValue != UNCOMPRESSED_FRAME_SLICE_MARKER;
        }

        /**
         * Determines whether a value is a pointer to a shared frame index. See
         * FrameInfoEncoder.encodeCompressedFirstEntry for more details.
         */
        @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
        private static boolean isSharedFramePointer(int value) {
            return value < 0;
        }

        /**
         * Complement of FrameInfoEncoder.encodeCompressedFirstEntry when a shared frame index is
         * encoded.
         */
        @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
        private static int decodeSharedFrameIndex(int value) {
            VMError.guarantee(value < UNCOMPRESSED_FRAME_SLICE_MARKER);

            return -(value + COMPRESSED_FRAME_POINTER_ADDEND);
        }

        /**
         * Determines whether the compressedBci signals that this frame also encodes a
         * uniqueSharedFrameSuccessor. See {@link FrameInfoEncoder#encodeCompressedEncodedBci}.
         */
        @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
        private static boolean hasEncodedUniqueSharedFrameSuccessor(long compressedBci) {
            return compressedBci < 0;
        }

        /**
         * Complement of {@link FrameInfoEncoder#encodeCompressedEncodedBci}.
         */
        @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
        private static long decodeCompressedEncodedBci(long bci) {
            if (bci < 0) {
                return -(bci + COMPRESSED_UNIQUE_SUCCESSOR_ADDEND);
            } else {
                return bci;
            }
        }

        /**
         * See FrameInfoEncoder.encodeCompressedSourceLineNumber for details.
         */
        @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
        private static boolean isSliceEnd(int encodedSourceLineNumber) {
            return encodedSourceLineNumber < 0;
        }

        /**
         * Complement of FrameInfoEncode.encodeCompressedSourceLineNumber.
         */
        @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
        private static int decodeSourceLineNumber(int sourceLineNumber) {
            return UninterruptibleUtils.Math.abs(sourceLineNumber) - COMPRESSED_SOURCE_LINE_ADDEND;
        }

    }

    protected static FrameInfoQueryResult decodeFrameInfo(boolean isDeoptEntry, ReusableTypeReader readBuffer, CodeInfo info, ConstantAccess constantAccess) {
        return decodeFrameInfo(isDeoptEntry, readBuffer, info, FrameInfoDecoder.HeapBasedFrameInfoQueryResultAllocator, FrameInfoDecoder.HeapBasedValueInfoAllocator,
                        constantAccess, new FrameInfoState());
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    protected static FrameInfoQueryResult decodeFrameInfo(boolean isDeoptEntry, ReusableTypeReader readBuffer, CodeInfo info,
                    FrameInfoQueryResultAllocator resultAllocator, ValueInfoAllocator valueInfoAllocator, ConstantAccess constantAccess, FrameInfoState state) {
        if (state.isFirstFrame) {
            state.firstValue = readBuffer.getSVInt();
        }

        FrameInfoQueryResult result;
        if (CompressedFrameDecoderHelper.isCompressedFrameSlice(state.firstValue)) {
            result = decodeCompressedFrameInfo(isDeoptEntry, readBuffer, resultAllocator, state, CodeInfoAccess.getMethodTableFirstId(info));
        } else {
            result = decodeUncompressedFrameInfo(isDeoptEntry, readBuffer, info, resultAllocator, valueInfoAllocator, constantAccess, state);
        }
        state.isFirstFrame = false;

        return result;
    }

    /*
     * See (FrameInfoEncoder.CompressedFrameInfoEncodingMetadata) for more information about the
     * compressed encoding format.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static FrameInfoQueryResult decodeCompressedFrameInfo(boolean isDeoptEntry, ReusableTypeReader readBuffer, FrameInfoQueryResultAllocator resultAllocator, FrameInfoState state,
                    int methodIdAddend) {
        FrameInfoQueryResult result = null;
        FrameInfoQueryResult prev = null;

        while (!state.isDone) {
            FrameInfoQueryResult cur = newFrameInfoQueryResult(resultAllocator);
            if (cur == null) {
                return result;
            }

            cur.isDeoptEntry = isDeoptEntry;

            long bufferIndexToRestore = -1;
            if (state.successorIndex != NO_SUCCESSOR_INDEX_MARKER) {
                bufferIndexToRestore = readBuffer.getByteIndex();
                readBuffer.setByteIndex(state.successorIndex);
            }

            final int firstEntry;
            if (state.isFirstFrame) {
                firstEntry = state.firstValue;
            } else {
                firstEntry = readBuffer.getSVInt();
                assert !isDeoptEntry : "Deoptimization entry must not have inlined frames";
            }

            if (CompressedFrameDecoderHelper.isSharedFramePointer(firstEntry)) {
                assert state.successorIndex == NO_SUCCESSOR_INDEX_MARKER && bufferIndexToRestore == -1 : state;
                long sharedFrameByteIndex = CompressedFrameDecoderHelper.decodeSharedFrameIndex(firstEntry);

                // save current buffer index
                bufferIndexToRestore = readBuffer.getByteIndex();

                // jump to shared frame index
                readBuffer.setByteIndex(sharedFrameByteIndex);

                int methodId = readBuffer.getSVInt();
                VMError.guarantee(!CompressedFrameDecoderHelper.isSharedFramePointer(methodId));
                decodeCompressedFrameData(readBuffer, state, methodId, cur, methodIdAddend);

                // jump back to frame slice information
                readBuffer.setByteIndex(bufferIndexToRestore);
                bufferIndexToRestore = -1;
            } else {
                decodeCompressedFrameData(readBuffer, state, firstEntry, cur, methodIdAddend);
            }

            if (bufferIndexToRestore != -1) {
                readBuffer.setByteIndex(bufferIndexToRestore);
            }

            if (prev == null) {
                // first frame read during this invocation
                result = cur;
            } else {
                prev.caller = cur;
            }
            prev = cur;

            state.isFirstFrame = false;
        }

        return result;
    }

    @Uninterruptible(reason = "Some allocators are interruptible.", calleeMustBe = false)
    private static FrameInfoQueryResult newFrameInfoQueryResult(FrameInfoQueryResultAllocator resultAllocator) {
        return resultAllocator.newFrameInfoQueryResult();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static void decodeCompressedFrameData(ReusableTypeReader readBuffer, FrameInfoState state, int methodId, FrameInfoQueryResult queryResult, int methodIdAddend) {
        queryResult.sourceMethodId = methodId + methodIdAddend;

        int encodedSourceLineNumber = readBuffer.getSVInt();
        long compressedBci = readBuffer.getSV();

        queryResult.sourceLineNumber = CompressedFrameDecoderHelper.decodeSourceLineNumber(encodedSourceLineNumber);
        queryResult.encodedBci = CompressedFrameDecoderHelper.decodeCompressedEncodedBci(compressedBci);

        if (CompressedFrameDecoderHelper.hasEncodedUniqueSharedFrameSuccessor(compressedBci)) {
            state.successorIndex = readBuffer.getSVInt();
        } else {
            state.successorIndex = NO_SUCCESSOR_INDEX_MARKER;
        }

        state.isDone = CompressedFrameDecoderHelper.isSliceEnd(encodedSourceLineNumber);

        assert !state.isDone || state.successorIndex == NO_SUCCESSOR_INDEX_MARKER : state;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static FrameInfoQueryResult decodeUncompressedFrameInfo(boolean isDeoptEntry, ReusableTypeReader readBuffer, CodeInfo info,
                    FrameInfoQueryResultAllocator resultAllocator, ValueInfoAllocator valueInfoAllocator, ConstantAccess constantAccess, FrameInfoState state) {
        FrameInfoQueryResult result = null;
        FrameInfoQueryResult prev = null;
        ValueInfo[][] virtualObjects = null;

        int methodIdAddend = CodeInfoAccess.getMethodTableFirstId(info);
        while (!state.isDone) {
            long start = readBuffer.getByteIndex();
            long encodedBci = readBuffer.getUV();
            if (encodedBci == ENCODED_BCI_NO_CALLER) {
                state.isDone = true;
                return result;
            }

            FrameInfoQueryResult cur = newFrameInfoQueryResult(resultAllocator);
            if (cur == null) {
                readBuffer.setByteIndex(start);
                return result;
            }

            assert state.isFirstFrame || !isDeoptEntry : "Deoptimization entry must not have inlined frames";

            cur.encodedBci = encodedBci;
            cur.isDeoptEntry = isDeoptEntry;

            cur.numLocks = readBuffer.getUVInt();
            cur.numLocals = readBuffer.getUVInt();
            cur.numStack = readBuffer.getUVInt();

            int deoptMethodIndex = readBuffer.getSVInt();
            if (deoptMethodIndex < 0) {
                /*
                 * Negative number is a reference to the target method (runtime compilations only).
                 */
                cur.deoptMethod = (SharedMethod) NonmovableArrays.getObject(CodeInfoAccess.getObjectConstants(info), -1 - deoptMethodIndex);
                cur.deoptMethodOffset = cur.deoptMethod.getImageCodeDeoptOffset();
                assert cur.deoptMethodOffset != 0;
            } else if (deoptMethodIndex > 0) {
                /*
                 * Positive number is a directly encoded method offset (AOT compilations only, to
                 * avoid unnecessary method metadata).
                 */
                assert CodeInfoAccess.isAOTImageCode(info);
                cur.deoptMethodOffset = deoptMethodIndex;
                cur.deoptMethodImageCodeInfo = info;
            } else {
                assert cur.deoptMethod == null && cur.deoptMethodOffset == 0 && cur.isDeoptMethodImageCodeInfoNull();
            }

            int curValueInfosLength = readBuffer.getUVInt();
            cur.valueInfos = decodeValues(valueInfoAllocator, constantAccess, curValueInfosLength, readBuffer, CodeInfoAccess.getObjectConstants(info));

            if (state.isFirstFrame) {
                /* This is the first frame, i.e., the top frame that will be returned. */
                int numVirtualObjects = readBuffer.getUVInt();
                virtualObjects = newValueInfoArrayArray(valueInfoAllocator, numVirtualObjects);
                for (int i = 0; i < numVirtualObjects; i++) {
                    int numValues = readBuffer.getUVInt();
                    ValueInfo[] decodedValues = decodeValues(valueInfoAllocator, constantAccess, numValues, readBuffer, CodeInfoAccess.getObjectConstants(info));
                    if (virtualObjects != null) {
                        virtualObjects[i] = decodedValues;
                    }
                }
            }
            cur.virtualObjects = virtualObjects;

            cur.sourceMethodId = readBuffer.getSVInt() + methodIdAddend;
            cur.sourceLineNumber = readBuffer.getSVInt();

            if (prev == null) {
                // first frame read during this invocation
                result = cur;
            } else {
                prev.caller = cur;
            }
            prev = cur;

            state.isFirstFrame = false;
        }

        return result;
    }

    @Uninterruptible(reason = "Some allocators are interruptible.", calleeMustBe = false)
    private static ValueInfo[][] newValueInfoArrayArray(ValueInfoAllocator valueInfoAllocator, int numVirtualObjects) {
        return valueInfoAllocator.newValueInfoArrayArray(numVirtualObjects);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static ValueInfo[] decodeValues(ValueInfoAllocator valueInfoAllocator, ConstantAccess constantAccess, int numValues, ReusableTypeReader readBuffer,
                    NonmovableObjectArray<?> frameInfoObjectConstants) {
        ValueInfo[] valueInfos = newValueInfoArray(valueInfoAllocator, numValues);

        for (int i = 0; i < numValues; i++) {
            ValueInfo valueInfo = newValueInfo(valueInfoAllocator);
            if (valueInfos != null) {
                valueInfos[i] = valueInfo;
            }

            int flags = readBuffer.getU1();
            ValueType valueType = extractType(flags);
            if (valueInfo != null) {
                valueInfo.type = valueType;
                valueInfo.kind = extractKind(flags);
                valueInfo.isCompressedReference = extractIsCompressedReference(flags);
                valueInfo.isEliminatedMonitor = extractIsEliminatedMonitor(flags);
            }
            if (valueType.hasData) {
                long valueInfoData = readBuffer.getSV();
                if (valueInfo != null) {
                    valueInfo.data = valueInfoData;
                }
            }
            decodeConstant(valueInfoAllocator, constantAccess, frameInfoObjectConstants, valueInfo);
        }
        return valueInfos;
    }

    @Uninterruptible(reason = "Some allocators are interruptible.", calleeMustBe = false)
    private static void decodeConstant(ValueInfoAllocator valueInfoAllocator, ConstantAccess constantAccess, NonmovableObjectArray<?> frameInfoObjectConstants, ValueInfo valueInfo) {
        valueInfoAllocator.decodeConstant(valueInfo, frameInfoObjectConstants, constantAccess);
    }

    @Uninterruptible(reason = "Some allocators are interruptible.", calleeMustBe = false)
    private static ValueInfo[] newValueInfoArray(ValueInfoAllocator valueInfoAllocator, int numValues) {
        return valueInfoAllocator.newValueInfoArray(numValues);
    }

    @Uninterruptible(reason = "Some allocators are interruptible.", calleeMustBe = false)
    private static ValueInfo newValueInfo(ValueInfoAllocator valueInfoAllocator) {
        return valueInfoAllocator.newValueInfo();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    protected static int decodeBci(long encodedBci) {
        assert encodedBci >= 0 && encodedBci != FrameInfoDecoder.ENCODED_BCI_NO_CALLER;
        long result = (encodedBci >> ENCODED_BCI_SHIFT) - ENCODED_BCI_ADDEND;
        assert result >= Integer.MIN_VALUE && result <= Integer.MAX_VALUE;
        return (int) result;
    }

    protected static boolean decodeDuringCall(long encodedBci) {
        assert encodedBci >= 0 && encodedBci != FrameInfoDecoder.ENCODED_BCI_NO_CALLER : encodedBci;
        return (encodedBci & ENCODED_BCI_DURING_CALL_MASK) != 0;
    }

    public static boolean decodeRethrowException(long encodedBci) {
        assert encodedBci >= 0 && encodedBci != FrameInfoDecoder.ENCODED_BCI_NO_CALLER : encodedBci;
        return (encodedBci & ENCODED_BCI_RETHROW_EXCEPTION_MASK) != 0;
    }

    public static String readableBci(long encodedBci) {
        return decodeBci(encodedBci) +
                        (decodeDuringCall(encodedBci) ? " duringCall" : "") +
                        (decodeRethrowException(encodedBci) ? " rethrowException" : "");
    }

    public static void logReadableBci(Log log, long encodedBci) {
        log.signed(decodeBci(encodedBci));
        if (decodeDuringCall(encodedBci)) {
            log.string(" duringCall");
        }
        if (decodeRethrowException(encodedBci)) {
            log.string(" rethrowException");
        }
    }

    protected static final int TYPE_BITS = 3;
    protected static final int TYPE_SHIFT = 0;
    protected static final int TYPE_MASK_IN_PLACE = ((1 << TYPE_BITS) - 1) << TYPE_SHIFT;

    protected static final int KIND_BITS = 4;
    protected static final int KIND_SHIFT = TYPE_SHIFT + TYPE_BITS;
    protected static final int KIND_MASK_IN_PLACE = ((1 << KIND_BITS) - 1) << KIND_SHIFT;

    /**
     * Value not used by {@link JavaKind} as a marker for eliminated monitors. The kind of a monitor
     * is always {@link JavaKind#Object}.
     */
    protected static final int IS_ELIMINATED_MONITOR_KIND_VALUE = 15;

    protected static final int IS_COMPRESSED_REFERENCE_BITS = 1;
    protected static final int IS_COMPRESSED_REFERENCE_SHIFT = KIND_SHIFT + KIND_BITS;
    protected static final int IS_COMPRESSED_REFERENCE_MASK_IN_PLACE = ((1 << IS_COMPRESSED_REFERENCE_BITS) - 1) << IS_COMPRESSED_REFERENCE_SHIFT;

    protected static final JavaKind[] KIND_VALUES;

    static {
        KIND_VALUES = Arrays.copyOf(JavaKind.values(), IS_ELIMINATED_MONITOR_KIND_VALUE + 1);
        assert KIND_VALUES[IS_ELIMINATED_MONITOR_KIND_VALUE] == null;
        KIND_VALUES[IS_ELIMINATED_MONITOR_KIND_VALUE] = JavaKind.Object;
    }

    /* Allow allocation-free access to ValueType values */
    private static final ValueType[] ValueTypeValues = ValueType.values();

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static ValueType extractType(int flags) {
        return ValueTypeValues[(flags & TYPE_MASK_IN_PLACE) >> TYPE_SHIFT];
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static JavaKind extractKind(int flags) {
        return KIND_VALUES[(flags & KIND_MASK_IN_PLACE) >> KIND_SHIFT];
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static boolean extractIsCompressedReference(int flags) {
        return (flags & IS_COMPRESSED_REFERENCE_MASK_IN_PLACE) != 0;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static boolean extractIsEliminatedMonitor(int flags) {
        return ((flags & KIND_MASK_IN_PLACE) >> KIND_SHIFT) == IS_ELIMINATED_MONITOR_KIND_VALUE;
    }
}
