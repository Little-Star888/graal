/*
 * Copyright (c) 2013, 2025, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.api.object;

import static com.oracle.truffle.api.object.UnsafeAccess.ARRAY_INT_BASE_OFFSET;
import static com.oracle.truffle.api.object.UnsafeAccess.ARRAY_INT_INDEX_SCALE;
import static com.oracle.truffle.api.object.UnsafeAccess.unsafeGetLong;
import static com.oracle.truffle.api.object.UnsafeAccess.unsafeGetObject;
import static com.oracle.truffle.api.object.UnsafeAccess.unsafePutLong;
import static com.oracle.truffle.api.object.UnsafeAccess.unsafePutObject;

import java.lang.invoke.VarHandle;
import java.util.Objects;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;

/**
 * Property location.
 *
 * @see Shape
 * @see Property
 */
@SuppressWarnings("deprecation")
abstract class CoreLocations {

    static final int LONG_FIELD_SLOT_SIZE = 1;
    static final int LONG_ARRAY_SLOT_SIZE = 2;
    static final int OBJECT_SLOT_SIZE = 1;
    static final int MAX_DYNAMIC_FIELDS = 1000;

    public interface TypedLocation {
        Class<?> getType();
    }

    public interface ObjectLocation extends TypedLocation {
        @Override
        Class<? extends Object> getType();

        /**
         * If {@code true}, this location does not accept {@code null} values.
         */
        boolean isNonNull();
    }

    public interface IntLocation extends TypedLocation, com.oracle.truffle.api.object.IntLocation {
        @Override
        int getInt(DynamicObject store, boolean guard);

        void setInt(DynamicObject store, int value, boolean guard, boolean init);

        @Override
        default Class<Integer> getType() {
            return int.class;
        }

        // --- deprecated methods below ---

        @Override
        default int getInt(DynamicObject store, Shape shape) {
            return getInt(store, store.getShape() == shape);
        }

        @Override
        default void setInt(DynamicObject store, int value, Shape shape) {
            setInt(store, value, store.getShape() == shape, false);
        }
    }

    public interface LongLocation extends TypedLocation, com.oracle.truffle.api.object.LongLocation {
        @Override
        long getLong(DynamicObject store, boolean guard);

        void setLong(DynamicObject store, long value, boolean guard, boolean init);

        @Override
        default Class<Long> getType() {
            return long.class;
        }

        boolean isImplicitCastIntToLong();

        // --- deprecated methods below ---

        @Override
        default long getLong(DynamicObject store, Shape shape) {
            return getLong(store, store.getShape() == shape);
        }

        @Override
        default void setLong(DynamicObject store, long value, Shape shape) {
            setLong(store, value, store.getShape() == shape, false);
        }
    }

    public interface DoubleLocation extends TypedLocation, com.oracle.truffle.api.object.DoubleLocation {
        @Override
        double getDouble(DynamicObject store, boolean guard);

        void setDouble(DynamicObject store, double value, boolean guard, boolean init);

        @Override
        default Class<Double> getType() {
            return double.class;
        }

        boolean isImplicitCastIntToDouble();

        // --- deprecated methods below ---

        @Override
        default double getDouble(DynamicObject store, Shape shape) {
            return getDouble(store, store.getShape() == shape);
        }

        @Override
        default void setDouble(DynamicObject store, double value, Shape shape) {
            setDouble(store, value, store.getShape() == shape, false);
        }
    }

    public interface BooleanLocation extends TypedLocation, com.oracle.truffle.api.object.BooleanLocation {
        @Override
        boolean getBoolean(DynamicObject store, boolean guard);

        void setBoolean(DynamicObject store, boolean value, boolean guard, boolean init);

        @Override
        default Class<Boolean> getType() {
            return boolean.class;
        }

        // --- deprecated methods below ---

        @Override
        default boolean getBoolean(DynamicObject store, Shape shape) {
            return getBoolean(store, store.getShape() == shape);
        }

