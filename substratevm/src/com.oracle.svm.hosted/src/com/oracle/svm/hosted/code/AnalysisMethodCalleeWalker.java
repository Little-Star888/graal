/*
 * Copyright (c) 2017, 2022, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.hosted.code;

import org.graalvm.collections.EconomicSet;

import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.InvokeInfo;
import com.oracle.svm.core.heap.RestrictHeapAccess;
import com.oracle.svm.hosted.code.AnalysisMethodCalleeWalker.CallPathVisitor.VisitResult;

import jdk.vm.ci.code.BytecodePosition;

/**
 * Gather a list of the transitive blacklisted callees from methods annotated with
 * {@link RestrictHeapAccess} that allocate.
 */
public class AnalysisMethodCalleeWalker {

    /** A stack of methods that are currently being examined, to detect cycles in the call graph. */
    private final EconomicSet<AnalysisMethod> path;

    public AnalysisMethodCalleeWalker() {
        path = EconomicSet.create();
    }

    /**
     * Walk a method by applying a visitor to the method and all of its callees. Returns true if all
     * the visits returned true, else returns false.
     */
    @SuppressWarnings("try")
    public boolean walkMethod(AnalysisMethod method, CallPathVisitor visitor) {
        if (visitor.prologue() != VisitResult.CONTINUE) {
            return false;
        }
        /* Initialize the path of callers. */
        path.clear();
        /* Walk the method and callees, but ignore the result. */
        walkMethodAndCallees(method, null, null, visitor);
        final VisitResult epilogueResult = visitor.epilogue();
        return (epilogueResult != VisitResult.CONTINUE);
    }

    VisitResult walkMethodAndCallees(AnalysisMethod method, AnalysisMethod caller, BytecodePosition invokePosition, CallPathVisitor visitor) {
        if (!path.add(method)) {
            /*
             * If the method is already on the path then I am in the middle of visiting it, so just
             * keep walking.
             */
            return VisitResult.CUT;
        }
        try {
            VisitResult directResult = visitor.visitMethod(method, caller, invokePosition, path.size());
            if (directResult != VisitResult.CONTINUE) {
                return directResult;
            }
            for (InvokeInfo invoke : method.getInvokes()) {
                for (AnalysisMethod target : invoke.getOriginalCallees()) {
                    walkMethodAndCallees(target, method, invoke.getPosition(), visitor);
                }
            }
            return VisitResult.CONTINUE;
        } finally {
            path.remove(method);
        }
    }

    /** A visitor for HostedMethods, with a caller path. */
    abstract static class CallPathVisitor {

        public enum VisitResult {
            CONTINUE,
            CUT,
            QUIT
        }

        /**
         * Called before any method is visited. Returns true if visiting should continue, else
         * false.
         */
        public VisitResult prologue() {
            return VisitResult.CONTINUE;
        }

        /**
         * Called for each method. Returns {@link VisitResult#CONTINUE} if the walk should continue,
         * {@link VisitResult#CUT} if the walk should cut at this method, or
         * {@link VisitResult#QUIT} if the walk should be stopped altogether.
         */
        public abstract VisitResult visitMethod(AnalysisMethod method, AnalysisMethod caller, BytecodePosition invokePosition, int depth);

        /**
         * Called after every method has been visited. Returns true if visiting should continue,
         * else false.
         */
        public VisitResult epilogue() {
            return VisitResult.CONTINUE;
        }
    }
}
