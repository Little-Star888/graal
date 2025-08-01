/*
 * Copyright (c) 2013, 2021, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.hotspot;

import static jdk.graal.compiler.core.common.LibGraalSupport.inLibGraalRuntime;
import static jdk.graal.compiler.core.common.NativeImageSupport.inBuildtimeCode;
import static jdk.graal.compiler.hotspot.EncodedSnippets.isAfterSnippetEncoding;
import static jdk.graal.compiler.hotspot.EncodedSnippets.isUsingEncodedSnippets;
import static jdk.graal.compiler.hotspot.replaycomp.ReplayCompilationSupport.isReplayingLibgraalInJargraal;

import java.util.BitSet;

import org.graalvm.collections.EconomicSet;

import jdk.graal.compiler.bytecode.Bytecode;
import jdk.graal.compiler.bytecode.BytecodeProvider;
import jdk.graal.compiler.bytecode.ResolvedJavaMethodBytecode;
import jdk.graal.compiler.core.common.LibGraalSupport;
import jdk.graal.compiler.debug.DebugCloseable;
import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.graal.compiler.hotspot.meta.HotSpotProviders;
import jdk.graal.compiler.hotspot.meta.HotSpotWordOperationPlugin;
import jdk.graal.compiler.hotspot.replaycomp.ReplayCompilationSupport;
import jdk.graal.compiler.hotspot.word.HotSpotOperation;
import jdk.graal.compiler.java.GraphBuilderPhase.Instance;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.nodes.LogicNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.StructuredGraph.AllowAssumptions;
import jdk.graal.compiler.nodes.extended.GuardingNode;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderConfiguration;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderPlugin;
import jdk.graal.compiler.nodes.graphbuilderconf.IntrinsicContext;
import jdk.graal.compiler.nodes.graphbuilderconf.InvocationPlugin;
import jdk.graal.compiler.nodes.spi.CoreProviders;
import jdk.graal.compiler.nodes.spi.SnippetParameterInfo;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.OptimisticOptimizations;
import jdk.graal.compiler.phases.util.Providers;
import jdk.graal.compiler.printer.GraalDebugHandlersFactory;
import jdk.graal.compiler.replacements.IntrinsicGraphBuilder;
import jdk.graal.compiler.replacements.ReplacementsImpl;
import jdk.graal.compiler.replacements.SnippetTemplate;
import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * Filters certain method substitutions based on whether there is underlying hardware support for
 * them.
 */
public class HotSpotReplacementsImpl extends ReplacementsImpl {
    public HotSpotReplacementsImpl(HotSpotProviders providers, BytecodeProvider bytecodeProvider, TargetDescription target) {
        super(new GraalDebugHandlersFactory(providers.getSnippetReflection()), providers, bytecodeProvider, target);
        if (!inLibGraalRuntime()) {
            registeredSnippets = EconomicSet.create();
        }
    }

    HotSpotReplacementsImpl(HotSpotReplacementsImpl replacements, HotSpotProviders providers) {
        super(new GraalDebugHandlersFactory(replacements.getProviders().getSnippetReflection()), providers,
                        replacements.getDefaultReplacementBytecodeProvider(), replacements.target);
        if (!inLibGraalRuntime()) {
            registeredSnippets = EconomicSet.create();
        }
    }

    @Override
    public HotSpotProviders getProviders() {
        return (HotSpotProviders) super.getProviders();
    }

    @LibGraalSupport.HostedOnly
    public static void clearSnippetEncoder() {
        synchronized (HotSpotReplacementsImpl.class) {
            snippetEncoder = null;
        }
    }

    @LibGraalSupport.HostedOnly
    public SymbolicSnippetEncoder maybeInitializeEncoder() {
        synchronized (HotSpotReplacementsImpl.class) {
            if (snippetEncoder == null) {
                snippetEncoder = new SymbolicSnippetEncoder(this);
            }
        }
        return snippetEncoder;
    }

    @Override
    public Class<? extends GraphBuilderPlugin> getIntrinsifyingPlugin(ResolvedJavaMethod method) {
        if (!inLibGraalRuntime()) {
            if (method.getAnnotation(HotSpotOperation.class) != null) {
                return HotSpotWordOperationPlugin.class;
            }
        }
        return super.getIntrinsifyingPlugin(method);
    }

    @Override
    public void notifyNotInlined(GraphBuilderContext b, ResolvedJavaMethod method, Invoke invoke) {
        if (!inLibGraalRuntime()) {
            if (b.parsingIntrinsic() && snippetEncoder != null) {
                if (getIntrinsifyingPlugin(method) != null) {
                    snippetEncoder.addDelayedInvocationPluginMethod(method);
                    return;
                }
            }
        }
        super.notifyNotInlined(b, method, invoke);
    }

