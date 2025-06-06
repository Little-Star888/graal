/*
 * Copyright (c) 2014, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.genscavenge;

import static com.oracle.svm.core.heap.ReferenceInternals.getReferentFieldAddress;
import static jdk.graal.compiler.nodes.extended.BranchProbabilityNode.SLOW_PATH_PROBABILITY;
import static jdk.graal.compiler.nodes.extended.BranchProbabilityNode.probability;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;

import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;

import com.oracle.svm.core.AlwaysInline;
import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.genscavenge.remset.RememberedSet;
import com.oracle.svm.core.heap.Heap;
import com.oracle.svm.core.heap.ObjectHeader;
import com.oracle.svm.core.heap.ObjectReferenceVisitor;
import com.oracle.svm.core.heap.ReferenceInternals;
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.snippets.KnownIntrinsics;
import com.oracle.svm.core.thread.VMOperation;
import com.oracle.svm.core.util.UnsignedUtils;

import jdk.graal.compiler.word.Word;

/** Discovers and handles {@link Reference} objects during garbage collection. */
final class ReferenceObjectProcessing {
    /** Head of the linked list of discovered references that need to be revisited. */
    private static Reference<?> rememberedRefsList;

    /**
     * For a {@link SoftReference}, the longest duration after its last access to keep its referent
     * alive. Determined at the end of a collection to be applied during the next collection.
     */
    private static UnsignedWord maxSoftRefAccessIntervalMs = UnsignedUtils.MAX_VALUE;

    /** Treat all soft references as weak, typically to reclaim space when low on memory. */
    private static boolean softReferencesAreWeak = false;

    /**
     * The first timestamp that was set as {@link SoftReference} clock, for examining references
     * that were created earlier than that.
     */
    private static long initialSoftRefClock = 0;

    private ReferenceObjectProcessing() { // all static
    }

    /*
     * Enables (or disables) reclaiming all objects that are softly reachable only, typically as a
     * last resort to avoid running out of memory.
     */
    public static void setSoftReferencesAreWeak(boolean enabled) {
        assert VMOperation.isGCInProgress();
        softReferencesAreWeak = enabled;
    }

    @AlwaysInline("GC performance")
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static void discoverIfReference(Object object, ObjectReferenceVisitor refVisitor) {
        assert object != null;
        DynamicHub hub = KnownIntrinsics.readHub(object);
        if (probability(SLOW_PATH_PROBABILITY, hub.isReferenceInstanceClass())) {
            discover(object, refVisitor);
        }
    }

    @AlwaysInline("GC performance")
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static void discover(Object obj, ObjectReferenceVisitor refVisitor) {
        Reference<?> dr = (Reference<?>) obj;
        // The discovered field might contain an object with a forwarding header
        // to avoid issues during the cast just look at it as a raw pointer
        if (ReferenceInternals.getDiscoveredPointer(dr).isNonNull()) {
            // Was already discovered earlier.
            return;
        }
        Pointer referentAddr = ReferenceInternals.getReferentPointer(dr);
        if (referentAddr.isNull()) {
            /*
             * If the Reference has been allocated but not yet initialized (null referent), its
             * soon-to-be referent will still be strongly reachable from the call stack. If the
             * Reference is initialized but has a null referent, it has already been enqueued
             * (either manually or by the GC) and does not need to be discovered.
             */
            return;
        }
        if (Heap.getHeap().isInImageHeap(referentAddr)) {
            // Referents in the image heap cannot be moved or reclaimed, no need to look closer.
            return;
        }
        if (maybeUpdateForwardedReference(dr, referentAddr)) {
            // Some other object had a strong reference to the referent, so the referent was already
            // promoted. The call above updated the reference object so that it now points to the
            // promoted object.
            return;
        }
        Object refObject = referentAddr.toObjectNonNull();
        if (willSurviveThisCollection(refObject)) {
            // Either an object that got promoted without being moved or an object in the old gen.
            RememberedSet.get().dirtyCardIfNecessary(dr, refObject, getReferentFieldAddress(dr));
            return;
        }
        if (!softReferencesAreWeak && dr instanceof SoftReference) {
            long clock = ReferenceInternals.getSoftReferenceClock();
            long timestamp = ReferenceInternals.getSoftReferenceTimestamp((SoftReference<?>) dr);
            if (timestamp == 0) { // created or last accessed before the clock was initialized
                timestamp = initialSoftRefClock;
            }
            UnsignedWord elapsed = Word.unsigned(clock - timestamp);
            if (elapsed.belowThan(maxSoftRefAccessIntervalMs)) {
                // Important: we need to pass the reference object as holder so that the remembered
                // set can be updated accordingly!
                int referenceSize = ConfigurationValues.getObjectLayout().getReferenceSize();
                refVisitor.visitObjectReferences(ReferenceInternals.getReferentFieldAddress(dr), true, referenceSize, dr, 1);
                return; // referent will survive
            }
        }

        // When we reach this point, then we don't know if the referent will survive or not. So,
        // lets add the reference to the list of remembered references. All remembered references
        // are revisited after the GC finished promoting all strongly reachable objects.

        // null link means undiscovered, avoid for the last node with a cyclic reference
        Reference<?> next = (rememberedRefsList != null) ? rememberedRefsList : dr;
        ReferenceInternals.setNextDiscovered(dr, next);
        rememberedRefsList = dr;
    }

