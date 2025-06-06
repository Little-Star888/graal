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
package org.graalvm.wasm;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.graalvm.wasm.memory.WasmMemory;
import org.graalvm.wasm.memory.WasmMemoryLibrary;

public class MemoryContext {

    private final ReferenceQueue<WasmMemory> cleanerRefQueue = new ReferenceQueue<>();
    /**
     * Phantom cleanables are used to free unreachable memories.
     */
    private final Set<Cleanable> phantomCleanableSet = ConcurrentHashMap.newKeySet();
    /**
     * Weak cleanables are used to free any remaining strong memories on context close.
     */
    private final Set<Cleanable> weakCleanableSet = ConcurrentHashMap.newKeySet();

    public MemoryContext() {
    }

    public void close() {
        // explicitly close all still reachable memories
        weakCleanableSet.forEach(Cleanable::clean);
        // free any leftover memories and clear reference queue
        phantomCleanableSet.forEach(Cleanable::clean);
        processCleanerQueue();
    }

    public void registerCleaner(WasmMemory referent, Runnable cleanup) {
        processCleanerQueue();
        phantomCleanableSet.add(new PhantomCleanableRef(referent, cleanerRefQueue, cleanup, phantomCleanableSet));
        weakCleanableSet.add(new WeakCleanableRef(referent, cleanerRefQueue, weakCleanableSet));
    }

    private void processCleanerQueue() {
        Cleanable ref;
        while ((ref = (Cleanable) cleanerRefQueue.poll()) != null) {
            ref.clean();
        }
    }

    private interface Cleanable {
        void clean();
    }

    private static final class PhantomCleanableRef extends PhantomReference<WasmMemory> implements Cleanable {
        private final Runnable action;
        private final Set<Cleanable> cleanableRefs;

        PhantomCleanableRef(WasmMemory referent, ReferenceQueue<? super WasmMemory> queue, Runnable action, Set<Cleanable> cleanableRefs) {
            super(referent, queue);
            this.action = action;
            this.cleanableRefs = cleanableRefs;
        }

        @Override
        public void clean() {
            super.clear();
            cleanableRefs.remove(this);
            action.run();
        }
    }

    private static final class WeakCleanableRef extends WeakReference<WasmMemory> implements Cleanable {
        private final Set<Cleanable> cleanableRefs;

        WeakCleanableRef(WasmMemory referent, ReferenceQueue<? super WasmMemory> queue, Set<Cleanable> cleanableRefs) {
            super(referent, queue);
            this.cleanableRefs = cleanableRefs;
        }

        @Override
        public void clean() {
            WasmMemory wasmMemory = get();
            super.clear();
            if (wasmMemory != null) {
                WasmMemoryLibrary.getUncached().close(wasmMemory);
            }
            cleanableRefs.remove(this);
        }
    }
}