    public static class HotSpotIntrinsicGraphBuilder extends IntrinsicGraphBuilder {

        private final boolean isInOOMETry;

        public HotSpotIntrinsicGraphBuilder(OptionValues options, DebugContext debug, CoreProviders providers, Bytecode code, int invokeBci, AllowAssumptions allowAssumptions, boolean isInOOMETry) {
            super(options, debug, providers, code, invokeBci, allowAssumptions);
            this.isInOOMETry = isInOOMETry;
        }

        public HotSpotIntrinsicGraphBuilder(OptionValues options, DebugContext debug, CoreProviders providers, Bytecode code, int invokeBci, AllowAssumptions allowAssumptions,
                        GraphBuilderConfiguration graphBuilderConfig) {
            super(options, debug, providers, code, invokeBci, allowAssumptions, graphBuilderConfig);
            this.isInOOMETry = false;
        }

        @Override
        public GuardingNode intrinsicRangeCheck(LogicNode condition, boolean negated) {
            return HotSpotBytecodeParser.doIntrinsicRangeCheck(this, condition, negated);
        }

        @Override
        public boolean currentBlockCatchesOOME() {
            return isInOOMETry;
        }
    }

    @Override
    public boolean hasSubstitution(ResolvedJavaMethod method, OptionValues options) {
        InvocationPlugin plugin = graphBuilderPlugins.getInvocationPlugins().lookupInvocation(method, options);
        return plugin != null;
    }

    @Override
    public StructuredGraph getInlineSubstitution(ResolvedJavaMethod method, int invokeBci, boolean isInOOMETry, Invoke.InlineControl inlineControl, boolean trackNodeSourcePosition,
                    NodeSourcePosition replaceePosition, AllowAssumptions allowAssumptions, OptionValues options) {
        assert invokeBci >= 0 : method;
        if (!inlineControl.allowSubstitution()) {
            return null;
        }
        StructuredGraph result;
        InvocationPlugin plugin = graphBuilderPlugins.getInvocationPlugins().lookupInvocation(method, options);
        if (plugin != null) {
            Bytecode code = new ResolvedJavaMethodBytecode(method);
            try (DebugContext debug = openSnippetDebugContext("Substitution_", method, options)) {
                result = new HotSpotIntrinsicGraphBuilder(options, debug, providers, code, invokeBci, allowAssumptions, isInOOMETry).buildGraph(plugin);
            }
        } else {
            result = null;
        }
        return result;
    }

    // When assertions are enabled, these fields are used to ensure all snippets are
    // registered during Graal initialization which in turn ensures that native image
    // building will not miss any snippets.
    @LibGraalSupport.HostedOnly//
    private EconomicSet<ResolvedJavaMethod> registeredSnippets;
    @LibGraalSupport.HostedOnly//
    private boolean snippetRegistrationClosed;

    @SuppressWarnings("try")
    @Override
    public void registerSnippet(ResolvedJavaMethod method, ResolvedJavaMethod original, Object receiver, boolean trackNodeSourcePosition, OptionValues options) {
        assert method.isStatic() || receiver != null : "must have a constant type for the receiver";
        if (isRegisteringSnippetMethods()) {
            assert !snippetRegistrationClosed || System.getProperty("GraalUnitTest") != null : "Cannot register snippet after registration is closed: " + method.format("%H.%n(%p)");
            if (registeredSnippets.add(method)) {
                snippetEncoder.registerSnippet(method, original, receiver, trackNodeSourcePosition);
                try (DebugCloseable ignored = ReplayCompilationSupport.enterSnippetContext(getProviders())) {
                    snippetEncoder.registerSnippet(method, original, receiver, trackNodeSourcePosition);
                }
            }
        }
    }

    /**
     * Returns true if the current runtime or current compilation thread registers snippet methods
     * to be encoded. This is true when building libgraal or when replaying a libgraal compilation
     * in jargraal.
     *
     * @return true if snippets methods should be registered
     */
    private static boolean isRegisteringSnippetMethods() {
        return !inLibGraalRuntime() && (inBuildtimeCode() || isReplayingLibgraalInJargraal());
    }

    @Override
    public SnippetParameterInfo getSnippetParameterInfo(ResolvedJavaMethod method) {
        if (isAfterSnippetEncoding()) {
            return getEncodedSnippets().getSnippetParameterInfo(method);
        }
        return super.getSnippetParameterInfo(method);
    }

    @Override
    public boolean isSnippet(ResolvedJavaMethod method) {
        if (isAfterSnippetEncoding()) {
            return getEncodedSnippets().isSnippet(method);
        }
        return super.isSnippet(method);
    }

    @Override
    public void closeSnippetRegistration() {
        if (inBuildtimeCode()) {
            snippetRegistrationClosed = true;
        }
    }

