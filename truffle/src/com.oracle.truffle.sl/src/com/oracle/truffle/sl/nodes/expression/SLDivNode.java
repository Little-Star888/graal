/*
 * Copyright (c) 2012, 2024, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.sl.nodes.expression;

import static com.oracle.truffle.api.CompilerDirectives.shouldNotReachHere;

import java.math.BigInteger;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.HostCompilerDirectives;
import com.oracle.truffle.api.bytecode.OperationProxy;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.sl.SLException;
import com.oracle.truffle.sl.nodes.SLBinaryNode;
import com.oracle.truffle.sl.runtime.SLBigInteger;

/**
 * This class is similar to the extensively documented {@link SLAddNode}. Divisions by 0 throw the
 * same {@link ArithmeticException exception} as in Java, SL has no special handling for it to keep
 * the code simple.
 */
@NodeInfo(shortName = "/")
@OperationProxy.Proxyable(allowUncached = true)
public abstract class SLDivNode extends SLBinaryNode {

    @Specialization(rewriteOn = ArithmeticException.class)
    public static long doLong(long left, long right) throws ArithmeticException {
        long result = left / right;
        /*
         * The division overflows if left is Long.MIN_VALUE and right is -1.
         */
        if ((left & right & result) < 0) {
            throw new ArithmeticException("long overflow");
        }
        return result;
    }

    @Specialization(replaces = "doLong")
    public static SLBigInteger doSLBigInteger(SLBigInteger left, SLBigInteger right) {
        BigInteger castLeft = left.getValue();
        BigInteger castRight = right.getValue();
        BigInteger result = divideBoundary(castLeft, castRight);
        return new SLBigInteger(result);
    }

    @TruffleBoundary(allowInlining = true)
    private static BigInteger divideBoundary(BigInteger castLeft, BigInteger castRight) {
        return castLeft.divide(castRight);
    }

    @Fallback
    @HostCompilerDirectives.InliningCutoff
    public static Object doFallback(Object left, Object right,
                    @Cached SlowPathNode fallback,
                    @Bind Node node) {
        return fallback.execute(node, left, right);
    }

    @GenerateInline(false)
    @GenerateUncached
    public abstract static class SlowPathNode extends Node {

        abstract Object execute(Node node, Object left, Object right);

        @Specialization(guards = {"leftLibrary.fitsInBigInteger(left)", "rightLibrary.fitsInBigInteger(right)"}, limit = "3")
        @SuppressWarnings("unused")
        static SLBigInteger doInteropBigInteger(Node node, Object left, Object right,
                        @CachedLibrary("left") InteropLibrary leftLibrary,
                        @CachedLibrary("right") InteropLibrary rightLibrary) {
            try {
                BigInteger castLeft = leftLibrary.asBigInteger(left);
                BigInteger castRight = rightLibrary.asBigInteger(right);
                BigInteger result = divideBoundary(castLeft, castRight);
                return new SLBigInteger(result);
            } catch (UnsupportedMessageException e) {
                throw shouldNotReachHere(e);
            }
        }

        @Fallback
        static Object typeError(Node node, Object left, Object right) {
            throw SLException.typeError(node, "/", left, right);
        }
    }

}
