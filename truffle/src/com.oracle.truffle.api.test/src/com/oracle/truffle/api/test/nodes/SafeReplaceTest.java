/*
 * Copyright (c) 2015, 2022, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.api.test.nodes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.tck.tests.TruffleTestAssumptions;

/**
 * Tests optional method for ensuring that a node replacement is type safe. Ordinary node
 * replacement is performed by unsafe assignment of a parent node's child field.
 */
public class SafeReplaceTest {

    @BeforeClass
    public static void runWithWeakEncapsulationOnly() {
        TruffleTestAssumptions.assumeWeakEncapsulation();
    }

    @Test
    public void testCorrectReplacement() {
        TestRootNode root = new TestRootNode();
        final TestNode oldChild = new TestNode();
        final TestNode newChild = new TestNode();
        root.child = oldChild;
        assertFalse(oldChild.isSafelyReplaceableBy(newChild));  // No parent node
        root.adoptChildren();
        assertTrue(oldChild.isSafelyReplaceableBy(newChild));   // Now adopted by parent
        // new node
        oldChild.replace(newChild);
        root.execute(null);
        assertEquals(root.executed, 1);
        assertEquals(oldChild.executed, 0);
        assertEquals(newChild.executed, 1);
    }

    @Test
    public void testIncorrectReplacement() {
        TestRootNode root = new TestRootNode();
        final TestNode oldChild = new TestNode();
        root.child = oldChild;
        root.adoptChildren();
        final TestNode newChild = new TestNode();
        final TestNode strayChild = new TestNode();
        assertFalse(strayChild.isSafelyReplaceableBy(newChild)); // Stray not a child of parent
        final WrongTestNode wrongTypeNewChild = new WrongTestNode();
        assertFalse(oldChild.isSafelyReplaceableBy(wrongTypeNewChild));
    }

    private static final class TestNode extends Node {

        private int executed;

        public Object execute() {
            executed++;
            return null;
        }
    }

    private static class TestRootNode extends RootNode {

        @Child TestNode child;

        private int executed;

        TestRootNode() {
            super(null);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            executed++;
            child.execute();
            return null;
        }
    }

    private static final class WrongTestNode extends Node {
    }

}