    public static EncodedSnippets getEncodedSnippets() {
        if (encodedSnippets == null) {
            throw GraalError.shouldNotReachHere("encoded snippets not found"); // ExcludeFromJacocoGeneratedReport
        }
        return encodedSnippets;
    }

    @LibGraalSupport.HostedOnly//
    public static boolean snippetsAreEncoded() {
        return encodedSnippets != null;
    }

    @LibGraalSupport.HostedOnly//
    public static void setEncodedSnippets(EncodedSnippets encodedSnippets) {
        HotSpotReplacementsImpl.encodedSnippets = encodedSnippets;
        SnippetTemplate.setHostedEncodedSnippets(snippetsAreEncoded());
    }

    @LibGraalSupport.HostedOnly//
    @SuppressWarnings("try")
    public boolean encode(OptionValues options) {
        SymbolicSnippetEncoder encoder = snippetEncoder;
        if (encoder != null) {
            try (DebugCloseable ignored = ReplayCompilationSupport.enterSnippetContext(getProviders())) {
                return encoder.encode(options);
            }
        }
        return false;
    }

    private static volatile EncodedSnippets encodedSnippets;

    @LibGraalSupport.HostedOnly//
    private static SymbolicSnippetEncoder snippetEncoder;

    @SuppressWarnings("try")
    @Override
    public StructuredGraph getSnippet(ResolvedJavaMethod method, ResolvedJavaMethod original, Object[] args, BitSet nonNullParameters, boolean trackNodeSourcePosition,
                    NodeSourcePosition replaceePosition, OptionValues options) {
        if (isUsingEncodedSnippets()) {
            // Snippets graphs can contain foreign object references and
            // outlive a single compilation.
            try (CompilationContext scope = HotSpotGraalServices.enterGlobalCompilationContext()) {
                StructuredGraph graph = getEncodedSnippets().getEncodedSnippet(method, original, this, args, AllowAssumptions.NO, options);
                if (graph == null) {
                    throw GraalError.shouldNotReachHere("snippet not found: " + method.format("%H.%n(%p)")); // ExcludeFromJacocoGeneratedReport
                }
                return graph;
            }
        }

        assert !inBuildtimeCode() || registeredSnippets == null || registeredSnippets.contains(method) : "Asking for snippet method that was never registered: " + method.format("%H.%n(%p)");
        try (DebugCloseable ignored = ReplayCompilationSupport.enterSnippetContext(getProviders())) {
            return super.getSnippet(method, original, args, nonNullParameters, trackNodeSourcePosition, replaceePosition, options);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getInjectedArgument(Class<T> capability) {
        if (capability.equals(GraalHotSpotVMConfig.class)) {
            return (T) getProviders().getConfig();
        }
        return super.getInjectedArgument(capability);
    }

    public static MetaAccessProvider noticeTypes(MetaAccessProvider metaAccess) {
        if (!inLibGraalRuntime()) {
            return SymbolicSnippetEncoder.noticeTypes(metaAccess);
        }
        return metaAccess;
    }

    public static boolean isGraalClass(ResolvedJavaType type) {
        return isGraalClass(type.toClassName());
    }

    static boolean isGraalClass(Class<?> clazz) {
        return isGraalClass(clazz.getName());
    }

    static boolean isGraalClass(String className) {
        String elementClassName;
        if (className.charAt(0) == '[') {
            elementClassName = className;
            while (elementClassName.charAt(0) == '[') {
                elementClassName = elementClassName.substring(1);
            }
            if (elementClassName.charAt(0) != 'L') {
                // Primitive class
                return false;
            }

            // Strip leading 'L' and trailing ';'
            elementClassName = elementClassName.substring(1, elementClassName.length() - 1);
        } else {
            elementClassName = className;
        }
        return elementClassName.startsWith("jdk.vm.ci.") ||
                        elementClassName.startsWith("jdk.graal.compiler.") ||
                        elementClassName.startsWith("org.graalvm.") ||
                        elementClassName.startsWith("com.oracle.graal.");
    }

    @Override
    protected GraphMaker createGraphMaker(ResolvedJavaMethod substitute, ResolvedJavaMethod original) {
        return new HotSpotGraphMaker(this, substitute, original);
    }

    static class HotSpotGraphMaker extends GraphMaker {

        HotSpotGraphMaker(ReplacementsImpl replacements, ResolvedJavaMethod substitute, ResolvedJavaMethod substitutedMethod) {
            super(replacements, substitute, substitutedMethod);
        }

        @Override
        protected Instance createGraphBuilder(Providers providers, GraphBuilderConfiguration graphBuilderConfig, OptimisticOptimizations optimisticOpts, IntrinsicContext initialIntrinsicContext) {
            return new HotSpotGraphBuilderInstance(providers, graphBuilderConfig, optimisticOpts, initialIntrinsicContext);
        }

    }
}