    /**
     * Updates remembered references according to the liveness of the referent, dirtying cards, and
     * clears the discovered status of non-pending references.
     *
     * @return a list of those references which are pending to be added to a {@link ReferenceQueue}.
     */
    static Reference<?> processRememberedReferences() {
        Reference<?> pendingHead = null;
        Reference<?> current = rememberedRefsList;
        rememberedRefsList = null;

        while (current != null) {
            // Get the next node (the last node has a cyclic reference to self).
            Reference<?> next = ReferenceInternals.getNextDiscovered(current);
            assert next != null;
            next = (next != current) ? next : null;

            if (!processRememberedRef(current) && ReferenceInternals.hasQueue(current)) {
                // The referent is dead, so add it to the list of references that will be processed
                // by the reference handler.
                ReferenceInternals.setNextDiscovered(current, pendingHead);
                pendingHead = current;
            } else {
                // No need to enqueue this reference.
                ReferenceInternals.setNextDiscovered(current, null);
            }

            current = next;
        }

        return pendingHead;
    }

    static void afterCollection(UnsignedWord freeBytes) {
        assert rememberedRefsList == null;
        UnsignedWord unused = freeBytes.unsignedDivide(1024 * 1024 /* MB */);
        maxSoftRefAccessIntervalMs = unused.multiply(SerialGCOptions.SoftRefLRUPolicyMSPerMB.getValue());
        ReferenceInternals.updateSoftReferenceClock();
        if (initialSoftRefClock == 0) {
            initialSoftRefClock = ReferenceInternals.getSoftReferenceClock();
        }
    }

    /**
     * Determine if the referent is live, updating the reference field, and dirtying cards.
     *
     * @return true if the referent will survive the collection, false otherwise.
     */
    private static boolean processRememberedRef(Reference<?> dr) {
        Pointer refPointer = ReferenceInternals.getReferentPointer(dr);
        assert !HeapImpl.getHeapImpl().isInImageHeap(refPointer) : "Image heap referent: should not have been discovered";

        if (SerialGCOptions.useCompactingOldGen() && GCImpl.getGCImpl().isCompleteCollection()) {
            assert refPointer.isNull() || !ObjectHeaderImpl.isPointerToForwardedObject(refPointer);
            return refPointer.isNonNull();
        }

        assert refPointer.isNonNull() : "Referent is null: should not have been discovered";
        if (maybeUpdateForwardedReference(dr, refPointer)) {
            return true;
        }
        Object refObject = refPointer.toObjectNonNull();
        if (willSurviveThisCollection(refObject)) {
            RememberedSet.get().dirtyCardIfNecessary(dr, refObject, getReferentFieldAddress(dr));
            return true;
        }
        /*
         * Referent has not survived.
         *
         * Note that we must use the Object-level store here, not the Pointer-level store: the
         * static analysis must see that the field can be null. This means that we get a write
         * barrier for this store.
         */
        ReferenceInternals.setReferent(dr, null);
        return false;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static boolean maybeUpdateForwardedReference(Reference<?> dr, Pointer referentAddr) {
        ObjectHeaderImpl ohi = ObjectHeaderImpl.getObjectHeaderImpl();
        UnsignedWord header = ohi.readHeaderFromPointer(referentAddr);
        if (ObjectHeaderImpl.isForwardedHeader(header)) {
            Object forwardedObj = ohi.getForwardedObject(referentAddr);
            ReferenceInternals.setReferent(dr, forwardedObj);
            return true;
        }
        return false;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static boolean willSurviveThisCollection(Object obj) {
        if (SerialGCOptions.useCompactingOldGen() && GCImpl.getGCImpl().isCompleteCollection()) {
            return ObjectHeaderImpl.isMarked(obj);
        }
        HeapChunk.Header<?> chunk = HeapChunk.getEnclosingHeapChunk(obj);
        Space space = HeapChunk.getSpace(chunk);
        return space.isToSpace() || space.isCompactingOldSpace();
    }

    static void updateForwardedRefs() {
        assert SerialGCOptions.useCompactingOldGen();

        Reference<?> current = rememberedRefsList;
        while (current != null) {
            // Get the next node (the last node has a cyclic reference to self).
            Reference<?> next = ReferenceInternals.getNextDiscovered(current);
            assert next != null;
            next = (next != current) ? next : null;

            Pointer refPointer = ReferenceInternals.getReferentPointer(current);
            if (!maybeUpdateForwardedReference(current, refPointer)) {
                ObjectHeader oh = Heap.getHeap().getObjectHeader();
                UnsignedWord header = oh.readHeaderFromPointer(refPointer);
                if (!ObjectHeaderImpl.isMarkedHeader(header)) {
                    ReferenceInternals.setReferent(current, null);
                }
            }

            current = next;
        }
    }
}