        @Override
        default void setBoolean(DynamicObject store, boolean value, Shape shape) {
            setBoolean(store, value, store.getShape() == shape, false);
        }
    }

    public abstract static class ValueLocation extends CoreLocation {

        private final Object value;

        ValueLocation(Object value) {
            assert !(value instanceof Location);
            this.value = value;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = super.hashCode();
            result = prime * result + ((value == null) ? 0 : value.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            return super.equals(obj) && Objects.equals(value, ((ValueLocation) obj).value);
        }

        @Override
        public final Object get(DynamicObject store, boolean guard) {
            return value;
        }

        @Override
        public boolean canStore(Object val) {
            return valueEquals(this.value, val);
        }

        @Override
        public final void set(DynamicObject store, Object value, boolean guard, boolean init) throws com.oracle.truffle.api.object.IncompatibleLocationException {
            if (!canStore(value)) {
                if (init) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    throw new UnsupportedOperationException();
                } else {
                    throw incompatibleLocation();
                }
            }
        }

        @Override
        public String toString() {
            return "=" + String.valueOf(value);
        }

        @Override
        public final void accept(LocationVisitor locationVisitor) {
        }

        @Override
        public final boolean isValue() {
            return true;
        }
    }

    public static final class ConstantLocation extends ValueLocation {

        ConstantLocation(Object value) {
            super(value);
        }

        @Override
        public boolean isConstant() {
            return true;
        }
    }

    public static final class DeclaredLocation extends ValueLocation {

        DeclaredLocation(Object value) {
            super(value);
        }

        @Override
        public boolean isDeclared() {
            return true;
        }
    }

    abstract static class InstanceLocation extends CoreLocation {

        protected final int index;

        protected InstanceLocation(int index) {
            this.index = index;
        }

        public final int getIndex() {
            return index;
        }
    }

    public abstract static class ArrayLocation extends InstanceLocation {

        protected ArrayLocation(int index) {
            super(index);
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = super.hashCode();
            result = prime * result + index;
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (!super.equals(obj)) {
                return false;
            }
            ArrayLocation other = (ArrayLocation) obj;
            if (index != other.index) {
                return false;
            }
            return true;
        }

        @Override
        public String getWhereString() {
            return "[" + index + "]";
        }
    }

    public abstract static class FieldLocation extends InstanceLocation {

        protected final FieldInfo field;
        protected final VarHandle varHandle;

        protected FieldLocation(int index, FieldInfo field) {
            super(index);
            this.field = Objects.requireNonNull(field);
            this.varHandle = ObjectStorageOptions.UseVarHandle ? Objects.requireNonNull(field.varHandle()) : null;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = super.hashCode();
            result = prime * result + index;
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (!super.equals(obj)) {
                return false;
            }
            FieldLocation other = (FieldLocation) obj;
            if (index != other.index) {
                return false;
            }
            return true;
        }

        @Override
        public String getWhereString() {
            return "@" + index;
        }

        protected static DynamicObject receiverCast(DynamicObject store, Class<? extends DynamicObject> tclass) {
            try {
                return tclass.cast(Objects.requireNonNull(store));
            } catch (ClassCastException | NullPointerException ex) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw illegalReceiver(store, tclass);
            }
        }

