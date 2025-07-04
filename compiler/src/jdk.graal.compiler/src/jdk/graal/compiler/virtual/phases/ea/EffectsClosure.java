/*
 * Copyright (c) 2011, 2024, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.virtual.phases.ea;

import java.util.ArrayList;
import java.util.List;

import org.graalvm.collections.EconomicMap;
import org.graalvm.collections.EconomicSet;
import org.graalvm.collections.Equivalence;
import org.graalvm.word.LocationIdentity;

import jdk.graal.compiler.core.common.GraalOptions;
import jdk.graal.compiler.core.common.cfg.BlockMap;
import jdk.graal.compiler.core.common.cfg.CFGLoop;
import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.core.common.util.CompilationAlarm;
import jdk.graal.compiler.debug.Assertions;
import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.debug.Indent;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeBitMap;
import jdk.graal.compiler.graph.NodeMap;
import jdk.graal.compiler.graph.iterators.NodeIterable;
import jdk.graal.compiler.nodes.AbstractMergeNode;
import jdk.graal.compiler.nodes.FixedNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.IfNode;
import jdk.graal.compiler.nodes.LogicConstantNode;
import jdk.graal.compiler.nodes.LogicNode;
import jdk.graal.compiler.nodes.LoopBeginNode;
import jdk.graal.compiler.nodes.LoopExitNode;
import jdk.graal.compiler.nodes.PhiNode;
import jdk.graal.compiler.nodes.ProxyNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.StructuredGraph.ScheduleResult;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.ValuePhiNode;
import jdk.graal.compiler.nodes.WithExceptionNode;
import jdk.graal.compiler.nodes.cfg.ControlFlowGraph;
import jdk.graal.compiler.nodes.cfg.HIRBlock;
import jdk.graal.compiler.nodes.extended.BoxNode;
import jdk.graal.compiler.nodes.util.GraphUtil;
import jdk.graal.compiler.nodes.virtual.AllocatedObjectNode;
import jdk.graal.compiler.nodes.virtual.CommitAllocationNode;
import jdk.graal.compiler.nodes.virtual.VirtualObjectNode;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.graph.ReentrantBlockIterator;
import jdk.graal.compiler.phases.graph.ReentrantBlockIterator.BlockIteratorClosure;
import jdk.graal.compiler.phases.graph.ReentrantBlockIterator.LoopInfo;

public abstract class EffectsClosure<BlockT extends EffectsBlockState<BlockT>> extends EffectsPhase.Closure<BlockT> {

    protected final ControlFlowGraph cfg;
    protected final ScheduleResult schedule;

    /**
     * If a node has an alias, this means that it was replaced with another node during analysis.
     * Nodes can be replaced by normal ("scalar") nodes, e.g., a LoadIndexedNode with a
     * ConstantNode, or by virtual nodes, e.g., a NewInstanceNode with a VirtualInstanceNode. A node
     * was replaced with a virtual value iff the alias is a subclass of VirtualObjectNode.
     *
     * This alias map exists only once and is not part of the block state, so that during iterative
     * loop processing the alias of a node may be changed to another value.
     */
    protected NodeMap<ValueNode> aliases;

    /**
     * This set allows for a quick check whether a node has inputs that were replaced with "scalar"
     * values.
     */
    private NodeBitMap hasScalarReplacedInputs;

    /*
     * TODO: if it was possible to introduce your own subclasses of Block and Loop, these maps would
     * not be necessary. We could merge the GraphEffectsList logic into them.
     */

    /**
     * The effects accumulated during analysis of nodes. They may be cleared and re-filled during
     * iterative loop processing.
     */
    protected BlockMap<GraphEffectList> blockEffects;

    /**
     * Effects that can only be applied after the effects from within the loop have been applied and
     * that must be applied before any effect from after the loop is applied. E.g., updating phis.
     */
    protected EconomicMap<CFGLoop<HIRBlock>, GraphEffectList> loopMergeEffects = EconomicMap.create(Equivalence.IDENTITY);

    /**
     * The entry state of loops is needed when loop proxies are processed.
     */
    private EconomicMap<LoopBeginNode, BlockT> loopEntryStates = EconomicMap.create(Equivalence.IDENTITY);

    // Intended to be used by read-eliminating phases based on the effects phase.
    protected EconomicMap<CFGLoop<HIRBlock>, LoopKillCache> loopLocationKillCache = EconomicMap.create(Equivalence.IDENTITY);

    protected boolean changed;
    protected final DebugContext debug;
    /**
     * The current execution mode: once we reach a maximum loop nest we stop further effects and
     * only perform the minimal necessary operations.
     */
    protected EffectsClosureMode currentMode;

    public EffectsClosure(ScheduleResult schedule, ControlFlowGraph cfg) {
        this.schedule = schedule;
        this.cfg = cfg;
        this.aliases = cfg.graph.createNodeMap();
        this.hasScalarReplacedInputs = cfg.graph.createNodeBitMap();
        this.blockEffects = new BlockMap<>(cfg);
        this.debug = cfg.graph.getDebug();
        for (HIRBlock block : cfg.getBlocks()) {
            blockEffects.put(block, new GraphEffectList(debug));
        }
        this.currentMode = EffectsClosureMode.REGULAR_VIRTUALIZATION;
    }

    @Override
    public boolean hasChanged() {
        return changed;
    }

    @Override
    public boolean needsApplyEffects() {
        return true;
    }

    @Override
    public void applyEffects() {
        final StructuredGraph graph = cfg.graph;
        final ArrayList<Node> obsoleteNodes = new ArrayList<>(0);
        final ArrayList<GraphEffectList> effectList = new ArrayList<>();
        /*
         * Effects are applied during a ordered iteration over the blocks to apply them in the
         * correct order, e.g., apply the effect that adds a node to the graph before the node is
         * used.
         */
        BlockIteratorClosure<Void> closure = new BlockIteratorClosure<>() {

            @Override
            protected Void getInitialState() {
                return null;
            }

            private void apply(GraphEffectList effects) {
                if (effects != null && !effects.isEmpty()) {
                    effectList.add(effects);
                }
            }

            @Override
            protected Void processBlock(HIRBlock block, Void currentState) {
                apply(blockEffects.get(block));
                return currentState;
            }

            @Override
            protected Void merge(HIRBlock merge, List<Void> states) {
                return null;
            }

            @Override
            protected Void cloneState(Void oldState) {
                return oldState;
            }

            @Override
            protected List<Void> processLoop(CFGLoop<HIRBlock> loop, Void initialState) {
                LoopInfo<Void> info = ReentrantBlockIterator.processLoop(this, loop, initialState);
                apply(loopMergeEffects.get(loop));
                return info.exitStates;
            }

        };
        ReentrantBlockIterator.apply(closure, cfg.getStartBlock());
        for (GraphEffectList effects : effectList) {
            effects.apply(graph, obsoleteNodes, false);
        }
        /*
         * Effects that modify the cfg (e.g., removing a branch for an if that got a constant
         * condition) need to be performed after all other effects, because they change phi value
         * indexes.
         */
        for (GraphEffectList effects : effectList) {
            effects.apply(graph, obsoleteNodes, true);
        }
        debug.dump(DebugContext.DETAILED_LEVEL, graph, "After applying effects");
        assert VirtualUtil.assertNonReachable(graph, obsoleteNodes) : Assertions.errorMessage("obsolete nodes should not be reachable: ", obsoleteNodes);
        for (Node node : obsoleteNodes) {
            if (node.isAlive() && node.hasNoUsages()) {
                if (node instanceof FixedWithNextNode) {
                    assert ((FixedWithNextNode) node).next() == null;
                }
                node.replaceAtUsages(null);
                GraphUtil.killWithUnusedFloatingInputs(node);
            }
        }
    }

    @Override
    protected BlockT processBlock(HIRBlock block, BlockT state) {
        if (!state.isDead()) {
            GraphEffectList effects = blockEffects.get(block);

            /*
             * If we enter an if branch that is known to be unreachable, we mark it as dead and
             * cease to do any more analysis on it. At merges, these dead branches will be ignored.
             */
            if (block.getBeginNode().predecessor() instanceof IfNode) {
                IfNode ifNode = (IfNode) block.getBeginNode().predecessor();
                LogicNode condition = ifNode.condition();
                Node alias = getScalarAlias(condition);
                if (alias instanceof LogicConstantNode) {
                    LogicConstantNode constant = (LogicConstantNode) alias;
                    boolean isTrueSuccessor = block.getBeginNode() == ifNode.trueSuccessor();

                    if (constant.getValue() != isTrueSuccessor) {
                        state.markAsDead();
                        effects.killIfBranch(ifNode, constant.getValue());
                        return state;
                    }
                }
            }

            OptionValues options = block.getBeginNode().getOptions();
            if (GraalOptions.TraceEscapeAnalysis.getValue(block.getBeginNode().getOptions())) {
                int predCount = block.getPredecessorCount();
                HIRBlock[] pred = new HIRBlock[predCount];
                for (int i = 0; i < predCount; i++) {
                    pred[i] = block.getPredecessorAt(i);
                }
                int succCount = block.getSuccessorCount();
                HIRBlock[] succ = new HIRBlock[succCount];
                for (int i = 0; i < succCount; i++) {
                    succ[i] = block.getSuccessorAt(i);
                }
                VirtualUtil.trace(options, debug, "\nBlock: %s, preds: %s, succ: %s (", block, pred, succ);
            }

            // a lastFixedNode is needed in case we want to insert fixed nodes
            FixedWithNextNode lastFixedNode = null;
            Iterable<? extends Node> nodes = schedule != null ? schedule.getBlockToNodesMap().get(block) : block.getNodes();
            for (Node node : nodes) {
                // reset the aliases (may be non-null due to iterative loop processing)
                aliases.set(node, null);
                if (node instanceof LoopExitNode) {
                    LoopExitNode loopExit = (LoopExitNode) node;
                    for (ProxyNode proxy : loopExit.proxies()) {
                        aliases.set(proxy, null);
                        changed |= processNode(proxy, state, effects, lastFixedNode) && isSignificantNode(node);
                    }
                    processLoopExit(loopExit, loopEntryStates.get(loopExit.loopBegin()), state, blockEffects.get(block));
                }
                HIRBlock exceptionEdgeToKill = node instanceof WithExceptionNode ? cfg.blockFor(((WithExceptionNode) node).exceptionEdge()) : null;
                boolean lastNodeChanged = processNode(node, state, effects, lastFixedNode) && isSignificantNode(node);
                changed |= lastNodeChanged;
                if (lastNodeChanged && exceptionEdgeToKill != null) {
                    /*
                     * We deleted a exception node, per definition the exception edge died in that
                     * process, no need to process the exception edge
                     */
                    if (state.exceptionEdgesToKill == null) {
                        state.exceptionEdgesToKill = EconomicSet.create();
                    }
                    state.exceptionEdgesToKill.add(exceptionEdgeToKill);
                }
                if (node instanceof FixedWithNextNode) {
                    lastFixedNode = (FixedWithNextNode) node;
                }
                if (state.isDead()) {
                    break;
                }
            }
            VirtualUtil.trace(options, debug, ")\n    end state: %s\n", state);
        }
        return state;
    }

    @Override
    protected BlockT afterSplit(HIRBlock successor, BlockT oldState) {
        BlockT state = oldState;
        if (oldState.exceptionEdgesToKill != null && oldState.exceptionEdgesToKill.contains(successor)) {
            state.markAsDead();
        }
        return state;
    }

    /**
     * Changes to {@link CommitAllocationNode}s, {@link AllocatedObjectNode}s and {@link BoxNode}s
     * are not considered to be "important". If only changes to those nodes are discovered during
     * analysis, the effects need not be applied.
     */
    private static boolean isSignificantNode(Node node) {
        return !(node instanceof CommitAllocationNode || node instanceof AllocatedObjectNode || node instanceof BoxNode);
    }

    enum EffectsClosureMode {
        /**
         * Regular processing of virtualizable nodes, i.e., try to virtualize them if possible.
         */
        REGULAR_VIRTUALIZATION,
        /**
         * Stop trying to virtualize allocations since the maximum loop nesting level is reached.
         */
        STOP_NEW_VIRTUALIZATIONS_LOOP_NEST,
        /**
         * Immediately materialize all virtual allocations after virtualization to avoid
         * re-iterating loops during PEA.
         */
        MATERIALIZE_ALL
    }

    /**
     * Collects the effects of virtualizing the given node.
     *
     * @return {@code true} if the effects include removing the node, {@code false} otherwise.
     */
    protected abstract boolean processNode(Node node, BlockT state, GraphEffectList effects, FixedWithNextNode lastFixedNode);

    @Override
    protected BlockT merge(HIRBlock merge, List<BlockT> states) {
        assert blockEffects.get(merge).isEmpty();
        MergeProcessor processor = createMergeProcessor(merge);
        doMergeWithoutDead(processor, states);
        blockEffects.get(merge).addAll(processor.mergeEffects);
        blockEffects.get(merge).addAll(processor.afterMergeEffects);
        return processor.newState;
    }

    @Override
    @SuppressWarnings("try")
    protected final List<BlockT> processLoop(CFGLoop<HIRBlock> loop, BlockT initialState) {
        final StructuredGraph graph = loop.getHeader().getBeginNode().graph();
        if (initialState.isDead()) {
            ArrayList<BlockT> states = new ArrayList<>();
            for (int i = 0; i < loop.getLoopExits().size(); i++) {
                states.add(initialState);
            }
            return states;
        }
        /*
         * Special case nested loops: To avoid an exponential runtime for nested loops we try to
         * only process them as little times as possible.
         *
         * In the first iteration of an outer most loop we go into the inner most loop(s). We run
         * the first iteration of the inner most loop and then, if necessary, a second iteration.
         *
         * We return from the recursion and finish the first iteration of the outermost loop. If we
         * have to do a second iteration in the outer most loop we go again into the inner most
         * loop(s) but this time we already know all states that are killed by the loop so inside
         * the loop we will only have those changes that propagate from the first iteration of the
         * outer most loop into the current loop. We strip the initial loop state for the inner most
         * loops and do the first iteration with the (possible) changes from outer loops. If there
         * are no changes we only have to do 1 iteration and are done.
         *
         * However, the stripping in the innermost loop(s) is only done for new allocations, i.e.
         * every allocation reached after the loop depth filter is automatically materialized. If we
         * reach an outer loop's allocation that is still virtual in an inner loop with depth >
         * cutOff, and this virtualized allocation is materialized in the inner loop we throw an
         * exception and re-do the entire loop nest and materialize everything
         *
         */
        BlockT initialStateRemovedKilledLocations = stripKilledLoopLocations(loop, cloneState(initialState));
        NodeMap<ValueNode> aliasesCopy = null;
        NodeBitMap hasScalarReplacedInputsCopy = null;
        BlockMap<GraphEffectList> blockEffectsCopy = null;
        EconomicMap<CFGLoop<HIRBlock>, GraphEffectList> loopMergeEffectsCopy = null;
        EconomicMap<LoopBeginNode, BlockT> loopEntryStatesCopy = null;
        EconomicMap<CFGLoop<HIRBlock>, LoopKillCache> loopLocationKillCacheCopy = null;
        BlockT initialStateRemovedKilledLocationsBackup = null;

        if (loop.getDepth() == 1) {

            /*
             * Find out if we will need the copy versions
             */
            boolean initBackUp = false;
            for (CFGLoop<HIRBlock> l : cfg.getLoops()) {
                if (l.getDepth() > GraalOptions.EscapeAnalysisLoopCutoff.getValue(cfg.graph.getOptions())) {
                    initBackUp = true;
                    break;
                }
            }
            if (initBackUp) {
                initialStateRemovedKilledLocationsBackup = cloneState(initialStateRemovedKilledLocations);
                aliasesCopy = new NodeMap<>(aliases);
                hasScalarReplacedInputsCopy = hasScalarReplacedInputs.copy();
                blockEffectsCopy = new BlockMap<>(cfg);
                for (HIRBlock block : cfg.getBlocks()) {
                    GraphEffectList copy = new GraphEffectList(debug);
                    copy.addAll(blockEffects.get(block));
                    blockEffectsCopy.put(block, copy);
                }
                loopMergeEffectsCopy = EconomicMap.create(Equivalence.IDENTITY);
                loopMergeEffectsCopy.putAll(loopMergeEffects);

                loopEntryStatesCopy = EconomicMap.create(Equivalence.IDENTITY);
                loopEntryStatesCopy.putAll(loopEntryStates);

                loopLocationKillCacheCopy = EconomicMap.create(Equivalence.IDENTITY);
                loopLocationKillCacheCopy.putAll(loopLocationKillCache);
            }
        }

        boolean tooManyIterationsSeen = false;
        while (true) { // // TERMINATION ARGUMENT: bound by number of basic blocks and iterative
                       // loop traversal
            CompilationAlarm.checkProgress(cfg.graph);
            try {
                BlockT loopEntryState = initialStateRemovedKilledLocations;
                BlockT lastMergedState = cloneState(initialStateRemovedKilledLocations);
                processInitialLoopState(loop, lastMergedState);
                MergeProcessor mergeProcessor = createMergeProcessor(loop.getHeader());
                /*
                 * Iterative loop processing: we take the predecessor state as the loop's starting
                 * state, processing the loop contents, merge the states of all loop ends, and check
                 * whether the resulting state is equal to the starting state. If it is, the loop
                 * processing has finished, if not, another iteration is needed.
                 *
                 * This processing converges because the merge processing always makes the starting
                 * state more generic, e.g., adding phis instead of non-phi values.
                 */
                boolean[] knownAliveLoopEnds = new boolean[loop.numBackedges()];
                for (int iteration = 0; iteration < 10; iteration++) {
                    try (Indent i = debug.logAndIndent("================== Process Loop Effects Closure: block:%s begin node:%s iteration:%s", loop.getHeader(), loop.getHeader().getBeginNode(),
                                    iteration)) {
                        LoopInfo<BlockT> info = ReentrantBlockIterator.processLoop(this, loop, cloneState(lastMergedState));

                        List<BlockT> states = new ArrayList<>();
                        states.add(initialStateRemovedKilledLocations);
                        states.addAll(info.endStates);
                        doMergeWithoutDead(mergeProcessor, states);

                        debug.log("MergeProcessor New State: %s", mergeProcessor.newState);
                        debug.log("===== vs.");
                        debug.log("Last Merged State: %s", lastMergedState);

                        if (mergeProcessor.newState.equivalentTo(lastMergedState)) {
                            blockEffects.get(loop.getHeader()).insertAll(mergeProcessor.mergeEffects, 0);
                            loopMergeEffects.put(loop, mergeProcessor.afterMergeEffects);

                            assert info.exitStates.size() == loop.getLoopExits().size() : Assertions.errorMessage(info, info.exitStates, loop, loop.getLoopExits());
                            loopEntryStates.put((LoopBeginNode) loop.getHeader().getBeginNode(), loopEntryState);
                            assert assertExitStatesNonEmpty(loop, info);

                            processKilledLoopLocations(loop, initialStateRemovedKilledLocations, mergeProcessor.newState);

                            if (currentMode != EffectsClosureMode.REGULAR_VIRTUALIZATION && loop.getDepth() == 1) {
                                /*
                                 * We are done processing the loop nest with limited EA for nested
                                 * objects deeper > level, switch back to normal mode.
                                 */
                                currentMode = EffectsClosureMode.REGULAR_VIRTUALIZATION;
                            }

                            return info.exitStates;
                        } else {
                            /*
                             * Check monotonicity: Once an iteration over the loop has determined
                             * that a certain loop end is reachable (the state at that end is
                             * alive), a later iteration must not conclude that that loop end is
                             * unreachable (the state is dead). This would mean that analysis
                             * information became more precise. But it can only become less precise
                             * as we try to converge towards a fixed point.
                             */
                            GraalError.guarantee(info.endStates.size() == knownAliveLoopEnds.length,
                                            "should have the same number of end states as loop ends: %s / %s",
                                            info.endStates.size(), knownAliveLoopEnds.length);
                            int endIndex = 0;
                            for (BlockT endState : info.endStates) {
                                GraalError.guarantee(!(knownAliveLoopEnds[endIndex] && endState.isDead()),
                                                "%s: monotonicity violated, state at loop end %s should remain alive but is dead: %s",
                                                loop, endIndex, endState);
                                knownAliveLoopEnds[endIndex] |= !endState.isDead();
                                endIndex++;
                            }

                            lastMergedState = mergeProcessor.newState;
                            for (HIRBlock block : loop.getBlocks()) {
                                blockEffects.get(block).clear();
                                if (block.isLoopHeader()) {
                                    final GraphEffectList loopEffects = loopMergeEffects.get(block.getLoop());
                                    if (loopEffects != null) {
                                        loopEffects.clear();
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (EffecsClosureOverflowException e) {
                if (loop.getDepth() != 1) {
                    /*
                     * We are not yet at the outermost loop, we rethrow the error to actually exit
                     * ALL cases
                     */
                    throw e;
                }
                /*
                 * We reached the outermost loop after having seen a loop nest operation that would
                 * cause exponential processing. Thus, we reset everything to before the loop and
                 * process the loop in a mode where we immediately materialize every virtualizable
                 * node in order to avoid any repetitive loop processing.
                 */
                assert aliases != aliasesCopy : aliasesCopy;
                aliases = aliasesCopy;
                hasScalarReplacedInputs = hasScalarReplacedInputsCopy;
                assert blockEffects != blockEffectsCopy : "Mus";
                blockEffects = blockEffectsCopy;
                loopMergeEffects = loopMergeEffectsCopy;
                loopEntryStates = loopEntryStatesCopy;
                loopLocationKillCache = loopLocationKillCacheCopy;
                initialStateRemovedKilledLocations = initialStateRemovedKilledLocationsBackup;
                processStateBeforeLoopOnOverflow(initialStateRemovedKilledLocations, ((LoopBeginNode) loop.getHeader().getBeginNode()).forwardEnd(),
                                blockEffects.get(loop.getHeader().getPredecessorAt(0)));
                currentMode = EffectsClosureMode.MATERIALIZE_ALL;
                continue;
            }
            if (!tooManyIterationsSeen) {
                tooManyIterationsSeen = true;
                /*
                 * The first time we see that we did too many iterations we materialize everything
                 * before the loop and see if that fixes our problems.
                 */
                graph.getDebug().dump(DebugContext.VERY_DETAILED_LEVEL, graph, "Too many loop iterations for %s trying to materialize everything before loop and redo loop nest", loop);
                currentMode = EffectsClosureMode.MATERIALIZE_ALL;
                continue;
            } else {
                throw new GraalError("too many iterations at %s", loop);
            }
        }
    }

    static class EffecsClosureOverflowException extends RuntimeException {

        private static final long serialVersionUID = 1;
    }

    @SuppressWarnings("unused")
    protected void processStateBeforeLoopOnOverflow(BlockT initialState, FixedNode materializeBefore, GraphEffectList effects) {

    }

    @SuppressWarnings("unused")
    protected BlockT stripKilledLoopLocations(CFGLoop<HIRBlock> loop, BlockT initialState) {
        return initialState;
    }

    @SuppressWarnings("unused")
    protected void processKilledLoopLocations(CFGLoop<HIRBlock> loop, BlockT initialState, BlockT mergedStates) {
        // nothing to do
    }

    @SuppressWarnings("unused")
    protected void processInitialLoopState(CFGLoop<HIRBlock> loop, BlockT initialState) {
        // nothing to do
    }

    private void doMergeWithoutDead(MergeProcessor mergeProcessor, List<BlockT> states) {
        int alive = 0;
        for (BlockT state : states) {
            if (!state.isDead()) {
                alive++;
            }
        }
        if (alive == 0) {
            mergeProcessor.setNewState(states.get(0));
        } else if (alive == states.size()) {
            int[] stateIndexes = new int[states.size()];
            for (int i = 0; i < stateIndexes.length; i++) {
                stateIndexes[i] = i;
            }
            mergeProcessor.setStateIndexes(stateIndexes);
            mergeProcessor.setNewState(getInitialState());
            mergeProcessor.merge(states);
        } else {
            ArrayList<BlockT> aliveStates = new ArrayList<>(alive);
            int[] stateIndexes = new int[alive];
            for (int i = 0; i < states.size(); i++) {
                if (!states.get(i).isDead()) {
                    stateIndexes[aliveStates.size()] = i;
                    aliveStates.add(states.get(i));
                }
            }
            mergeProcessor.setStateIndexes(stateIndexes);
            mergeProcessor.setNewState(getInitialState());
            mergeProcessor.merge(aliveStates);
        }
    }

    private boolean assertExitStatesNonEmpty(CFGLoop<HIRBlock> loop, LoopInfo<BlockT> info) {
        for (int i = 0; i < loop.getLoopExits().size(); i++) {
            assert info.exitStates.get(i) != null : "no loop exit state at " + loop.getLoopExits().get(i) + " / " + loop.getHeader();
        }
        return true;
    }

    protected abstract void processLoopExit(LoopExitNode exitNode, BlockT initialState, BlockT exitState, GraphEffectList effects);

    protected abstract MergeProcessor createMergeProcessor(HIRBlock merge);

    /**
     * The main workhorse for merging states, both for loops and for normal merges.
     */
    protected abstract class MergeProcessor {

        protected final HIRBlock mergeBlock;
        protected final AbstractMergeNode merge;

        protected final GraphEffectList mergeEffects;
        protected final GraphEffectList afterMergeEffects;

        /**
         * The indexes are used to map from an index in the list of active (non-dead) predecessors
         * to an index in the list of all predecessors (the latter may be larger).
         */
        private int[] stateIndexes;
        protected BlockT newState;

        public MergeProcessor(HIRBlock mergeBlock) {
            this.mergeBlock = mergeBlock;
            this.merge = (AbstractMergeNode) mergeBlock.getBeginNode();
            this.mergeEffects = new GraphEffectList(debug);
            this.afterMergeEffects = new GraphEffectList(debug);
        }

        /**
         * @param states the states that should be merged.
         */
        protected abstract void merge(List<BlockT> states);

        private void setNewState(BlockT state) {
            newState = state;
            mergeEffects.clear();
            afterMergeEffects.clear();
        }

        private void setStateIndexes(int[] stateIndexes) {
            this.stateIndexes = stateIndexes;
        }

        protected final HIRBlock getPredecessor(int index) {
            return mergeBlock.getPredecessorAt(stateIndexes[index]);
        }

        protected final NodeIterable<PhiNode> getPhis() {
            return merge.phis();
        }

        protected final ValueNode getPhiValueAt(PhiNode phi, int index) {
            return phi.valueAt(stateIndexes[index]);
        }

        protected final ValuePhiNode createValuePhi(Stamp stamp) {
            ValuePhiNode valuePhi = new ValuePhiNode(stamp, merge, new ValueNode[mergeBlock.getPredecessorCount()]);
            valuePhi.setNodeSourcePosition(merge.getNodeSourcePosition());
            return valuePhi;
        }

        protected final void setPhiInput(PhiNode phi, int index, ValueNode value) {
            afterMergeEffects.initializePhiInput(phi, stateIndexes[index], value);
        }

        protected final StructuredGraph graph() {
            return merge.graph();
        }

        @Override
        public String toString() {
            return "MergeProcessor@" + merge;
        }
    }

    public void addScalarAlias(ValueNode node, ValueNode alias) {
        assert !(alias instanceof VirtualObjectNode) : "Must not be a virtual object node " + alias;
        aliases.set(node, alias);
        for (Node usage : node.usages()) {
            if (!hasScalarReplacedInputs.isNew(usage)) {
                hasScalarReplacedInputs.mark(usage);
            }
        }
    }

    protected final boolean hasScalarReplacedInputs(Node node) {
        return hasScalarReplacedInputs.isMarked(node);
    }

    public ValueNode getScalarAlias(ValueNode node) {
        assert !(node instanceof VirtualObjectNode) : node;
        if (node == null || !node.isAlive() || aliases.isNew(node)) {
            return node;
        }
        ValueNode result = aliases.get(node);
        return (result == null || result instanceof VirtualObjectNode) ? node : result;
    }

    protected static final class LoopKillCache {
        private int visits;
        private LocationIdentity firstLocation;
        private EconomicSet<LocationIdentity> killedLocations;
        private boolean killsAll;

        protected LoopKillCache(int visits) {
            this.visits = visits;
        }

        protected void visited() {
            visits++;
        }

        protected int visits() {
            return visits;
        }

        protected void setKillsAll() {
            killsAll = true;
            firstLocation = null;
            killedLocations = null;
        }

        protected boolean containsLocation(LocationIdentity locationIdentity) {
            if (killsAll) {
                return true;
            }
            if (firstLocation == null) {
                return false;
            }
            if (!firstLocation.equals(locationIdentity)) {
                return killedLocations != null ? killedLocations.contains(locationIdentity) : false;
            }
            return true;
        }

        protected void rememberLoopKilledLocation(LocationIdentity locationIdentity) {
            if (killsAll) {
                return;
            }
            if (firstLocation == null || firstLocation.equals(locationIdentity)) {
                firstLocation = locationIdentity;
            } else {
                if (killedLocations == null) {
                    killedLocations = EconomicSet.create(Equivalence.IDENTITY);
                }
                killedLocations.add(locationIdentity);
            }
        }

        protected boolean loopKillsLocations() {
            if (killsAll) {
                return true;
            }
            return firstLocation != null;
        }
    }

}
