/*
 * Copyright (c) 2013, 2022, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.truffle;

import java.lang.reflect.Method;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.graalvm.nativeimage.ImageSingletons;

import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.SubstrateUtil;
import com.oracle.svm.core.deopt.DeoptimizationRuntime;
import com.oracle.svm.core.graal.code.SubstrateBackend;
import com.oracle.svm.core.meta.SubstrateObjectConstant;
import com.oracle.svm.graal.TruffleRuntimeCompilationSupport;
import com.oracle.svm.graal.hosted.runtimecompilation.RuntimeCompilationFeature;
import com.oracle.svm.truffle.api.SubstrateKnownTruffleTypes;
import com.oracle.svm.truffle.api.SubstrateOptimizedCallTarget;
import com.oracle.svm.truffle.api.SubstrateOptimizedCallTargetInstalledCode;
import com.oracle.svm.truffle.api.SubstratePartialEvaluator;
import com.oracle.svm.truffle.api.SubstrateTruffleCompiler;
import com.oracle.svm.truffle.api.SubstrateTruffleCompilerImpl;
import com.oracle.svm.truffle.api.SubstrateTruffleRuntime;
import com.oracle.svm.truffle.isolated.IsolateAwareTruffleCompiler;
import com.oracle.svm.truffle.isolated.IsolatedTruffleRuntimeSupport;
import com.oracle.svm.util.ReflectionUtil;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.utilities.TriState;
import com.oracle.truffle.compiler.OptimizedAssumptionDependency;
import com.oracle.truffle.compiler.TruffleCompilable;
import com.oracle.truffle.runtime.BackgroundCompileQueue;
import com.oracle.truffle.runtime.EngineData;
import com.oracle.truffle.runtime.OptimizedAssumption;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import com.oracle.truffle.runtime.OptimizedDirectCallNode;

import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderConfiguration;
import jdk.graal.compiler.phases.util.Providers;
import jdk.graal.compiler.truffle.EconomyPartialEvaluatorConfiguration;
import jdk.graal.compiler.truffle.PartialEvaluatorConfiguration;
import jdk.graal.compiler.truffle.TruffleCompilerConfiguration;
import jdk.graal.compiler.truffle.TruffleCompilerImpl;
import jdk.graal.compiler.truffle.TruffleTierConfiguration;
import jdk.vm.ci.meta.JavaConstant;

public class TruffleSupport {

    public static TruffleSupport singleton() {
        return ImageSingletons.lookup(TruffleSupport.class);
    }

    /**
     * @return the concrete subclass of {@link OptimizedCallTarget}.
     */
    public Class<?> getOptimizedCallTargetClass() {
        return SubstrateOptimizedCallTarget.class;
    }

    public SubstrateOptimizedCallTarget createOptimizedCallTarget(OptimizedCallTarget sourceCallTarget, RootNode rootNode) {
        return new SubstrateOptimizedCallTarget(sourceCallTarget, rootNode);
    }

    public SubstrateOptimizedCallTarget createOptimizedCallTarget(EngineData engine) {
        return new SubstrateOptimizedCallTarget(engine);
    }

    private static Method getOptimizedCallTargetInvokeMethod() {
        return ReflectionUtil.lookupMethod(SubstrateOptimizedCallTargetInstalledCode.class, "doInvoke", SubstrateOptimizedCallTarget.class, Object[].class);
    }

    public SubstratePartialEvaluator createPartialEvaluator(TruffleCompilerConfiguration config, GraphBuilderConfiguration graphBuilderConfigForRoot) {
        return new SubstratePartialEvaluator(config, graphBuilderConfigForRoot);
    }

    public SubstrateTruffleCompiler createTruffleCompiler(SubstrateTruffleRuntime runtime) {
        SubstrateTruffleCompiler compiler = new SubstrateTruffleCompilerImpl(createSubstrateTruffleCompilerConfig(runtime, "community", getOptimizedCallTargetInvokeMethod()));
        if (SubstrateOptions.supportCompileInIsolates()) {
            compiler = new IsolateAwareTruffleCompiler((SubstrateTruffleCompilerImpl) compiler);
        }
        return compiler;
    }

    protected static TruffleCompilerConfiguration createSubstrateTruffleCompilerConfig(SubstrateTruffleRuntime runtime, String compilerConfigurationName, Method optimizedCallTargetMethod) {
        RuntimeCompilationFeature runtimeCompilationFeature = RuntimeCompilationFeature.singleton();
        final GraphBuilderConfiguration.Plugins graphBuilderPlugins = runtimeCompilationFeature.getHostedProviders().getGraphBuilderPlugins();
        SubstrateBackend substrateBackend = TruffleRuntimeCompilationSupport.getRuntimeConfig().getBackendForNormalMethod();
        substrateBackend.setRuntimeToRuntimeInvokeMethod(optimizedCallTargetMethod);
        Providers providers = TruffleRuntimeCompilationSupport.getRuntimeConfig().getProviders();
        SubstrateKnownTruffleTypes types = new SubstrateKnownTruffleTypes(runtime, providers.getMetaAccess(), providers.getConstantReflection());
        final TruffleTierConfiguration firstTier = new TruffleTierConfiguration(new EconomyPartialEvaluatorConfiguration(), substrateBackend,
                        TruffleRuntimeCompilationSupport.getFirstTierProviders(), TruffleRuntimeCompilationSupport.getFirstTierSuites(), TruffleRuntimeCompilationSupport.getFirstTierLirSuites(),
                        types, DeoptimizationRuntime.DEOPTIMIZE);

        PartialEvaluatorConfiguration peConfig = TruffleCompilerImpl.createPartialEvaluatorConfiguration(compilerConfigurationName);
        final TruffleTierConfiguration lastTier = new TruffleTierConfiguration(peConfig, substrateBackend,
                        TruffleRuntimeCompilationSupport.getRuntimeConfig().getProviders(), TruffleRuntimeCompilationSupport.getFullOptSuites(), TruffleRuntimeCompilationSupport.getLIRSuites(),
                        types, DeoptimizationRuntime.DEOPTIMIZE);
        return new TruffleCompilerConfiguration(runtime, graphBuilderPlugins, runtimeCompilationFeature.getHostedProviders().getSnippetReflection(), firstTier, lastTier, types,
                        TruffleRuntimeCompilationSupport.getFullOptSuites(), null);
    }

    public static boolean isIsolatedCompilation() {
        return !SubstrateUtil.HOSTED && SubstrateOptions.shouldCompileInIsolates();
    }

    public Consumer<OptimizedAssumptionDependency> registerOptimizedAssumptionDependency(JavaConstant optimizedAssumptionConstant) {
        if (isIsolatedCompilation()) {
            return IsolatedTruffleRuntimeSupport.registerOptimizedAssumptionDependency(optimizedAssumptionConstant);
        }
        Object target = SubstrateObjectConstant.asObject(optimizedAssumptionConstant);
        OptimizedAssumption assumption = (OptimizedAssumption) target;
        return assumption.registerDependency();
    }

    public JavaConstant getCallTargetForCallNode(JavaConstant callNodeConstant) {
        if (isIsolatedCompilation()) {
            return IsolatedTruffleRuntimeSupport.getCallTargetForCallNode(callNodeConstant);
        }
        Object target = SubstrateObjectConstant.asObject(callNodeConstant);
        OptimizedDirectCallNode callNode = (OptimizedDirectCallNode) target;
        OptimizedCallTarget callTarget = callNode.getCallTarget();
        return SubstrateObjectConstant.forObject(callTarget);
    }

    public BackgroundCompileQueue createBackgroundCompileQueue(@SuppressWarnings("unused") SubstrateTruffleRuntime runtime) {
        return new BackgroundCompileQueue(runtime);
    }

    public TruffleCompilable asCompilableTruffleAST(JavaConstant constant) {
        if (isIsolatedCompilation()) {
            return IsolatedTruffleRuntimeSupport.asCompilableTruffleAST(constant);
        }
        return SubstrateObjectConstant.asObject(OptimizedCallTarget.class, constant);
    }

    @SuppressWarnings("unused")
    public boolean tryLog(SubstrateTruffleRuntime runtime, String loggerId, TruffleCompilable compilable, String message) {
        if (isIsolatedCompilation()) {
            return IsolatedTruffleRuntimeSupport.tryLog(loggerId, compilable, message);
        }
        return false;
    }

    public TriState tryIsSuppressedFailure(TruffleCompilable compilable, Supplier<String> serializedException) {
        if (isIsolatedCompilation()) {
            return IsolatedTruffleRuntimeSupport.tryIsSuppressedFailure(compilable, serializedException);
        }
        return TriState.UNDEFINED;
    }

}