        protected static IllegalArgumentException illegalReceiver(DynamicObject store, Class<? extends DynamicObject> declaringClass) {
            CompilerAsserts.neverPartOfCompilation();
            return new IllegalArgumentException("Invalid receiver type (expected " + declaringClass + ", was " + (store == null ? null : store.getClass()) + ")");
        }
    }

    static class ObjectArrayLocation extends ArrayLocation implements ObjectLocation {
        protected ObjectArrayLocation(int index) {
            super(index);
        }

        protected static final Object[] getArray(DynamicObject store) {
            return store.getObjectStore();
        }

        @Override
        public Object get(DynamicObject store, boolean guard) {
            return getArray(store)[index];
        }

        @Override
        public final void set(DynamicObject store, Object value, boolean guard, boolean init) {
            getArray(store)[index] = value;
        }

        @Override
        public boolean canStore(Object value) {
            return true;
        }

        @Override
        public Class<? extends Object> getType() {
            return Object.class;
        }

        @Override
        public final boolean isNonNull() {
            return false;
        }

        @Override
        protected void clear(DynamicObject store) {
            set(store, null, false, true);
        }

        @Override
        public int objectArrayCount() {
            return OBJECT_SLOT_SIZE;
        }

        @Override
        public final void accept(LocationVisitor locationVisitor) {
            locationVisitor.visitObjectArray(index, OBJECT_SLOT_SIZE);
        }
    }

    static final class SimpleObjectFieldLocation extends FieldLocation implements ObjectLocation {

        SimpleObjectFieldLocation(int index, FieldInfo field) {
            super(index, field);
        }

        @Override
        public Object get(DynamicObject store, boolean guard) {
            if (ObjectStorageOptions.UseVarHandle) {
                return varHandle.get(store);
            }
            return unsafeGetObject(receiverCast(store, field.getDeclaringClass()), field.offset());
        }

        @Override
        public void set(DynamicObject store, Object value, boolean guard, boolean init) {
            if (ObjectStorageOptions.UseVarHandle) {
                varHandle.set(store, value);
                return;
            }
            unsafePutObject(receiverCast(store, field.getDeclaringClass()), field.offset(), value);
        }

        @Override
        public boolean canStore(Object value) {
            return true;
        }

        @Override
        public Class<? extends Object> getType() {
            return Object.class;
        }

        @Override
        public boolean isNonNull() {
            return false;
        }

        @Override
        protected void clear(DynamicObject store) {
            set(store, null, false, true);
        }

        @Override
        public int objectFieldCount() {
            return OBJECT_SLOT_SIZE;
        }

        @Override
        public void accept(LocationVisitor locationVisitor) {
            locationVisitor.visitObjectField(getIndex(), OBJECT_SLOT_SIZE);
        }
    }

    static class LongArrayLocation extends ArrayLocation implements LongLocation {
        private static final int ALIGN = LONG_ARRAY_SLOT_SIZE - 1;

        protected final boolean allowInt;

        protected LongArrayLocation(int index, boolean allowInt) {
            super(index);
            this.allowInt = allowInt;
        }

        protected LongArrayLocation(int index) {
            this(index, false);
        }

        @Override
        public final Object get(DynamicObject store, boolean guard) {
            return getLong(store, guard);
        }

        @Override
        public final void set(DynamicObject store, Object value, boolean guard, boolean init) throws com.oracle.truffle.api.object.IncompatibleLocationException {
            if (canStore(value)) {
                setLong(store, longValue(value), guard, init);
            } else {
                throw incompatibleLocation();
            }
        }

        private long longValue(Object value) {
            if (!allowInt || value instanceof Long) {
                return ((Long) value).longValue();
            } else {
                return ((Integer) value).longValue();
            }
        }

        protected static final int[] getArray(DynamicObject store) {
            return store.getPrimitiveStore();
        }

        @Override
        public long getLong(DynamicObject store, boolean guard) {
            int[] array = getArray(store);
            int idx = index;
            boolean boundsCheck = idx >= 0 && idx < array.length - ALIGN;
            if (boundsCheck) {
                long offset = ARRAY_INT_BASE_OFFSET + ARRAY_INT_INDEX_SCALE * idx;
                return unsafeGetLong(array, offset, boundsCheck, null);
            } else {
                throw arrayIndexOutOfBounds(idx);
            }
        }

        public final void setLongInternal(DynamicObject store, long value) {
            int[] array = getArray(store);
            int idx = index;
            if (idx >= 0 && idx < array.length - ALIGN) {
                long offset = ARRAY_INT_BASE_OFFSET + ARRAY_INT_INDEX_SCALE * idx;
                unsafePutLong(array, offset, value, null);
            } else {
                throw arrayIndexOutOfBounds(idx);
            }
        }

        private static ArrayIndexOutOfBoundsException arrayIndexOutOfBounds(int idx) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new ArrayIndexOutOfBoundsException(idx);
        }

        @Override
        public void setLong(DynamicObject store, long value, boolean guard, boolean init) {
            setLongInternal(store, value);
        }

        @Override
        public final boolean canStore(Object value) {
            return value instanceof Long || (allowInt && value instanceof Integer);
        }

        @Override
        public final Class<Long> getType() {
            return long.class;
        }

        @Override
        public int primitiveArrayCount() {
            return LONG_ARRAY_SLOT_SIZE;
        }

        @Override
        public final void accept(LocationVisitor locationVisitor) {
            locationVisitor.visitPrimitiveArray(getIndex(), LONG_ARRAY_SLOT_SIZE);
        }

        @Override
        public boolean equals(Object obj) {
            return super.equals(obj) && this.allowInt == ((LongArrayLocation) obj).allowInt;
        }

        @Override
        public boolean isImplicitCastIntToLong() {
            return allowInt;
        }
    }

    static LongLocation createLongLocation(LongLocation longLocation, boolean allowInt) {
        if ((!allowInt && (longLocation instanceof LongLocationDecorator)) || (longLocation instanceof LongLocationDecorator && ((LongLocationDecorator) longLocation).allowInt == allowInt)) {
            return longLocation;
        } else {
            return new LongLocationDecorator(longLocation, allowInt);
        }
    }

    static class LongLocationDecorator extends PrimitiveLocationDecorator implements LongLocation {
        protected final boolean allowInt;

        protected LongLocationDecorator(LongLocation longLocation, boolean allowInt) {
            super(longLocation);
            this.allowInt = allowInt;
        }

        @Override
        public final Object get(DynamicObject store, boolean guard) {
            return getLong(store, guard);
        }

        @Override
        public long getLong(DynamicObject store, boolean guard) {
            return super.getLongInternal(store, guard);
        }

        @Override
        public final void set(DynamicObject store, Object value, boolean guard, boolean init) throws com.oracle.truffle.api.object.IncompatibleLocationException {
            if (canStore(value)) {
                setLong(store, longValue(value), guard, init);
            } else {
                throw incompatibleLocation();
            }
        }

        @Override
        public void setLong(DynamicObject store, long value, boolean guard, boolean init) {
            super.setLongInternal(store, value, guard);
        }

        private long longValue(Object value) {
            if (!allowInt || value instanceof Long) {
                return ((Long) value).longValue();
            } else {
                return ((Integer) value).longValue();
            }
        }

        @Override
        public final boolean canStore(Object value) {
            return value instanceof Long || (allowInt && value instanceof Integer);
        }

        @Override
        public Class<Long> getType() {
            return long.class;
        }

        @Override
        public boolean equals(Object obj) {
            return super.equals(obj) && this.allowInt == ((LongLocationDecorator) obj).allowInt;
        }

        @Override
        public boolean isImplicitCastIntToLong() {
            return allowInt;
        }
    }

    static final class SimpleLongFieldLocation extends FieldLocation implements LongLocation {

        SimpleLongFieldLocation(int index, FieldInfo field) {
            super(index, field);
        }

        @Override
        public Object get(DynamicObject store, boolean guard) {
            return getLong(store, guard);
        }

        @Override
        public void set(DynamicObject store, Object value, boolean guard, boolean init) throws com.oracle.truffle.api.object.IncompatibleLocationException {
            if (canStore(value)) {
                setLong(store, ((Long) value).longValue(), guard, init);
            } else {
                throw incompatibleLocation();
            }
        }

        @Override
        public boolean canStore(Object value) {
            return value instanceof Long;
        }

        @Override
        public long getLong(DynamicObject store, boolean guard) {
            if (ObjectStorageOptions.UseVarHandle) {
                return (long) varHandle.get(store);
            }
            return unsafeGetLong(receiverCast(store, field.getDeclaringClass()), field.offset());
        }

        @Override
        public long getLong(DynamicObject store, Shape shape) {
            return getLong(store, checkShape(store, shape));
        }

        @Override
        public void setLong(DynamicObject store, long value, boolean guard, boolean init) {
            if (ObjectStorageOptions.UseVarHandle) {
                varHandle.set(store, value);
                return;
            }
            unsafePutLong(receiverCast(store, field.getDeclaringClass()), field.offset(), value);
        }

        @Override
        public int primitiveFieldCount() {
            return LONG_FIELD_SLOT_SIZE;
        }

        @Override
        public Class<Long> getType() {
            return long.class;
        }

        @Override
        public void accept(LocationVisitor locationVisitor) {
            locationVisitor.visitPrimitiveField(getIndex(), LONG_FIELD_SLOT_SIZE);
        }

        @Override
        public boolean isImplicitCastIntToLong() {
            return false;
        }
    }

    public abstract static class PrimitiveLocationDecorator extends CoreLocation {
        private final LongLocation longLocation;

        protected PrimitiveLocationDecorator(LongLocation longLocation) {
            this.longLocation = longLocation;
        }

        public final long getLongInternal(DynamicObject store, boolean guard) {
            return longLocation.getLong(store, guard);
        }

        public final void setLongInternal(DynamicObject store, long value, boolean guard) {
            longLocation.setLong(store, value, guard, true);
        }

        public final LongLocation getInternalLongLocation() {
            return longLocation;
        }

        @Override
        protected final LocationImpl getInternalLocation() {
            return (LocationImpl) longLocation;
        }

        @Override
        public final int primitiveFieldCount() {
            return ((LocationImpl) longLocation).primitiveFieldCount();
        }

        @Override
        public final int primitiveArrayCount() {
            return ((LocationImpl) longLocation).primitiveArrayCount();
        }

        @Override
        public final void accept(LocationVisitor locationVisitor) {
            ((LocationImpl) longLocation).accept(locationVisitor);
        }

        @Override
        public String getWhereString() {
            return ((LocationImpl) longLocation).getWhereString();
        }

        @Override
        public boolean equals(Object obj) {
            return super.equals(obj) && this.longLocation.equals(((PrimitiveLocationDecorator) obj).longLocation);
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = super.hashCode();
            result = prime * result + longLocation.hashCode();
            return result;
        }
    }

    static class IntLocationDecorator extends PrimitiveLocationDecorator implements IntLocation {
        protected IntLocationDecorator(LongLocation longLocation) {
            super(longLocation);
        }

        @Override
        public final Object get(DynamicObject store, boolean guard) {
            return getInt(store, guard);
        }

        @Override
        public int getInt(DynamicObject store, boolean guard) {
            return (int) getLongInternal(store, guard);
        }

        @Override
        public void setInt(DynamicObject store, int value, boolean guard, boolean init) {
            setLongInternal(store, value, guard);
        }

        @Override
        public final void set(DynamicObject store, Object value, boolean guard, boolean init) throws com.oracle.truffle.api.object.IncompatibleLocationException {
            if (canStore(value)) {
                setLongInternal(store, (int) value, guard);
            } else {
                throw incompatibleLocation();
            }
        }

        @Override
        public final int getInt(DynamicObject store, Shape shape) {
            return getInt(store, checkShape(store, shape));
        }

        @Override
        public final boolean canStore(Object value) {
            return value instanceof Integer;
        }

        @Override
        public Class<Integer> getType() {
            return int.class;
        }
    }

    static class DoubleLocationDecorator extends PrimitiveLocationDecorator implements DoubleLocation {
        private final boolean allowInt;

        protected DoubleLocationDecorator(LongLocation longLocation, boolean allowInt) {
            super(longLocation);
            this.allowInt = allowInt;
        }

        @Override
        public final Object get(DynamicObject store, boolean guard) {
            return getDouble(store, guard);
        }

        @Override
        public double getDouble(DynamicObject store, boolean guard) {
            return Double.longBitsToDouble(getLongInternal(store, guard));
        }

        @Override
        public void setDouble(DynamicObject store, double value, boolean guard, boolean init) {
            setLongInternal(store, Double.doubleToRawLongBits(value), guard);
        }

        @Override
        public final void set(DynamicObject store, Object value, boolean guard, boolean init) throws com.oracle.truffle.api.object.IncompatibleLocationException {
            if (canStore(value)) {
                setDouble(store, doubleValue(value), guard, init);
            } else {
                throw incompatibleLocation();
            }
        }

        private double doubleValue(Object value) {
            if (!allowInt || value instanceof Double) {
                return ((Double) value).doubleValue();
            } else {
                return ((Integer) value).doubleValue();
            }
        }

        @Override
        public final double getDouble(DynamicObject store, Shape shape) {
            return getDouble(store, checkShape(store, shape));
        }

        @Override
        public final boolean canStore(Object value) {
            return value instanceof Double || (allowInt && value instanceof Integer);
        }

        @Override
        public Class<Double> getType() {
            return double.class;
        }

        @Override
        public boolean equals(Object obj) {
            return super.equals(obj) && this.allowInt == ((DoubleLocationDecorator) obj).allowInt;
        }

        @Override
        public boolean isImplicitCastIntToDouble() {
            return allowInt;
        }
    }

    static class BooleanLocationDecorator extends PrimitiveLocationDecorator implements BooleanLocation {
        protected BooleanLocationDecorator(LongLocation longLocation) {
            super(longLocation);
        }

        @Override
        public final Object get(DynamicObject store, boolean guard) {
            return getBoolean(store, guard);
        }

        @Override
        public boolean getBoolean(DynamicObject store, boolean guard) {
            return getLongInternal(store, guard) != 0;
        }

        @Override
        public void setBoolean(DynamicObject store, boolean value, boolean guard, boolean init) {
            setLongInternal(store, value ? 1 : 0, guard);
        }

        @Override
        public final void set(DynamicObject store, Object value, boolean guard, boolean init) throws com.oracle.truffle.api.object.IncompatibleLocationException {
            if (canStore(value)) {
                setBoolean(store, (boolean) value, guard, init);
            } else {
                throw incompatibleLocation();
            }
        }

        @Override
        public final boolean getBoolean(DynamicObject store, Shape shape) {
            return getBoolean(store, checkShape(store, shape));
        }

        @Override
        public final boolean canStore(Object value) {
            return value instanceof Boolean;
        }

        @Override
        public Class<Boolean> getType() {
            return boolean.class;
        }
    }

    static long decodeLong(int lower, int upper) {
        return (lower & 0xffff_ffffL) | ((long) upper << 32);
    }

    static int lowerInt(long value) {
        return (int) value;
    }

    static int upperInt(long value) {
        return (int) (value >>> 32);
    }

    static int getLocationOrdinal(CoreLocation loc) {
        LocationImpl internal = loc.getInternalLocation();
        boolean isPrimitive = internal instanceof CoreLocations.LongLocation;
        if (internal instanceof CoreLocations.FieldLocation) {
            return (isPrimitive ? -Integer.MAX_VALUE : 0) + ((CoreLocations.FieldLocation) internal).getIndex();
        } else if (internal instanceof CoreLocations.ArrayLocation) {
            return (isPrimitive ? -Integer.MAX_VALUE : 0) + MAX_DYNAMIC_FIELDS + ((CoreLocations.ArrayLocation) internal).getIndex();
        } else {
            throw new IllegalArgumentException(internal.getClass().getName());
        }
    }
}
