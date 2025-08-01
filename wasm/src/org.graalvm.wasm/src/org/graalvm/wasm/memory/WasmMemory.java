/*
 * Copyright (c) 2019, 2025, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.wasm.memory;

import static java.lang.Long.compareUnsigned;
import static org.graalvm.wasm.Assert.assertUnsignedLongLessOrEqual;
import static org.graalvm.wasm.constants.Sizes.MAX_MEMORY_64_DECLARATION_SIZE;
import static org.graalvm.wasm.constants.Sizes.MAX_MEMORY_64_INSTANCE_SIZE;
import static org.graalvm.wasm.constants.Sizes.MAX_MEMORY_DECLARATION_SIZE;
import static org.graalvm.wasm.constants.Sizes.MAX_MEMORY_INSTANCE_SIZE;

import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.graalvm.wasm.EmbedderDataHolder;
import org.graalvm.wasm.api.WebAssembly;
import org.graalvm.wasm.collection.ByteArrayList;
import org.graalvm.wasm.constants.Sizes;
import org.graalvm.wasm.exception.Failure;
import org.graalvm.wasm.exception.WasmException;
import org.graalvm.wasm.nodes.WasmFunctionNode;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.InvalidBufferOffsetException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;

@ExportLibrary(InteropLibrary.class)
public abstract class WasmMemory extends EmbedderDataHolder implements TruffleObject {

    /**
     * @see #declaredMinSize()
     */
    protected final long declaredMinSize;

    /**
     * @see #declaredMaxSize()
     */
    protected final long declaredMaxSize;

    /**
     * @see #minSize()
     */
    protected long currentMinSize;

    /**
     * The maximum practical size of this memory instance (measured in number of
     * {@link Sizes#MEMORY_PAGE_SIZE pages}).
     * <p>
     * It is the minimum between {@link #declaredMaxSize the limit defined in the module binary} and
     * the limit imposed by the implementation.
     * <p>
     * This is different from {@link #declaredMaxSize()}, which can be higher.
     */
    protected final long maxAllowedSize;

    /**
     * @see #hasIndexType64()
     */
    protected final boolean indexType64;

    /**
     * @see #isShared()
     */
    protected final boolean shared;

    @TruffleBoundary
    protected WasmMemory(long declaredMinSize, long declaredMaxSize, long initialSize, long maxAllowedSize, boolean indexType64, boolean shared) {
        assertUnsignedLongLessOrEqual(initialSize, maxAllowedSize, Failure.MEMORY_SIZE_LIMIT_EXCEEDED, "Initial memory size exceeds implementation limit");

        assert compareUnsigned(declaredMinSize, initialSize) <= 0;
        assert compareUnsigned(initialSize, maxAllowedSize) <= 0;
        assert compareUnsigned(maxAllowedSize, declaredMaxSize) <= 0;
        assert indexType64 || compareUnsigned(maxAllowedSize, MAX_MEMORY_INSTANCE_SIZE) <= 0;
        assert indexType64 || compareUnsigned(declaredMaxSize, MAX_MEMORY_DECLARATION_SIZE) <= 0;
        assert !indexType64 || compareUnsigned(maxAllowedSize, MAX_MEMORY_64_INSTANCE_SIZE) <= 0;
        assert !indexType64 || compareUnsigned(declaredMaxSize, MAX_MEMORY_64_DECLARATION_SIZE) <= 0;

        this.declaredMinSize = declaredMinSize;
        this.declaredMaxSize = declaredMaxSize;
        this.currentMinSize = declaredMinSize;
        this.maxAllowedSize = maxAllowedSize;
        this.indexType64 = indexType64;
        this.shared = shared;
    }

    /**
     * The minimum size of this memory as declared in the binary (measured in number of
     * {@link Sizes#MEMORY_PAGE_SIZE pages}).
     * <p>
     * This is different from the current minimum size, which can be larger.
     */
    public final long declaredMinSize() {
        return declaredMinSize;
    }

    /**
     * The maximum size of this memory as declared in the binary (measured in number of
     * {@link Sizes#MEMORY_PAGE_SIZE pages}).
     * <p>
     * This is an upper bound on this memory's size. This memory can only be imported with a greater
     * or equal maximum size.
     * <p>
     * This is different from the internal maximum allowed size, which can be lower.
     */
    public final long declaredMaxSize() {
        return declaredMaxSize;
    }

    /**
     * The current minimum size of the memory (measured in number of {@link Sizes#MEMORY_PAGE_SIZE
     * pages}). The size can change based on calls to
     * {@link WasmMemoryLibrary#grow(WasmMemory,long)}.
     * <p>
     * This is a lower bound on this memory's size. This memory can only be imported with a lower or
     * equal minimum size.
     */
    public final long minSize() {
        return currentMinSize;
    }

    public final long maxAllowedSize() {
        return maxAllowedSize;
    }

    /**
     * @return Whether the index type (addressing mode) is 64-bit or 32-bit.
     */
    public final boolean hasIndexType64() {
        return indexType64;
    }

    /**
     * @return Whether the memory is shared (modifications are visible to other threads).
     */
    public final boolean isShared() {
        return shared;
    }

    @TruffleBoundary
    protected static final WasmException trapOutOfBounds(Node node, long address, long length, long byteSize) {
        final String message = String.format(Locale.ROOT, "%d-byte memory access at address 0x%016X (%d) is out-of-bounds (memory size %d bytes).",
                        length, address, address, byteSize);
        return WasmException.create(Failure.OUT_OF_BOUNDS_MEMORY_ACCESS, node, message);
    }

    @TruffleBoundary
    protected static final WasmException trapUnalignedAtomic(Node node, long address, int length) {
        final String message = String.format(Locale.ROOT, "%d-byte atomic memory access at address 0x%016X (%d) is unaligned.",
                        length, address, address);
        return WasmException.create(Failure.UNALIGNED_ATOMIC, node, message);
    }

    @TruffleBoundary
    protected static final WasmException trapNegativeLength(Node node, long length) {
        final String message = String.format(Locale.ROOT, "memory access of negative length %d.", length);
        return WasmException.create(Failure.OUT_OF_BOUNDS_MEMORY_ACCESS, node, message);
    }

    @TruffleBoundary
    protected static final WasmException trapUnsharedMemory(Node node) {
        final String message = "Atomic wait operator can only be used on shared memories.";
        return WasmException.create(Failure.EXPECTED_SHARED_MEMORY, node, message);
    }

    @TruffleBoundary
    protected static final WasmException trapOutOfBoundsBuffer(Node node, long offset, long length, long bufferSize) {
        final String message = String.format(Locale.ROOT, "%d-byte buffer access at offset %d is out-of-bounds (buffer size %d bytes).",
                        length, offset, bufferSize);
        return WasmException.create(Failure.OUT_OF_BOUNDS_MEMORY_ACCESS, node, message);
    }

    /**
     * Validates that the region starting at offset {@code address} and of length {@code length} is
     * in bounds for a memory of size {@code byteSize}. If the region is not in bounds, then this
     * traps (throws a {@link WasmException}).
     * <p>
     * Pre-conditions:
     * <ul>
     * <li>{@code length >= 0}, can be validated using {@link #validateLength(Node, long)}</li>
     * <li>{@code byteSize >= 0}</li>
     * </ul>
     * </p>
     *
     * @param node the node used for errors
     * @param address the offset at which the region to be validated starts
     * @param length the length of the region to be validated, assumed to be non-negative
     * @param byteSize the size of the memory in bytes, assumed to be non-negative
     */
    protected static final void validateAddress(Node node, long address, long length, long byteSize) {
        assert length >= 0;
        assert byteSize >= 0;
        if (address < 0 || address > byteSize - length) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw trapOutOfBounds(node, address, length, byteSize);
        }
    }

    /**
     * Validates that the offset {@code address} is {@code length}-byte aligned, i.e., that
     * {@code address} is a multiple of {@code length}.
     *
     * <p>
     * Note that this will not validate that {@code address} and {@code length} form an in-bounds
     * region. Use {@link #validateAddress(Node, long, long, long)} for that.
     * </p>
     *
     * @param node the node used for errors
     * @param address the offset whose alignment is to be validated
     * @param length the alignment length to be enforced, must be a power of two
     */
    protected static final void validateAtomicAddress(Node node, long address, int length) {
        assert length != 0 && ((length - 1) & length) == 0 : "alignment length must be a power of two";
        if ((address & (length - 1)) != 0) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw trapUnalignedAtomic(node, address, length);
        }
    }

    /**
     * Validates that the argument {@code length} is non-negative. If {@code length} is negative,
     * then this traps (throws {@link WasmException}).
     *
     * @param node the node used for errors
     * @param length the region length to be validated
     */
    protected static final void validateLength(Node node, long length) {
        if (length < 0) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw trapNegativeLength(node, length);
        }
    }

    /**
     * Reads the null-terminated UTF-8 string starting at {@code startOffset}.
     *
     * @param startOffset memory index of the first character
     * @param node a node indicating the location where this read occurred in the Truffle AST. It
     *            may be {@code null} to indicate that the location is not available.
     * @return the read {@code String}
     */
    @CompilerDirectives.TruffleBoundary
    public String readString(int startOffset, WasmFunctionNode<?> node) {
        ByteArrayList bytes = new ByteArrayList();
        byte currentByte;
        int offset = startOffset;

        while ((currentByte = (byte) WasmMemoryLibrary.getUncached().load_i32_8u(this, node, offset)) != 0) {
            bytes.add(currentByte);
            ++offset;
        }

        return new String(bytes.toArray(), StandardCharsets.UTF_8);
    }

    /**
     * Reads the UTF-8 string of length {@code length} starting at {@code startOffset}.
     *
     * @param startOffset memory index of the first character
     * @param length length of the UTF-8 string to read in bytes
     * @param node a node indicating the location where this read occurred in the Truffle AST. It
     *            may be {@code null} to indicate that the location is not available.
     * @return the read {@code String}
     */
    @CompilerDirectives.TruffleBoundary
    public final String readString(int startOffset, int length, Node node) {
        ByteArrayList bytes = new ByteArrayList();

        for (int i = 0; i < length; ++i) {
            bytes.add((byte) WasmMemoryLibrary.getUncached().load_i32_8u(this, node, startOffset + i));
        }

        return new String(bytes.toArray(), StandardCharsets.UTF_8);
    }

    /**
     * Writes a Java String at offset {@code offset}.
     * <p>
     * The written string is encoded as UTF-8 and <em>not</em> terminated with a null character.
     *
     * @param node a node indicating the location where this write occurred in the Truffle AST. It
     *            may be {@code null} to indicate that the location is not available.
     * @param string the string to write
     * @param offset memory index where to write the string
     * @param length the maximum number of bytes to write, including the trailing null character
     * @return the number of bytes written
     */
    @CompilerDirectives.TruffleBoundary
    public final int writeString(Node node, String string, int offset, int length) {
        final byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
        int i = 0;
        for (; i < bytes.length && i < length; ++i) {
            WasmMemoryLibrary.getUncached().store_i32_8(this, node, offset + i, bytes[i]);
        }
        return i;
    }

    public final int writeString(Node node, String string, int offset) {
        return writeString(node, string, offset, Integer.MAX_VALUE);
    }

    /**
     * Returns the number of bytes needed to write {@code string} with {@link #writeString}.
     *
     * @param string the string to write
     * @return the number of bytes needed to write {@code string}
     */
    @CompilerDirectives.TruffleBoundary
    public static int encodedStringLength(String string) {
        return string.getBytes(StandardCharsets.UTF_8).length;
    }

    long[] view(int address, int length) {
        long[] chunk = new long[length / 8];
        for (int p = address; p < address + length; p += 8) {
            chunk[(p - address) / 8] = WasmMemoryLibrary.getUncached().load_i64(this, null, p);
        }
        return chunk;
    }

    String viewByte(int address) {
        final int value = WasmMemoryLibrary.getUncached().load_i32_8u(this, null, address);
        String result = Integer.toHexString(value);
        if (result.length() == 1) {
            result = "0" + result;
        }
        return result;
    }

    public String hexView(int address, int length) {
        long[] chunk = view(address, length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunk.length; i++) {
            sb.append("0x").append(hex(address + i * 8L)).append(" | ");
            for (int j = 0; j < 8; j++) {
                sb.append(viewByte(address + i * 8 + j)).append(" ");
            }
            sb.append("| ");
            sb.append(batch(hex(chunk[i]), 2)).append("\n");
        }
        return sb.toString();
    }

    private static String hex(long value) {
        return pad(Long.toHexString(value), 16);
    }

    private static String batch(String s, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            result.insert(0, s.charAt(i));
            if ((i + 1) % count == 0) {
                result.insert(0, " ");
            }
        }
        return result.reverse().toString();
    }

    private static String pad(String s, int length) {
        StringBuilder padded = new StringBuilder(s);
        while (padded.length() < length) {
            padded.insert(0, "0");
        }
        return padded.toString();
    }

    @SuppressWarnings("static-method")
    @ExportMessage
    final boolean hasBufferElements() {
        return true;
    }

    @ExportMessage
    final long getBufferSize(@CachedLibrary(value = "this") WasmMemoryLibrary wasmMemoryLibrary) {
        return wasmMemoryLibrary.byteSize(this);
    }

    private void checkOffset(Node node, WasmMemoryLibrary wasmMemoryLibrary, long byteOffset, int opLength, InlinedBranchProfile errorBranch) throws InvalidBufferOffsetException {
        if (opLength < 0 || byteOffset < 0 || getBufferSize(wasmMemoryLibrary) - opLength < byteOffset) {
            errorBranch.enter(node);
            throw InvalidBufferOffsetException.create(byteOffset, opLength);
        }
    }

    @ExportMessage
    final void readBuffer(long byteOffset, byte[] destination, int destinationOffset, int length,
                    @Bind Node node,
                    @Shared("errorBranch") @Cached InlinedBranchProfile errorBranch,
                    @CachedLibrary(value = "this") WasmMemoryLibrary wasmMemoryLibrary) throws InvalidBufferOffsetException {
        checkOffset(node, wasmMemoryLibrary, byteOffset, length, errorBranch);
        wasmMemoryLibrary.copyToBuffer(this, node, destination, byteOffset, destinationOffset, length);
    }

    @ExportMessage
    final byte readBufferByte(long byteOffset,
                    @Bind Node node,
                    @Shared("errorBranch") @Cached InlinedBranchProfile errorBranch,
                    @CachedLibrary(value = "this") WasmMemoryLibrary wasmMemoryLibrary) throws InvalidBufferOffsetException {
        checkOffset(node, wasmMemoryLibrary, byteOffset, Byte.BYTES, errorBranch);
        return (byte) wasmMemoryLibrary.load_i32_8s(this, null, byteOffset);
    }

    @ExportMessage
    final short readBufferShort(ByteOrder order, long byteOffset,
                    @Bind Node node,
                    @Shared("errorBranch") @Cached InlinedBranchProfile errorBranch,
                    @CachedLibrary(value = "this") WasmMemoryLibrary wasmMemoryLibrary) throws InvalidBufferOffsetException {
        checkOffset(node, wasmMemoryLibrary, byteOffset, Short.BYTES, errorBranch);
        short result = (short) wasmMemoryLibrary.load_i32_16s(this, null, byteOffset);
        if (order == ByteOrder.BIG_ENDIAN) {
            result = Short.reverseBytes(result);
        }
        return result;
    }

    @ExportMessage
    final int readBufferInt(ByteOrder order, long byteOffset,
                    @Bind Node node,
                    @Shared("errorBranch") @Cached InlinedBranchProfile errorBranch,
                    @CachedLibrary(value = "this") WasmMemoryLibrary wasmMemoryLibrary) throws InvalidBufferOffsetException {
        checkOffset(node, wasmMemoryLibrary, byteOffset, Integer.BYTES, errorBranch);
        int result = wasmMemoryLibrary.load_i32(this, null, byteOffset);
        if (order == ByteOrder.BIG_ENDIAN) {
            result = Integer.reverseBytes(result);
        }
        return result;
    }

    @ExportMessage
    final long readBufferLong(ByteOrder order, long byteOffset,
                    @Bind Node node,
                    @Shared("errorBranch") @Cached InlinedBranchProfile errorBranch,
                    @CachedLibrary(value = "this") WasmMemoryLibrary wasmMemoryLibrary) throws InvalidBufferOffsetException {
        checkOffset(node, wasmMemoryLibrary, byteOffset, Long.BYTES, errorBranch);
        long result = wasmMemoryLibrary.load_i64(this, null, byteOffset);
        if (order == ByteOrder.BIG_ENDIAN) {
            result = Long.reverseBytes(result);
        }
        return result;
    }

    @ExportMessage
    final float readBufferFloat(ByteOrder order, long byteOffset,
                    @Bind Node node,
                    @Shared("errorBranch") @Cached InlinedBranchProfile errorBranch,
                    @CachedLibrary(value = "this") WasmMemoryLibrary wasmMemoryLibrary) throws InvalidBufferOffsetException {
        checkOffset(node, wasmMemoryLibrary, byteOffset, Float.BYTES, errorBranch);
        float result = wasmMemoryLibrary.load_f32(this, null, byteOffset);
        if (order == ByteOrder.BIG_ENDIAN) {
            result = Float.intBitsToFloat(Integer.reverseBytes(Float.floatToRawIntBits(result)));
        }
        return result;
    }

    @ExportMessage
    final double readBufferDouble(ByteOrder order, long byteOffset,
                    @Bind Node node,
                    @Shared("errorBranch") @Cached InlinedBranchProfile errorBranch,
                    @CachedLibrary(value = "this") WasmMemoryLibrary wasmMemoryLibrary) throws InvalidBufferOffsetException {
        checkOffset(node, wasmMemoryLibrary, byteOffset, Double.BYTES, errorBranch);
        double result = wasmMemoryLibrary.load_f64(this, null, byteOffset);
        if (order == ByteOrder.BIG_ENDIAN) {
            result = Double.longBitsToDouble(Long.reverseBytes(Double.doubleToRawLongBits(result)));
        }
        return result;
    }

    @SuppressWarnings("static-method")
    @ExportMessage
    final boolean isBufferWritable() {
        return true;
    }

    @ExportMessage
    final void writeBufferByte(long byteOffset, byte value,
                    @Bind Node node,
                    @Shared("errorBranch") @Cached InlinedBranchProfile errorBranch,
                    @CachedLibrary(value = "this") WasmMemoryLibrary wasmMemoryLibrary) throws InvalidBufferOffsetException {
        checkOffset(node, wasmMemoryLibrary, byteOffset, Byte.BYTES, errorBranch);
        wasmMemoryLibrary.store_i32_8(this, null, byteOffset, value);
    }

    @ExportMessage
    final void writeBufferShort(ByteOrder order, long byteOffset, short value,
                    @Bind Node node,
                    @Shared("errorBranch") @Cached InlinedBranchProfile errorBranch,
                    @CachedLibrary(value = "this") WasmMemoryLibrary wasmMemoryLibrary) throws InvalidBufferOffsetException {
        checkOffset(node, wasmMemoryLibrary, byteOffset, Short.BYTES, errorBranch);
        short actualValue = (order == ByteOrder.LITTLE_ENDIAN) ? value : Short.reverseBytes(value);
        wasmMemoryLibrary.store_i32_16(this, null, byteOffset, actualValue);
    }

    @ExportMessage
    final void writeBufferInt(ByteOrder order, long byteOffset, int value,
                    @Bind Node node,
                    @Shared("errorBranch") @Cached InlinedBranchProfile errorBranch,
                    @CachedLibrary(value = "this") WasmMemoryLibrary wasmMemoryLibrary) throws InvalidBufferOffsetException {
        checkOffset(node, wasmMemoryLibrary, byteOffset, Integer.BYTES, errorBranch);
        int actualValue = (order == ByteOrder.LITTLE_ENDIAN) ? value : Integer.reverseBytes(value);
        wasmMemoryLibrary.store_i32(this, null, byteOffset, actualValue);
    }

    @ExportMessage
    final void writeBufferLong(ByteOrder order, long byteOffset, long value,
                    @Bind Node node,
                    @Shared("errorBranch") @Cached InlinedBranchProfile errorBranch,
                    @CachedLibrary(value = "this") WasmMemoryLibrary wasmMemoryLibrary) throws InvalidBufferOffsetException {
        checkOffset(node, wasmMemoryLibrary, byteOffset, Long.BYTES, errorBranch);
        long actualValue = (order == ByteOrder.LITTLE_ENDIAN) ? value : Long.reverseBytes(value);
        wasmMemoryLibrary.store_i64(this, null, byteOffset, actualValue);
    }

    @ExportMessage
    final void writeBufferFloat(ByteOrder order, long byteOffset, float value,
                    @Bind Node node,
                    @Shared("errorBranch") @Cached InlinedBranchProfile errorBranch,
                    @CachedLibrary(value = "this") WasmMemoryLibrary wasmMemoryLibrary) throws InvalidBufferOffsetException {
        checkOffset(node, wasmMemoryLibrary, byteOffset, Float.BYTES, errorBranch);
        float actualValue = (order == ByteOrder.LITTLE_ENDIAN) ? value : Float.intBitsToFloat(Integer.reverseBytes(Float.floatToRawIntBits(value)));
        wasmMemoryLibrary.store_f32(this, null, byteOffset, actualValue);
    }

    @ExportMessage
    final void writeBufferDouble(ByteOrder order, long byteOffset, double value,
                    @Bind Node node,
                    @Shared("errorBranch") @Cached InlinedBranchProfile errorBranch,
                    @CachedLibrary(value = "this") WasmMemoryLibrary wasmMemoryLibrary) throws InvalidBufferOffsetException {
        checkOffset(node, wasmMemoryLibrary, byteOffset, Double.BYTES, errorBranch);
        double actualValue = (order == ByteOrder.LITTLE_ENDIAN) ? value : Double.longBitsToDouble(Long.reverseBytes(Double.doubleToRawLongBits(value)));
        wasmMemoryLibrary.store_f64(this, null, byteOffset, actualValue);
    }

    @ExportMessage
    boolean hasArrayElements() {
        return true;
    }

    @ExportMessage
    long getArraySize(@CachedLibrary(value = "this") WasmMemoryLibrary wasmMemoryLibrary) {
        return wasmMemoryLibrary.byteSize(this);
    }

    @ExportMessage
    boolean isArrayElementReadable(long address,
                    @CachedLibrary(value = "this") WasmMemoryLibrary wasmMemoryLibrary) {
        return address >= 0 && address < getArraySize(wasmMemoryLibrary);
    }

    @ExportMessage
    final boolean isArrayElementModifiable(long address,
                    @CachedLibrary(value = "this") WasmMemoryLibrary wasmMemoryLibrary) {
        return isArrayElementReadable(address, wasmMemoryLibrary);
    }

    @SuppressWarnings({"unused", "static-method"})
    @ExportMessage
    final boolean isArrayElementInsertable(long address) {
        return false;
    }

    @ExportMessage
    public Object readArrayElement(long address,
                    @Bind Node node,
                    @Shared("errorBranch") @Cached InlinedBranchProfile errorBranch,
                    @CachedLibrary(value = "this") WasmMemoryLibrary wasmMemoryLibrary) throws InvalidArrayIndexException {
        if (!isArrayElementReadable(address, wasmMemoryLibrary)) {
            errorBranch.enter(node);
            throw InvalidArrayIndexException.create(address);
        }
        return wasmMemoryLibrary.load_i32_8u(this, null, address);
    }

    @ExportMessage
    public void writeArrayElement(long address, Object value,
                    @Bind Node node,
                    @CachedLibrary(limit = "3") InteropLibrary valueLib,
                    @Shared("errorBranch") @Cached InlinedBranchProfile errorBranch,
                    @CachedLibrary(value = "this") WasmMemoryLibrary wasmMemoryLibrary)
                    throws InvalidArrayIndexException, UnsupportedMessageException, UnsupportedTypeException {
        if (!isArrayElementModifiable(address, wasmMemoryLibrary)) {
            errorBranch.enter(node);
            throw InvalidArrayIndexException.create(address);
        }
        byte rawValue;
        if (valueLib.fitsInByte(value)) {
            rawValue = valueLib.asByte(value);
        } else {
            errorBranch.enter(node);
            throw UnsupportedTypeException.create(new Object[]{value}, "Only bytes can be stored into WebAssembly memory.");
        }
        wasmMemoryLibrary.store_i32_8(this, null, address, rawValue);
    }

    protected void invokeGrowCallback() {
        WebAssembly.invokeMemGrowCallback(this);
    }

    protected int invokeNotifyCallback(Node node, long address, int count) {
        return WebAssembly.invokeMemNotifyCallback(node, this, address, count);
    }

    protected int invokeWaitCallback(Node node, long address, long expected, long timeout, boolean is64) {
        return WebAssembly.invokeMemWaitCallback(node, this, address, expected, timeout, is64);
    }

    public final WasmMemory checkSize(WasmMemoryLibrary memoryLib, long initialSize) {
        if (memoryLib.byteSize(this) < initialSize * Sizes.MEMORY_PAGE_SIZE) {
            throw CompilerDirectives.shouldNotReachHere("Memory size must not be less than initial size");
        }
        return this;
    }
}
