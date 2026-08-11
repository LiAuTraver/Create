package com.simibubi.create.content.trains.track;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.simibubi.create.Create;
import com.simibubi.create.api.event.TrackGraphMergeEvent;
import com.simibubi.create.content.trains.GlobalRailwayManager;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation.DiscoveredLocation;
import com.simibubi.create.content.trains.signal.SignalPropagator;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.common.NeoForge;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TrackPropagator {

	private record FrontierEntry(@Nullable DiscoveredLocation parent, @Nullable DiscoveredLocation previous,
	                             @NotNull DiscoveredLocation current) {
	}

	@NotNull
	final GlobalRailwayManager M;
	@NotNull
	final LevelAccessor LA;
	@NotNull
	final BlockPos BP;
	@NotNull
	final BlockState BS;
	@NotNull
	final ITrackBlock TB;
	@NotNull
	final static Logger L = LoggerFactory.getLogger(TrackPropagator.class);
	final static int kMaxThreshold = 0x2000;

	/// the block state must be a track block, otherwise the behavior is undefined.
	private TrackPropagator(@NotNull LevelAccessor LA, @NotNull BlockPos BP, @NotNull BlockState BS) {
		this.M = Create.RAILWAYS;
		this.LA = LA;
		this.BP = BP;
		this.BS = BS;
		this.TB = (ITrackBlock) BS.getBlock();
	}

	public static @Nullable TrackGraph onRailAdded(@NotNull LevelAccessor reader, @NotNull BlockPos pos,
	                                               @NotNull BlockState state) {
		if (!(state.getBlock() instanceof ITrackBlock))
			// this is normal and expected behavior when calling from the onRailRemoved call
			// since it's needed to update adjacent block.
			return null;

		return new TrackPropagator(reader, pos, state).onRailAdded();
	}

	public static void onRailRemoved(@NotNull LevelAccessor reader, @NotNull BlockPos pos, @NotNull BlockState state) {
		if (!(state.getBlock() instanceof ITrackBlock)) {
			L.warn("state {} is not an instance of ITrackBlock, ignoring it.", state.getBlock().getClass().getName());
			return;
		}
		new TrackPropagator(reader, pos, state).onRailRemoved();
	}

	private @NotNull TrackGraph onRailAdded() {
		// < remove all immediately reachable node locations around the new rail
		var frontiers = getFrontiers(false);
		final var connectedGraphs = removeReachableNodes(frontiers);

		// < resolve target graph, merging if necessary
		final var targetGraph = canonicalizeGraph(connectedGraphs);

		// < find the first graph node candidate nearby
		frontiers = getFrontiers(true);

		final var startNode = getStartNode(frontiers);
		if (startNode == null) {
			L.warn("Start node candidate must not be null when placing track, aborting graph build.");
			return targetGraph;
		}

		// < build up the graph via all connected nodes
		final var addedNodes = buildGraph(targetGraph, startNode);
		addedNodes.forEach(trackNode -> SignalPropagator.notifySignalsOfNewNode(targetGraph, trackNode));

		M.markTracksDirty();
		return targetGraph;
	}

	private void onRailRemoved() {
		final var connectedLocs = TB.getConnected(LA, BP, BS, false, null);

		final var positionsToUpdate = connectedLocs.stream()
			.peek(removedLocation -> M.getGraphs(LA, removedLocation).forEach(foundGraph -> {
				// > remove any nodes this rail was part of...
				final var removedNode = foundGraph.locateNode(removedLocation);
				if (removedNode == null)
					return;
				foundGraph.removeNode(LA, removedLocation);
				M.sync.nodeRemoved(foundGraph, removedNode);
				if (!foundGraph.isEmpty())
					return;
				M.removeGraphAndGroup(foundGraph);
				M.sync.graphRemoved(foundGraph);
			}))
			.flatMap(removedEnd -> removedEnd.allAdjacent().stream())
			.collect(Collectors.toSet()); // dedup is essential in order to avoid redundant onRailAdd call

		// > re-run railAdded for any track that was disconnected from this track
		positionsToUpdate.stream()
			.filter(blockPos -> !blockPos.equals(BP))
			.map(blockPos -> onRailAdded(LA, blockPos, LA.getBlockState(blockPos)))
			.filter(Objects::nonNull)
			.forEach(railGraph -> M.updateSplitGraph(LA, railGraph));
		// > check updated graph for segmentation, if any. ^^^

		M.markTracksDirty();
	}

	private @NotNull Set<TrackGraph> removeReachableNodes(@NotNull Queue<FrontierEntry> frontier) {
		final var visited = new HashSet<DiscoveredLocation>();
		final var connectedGraphs = new HashSet<TrackGraph>();

		withThreshold(kMaxThreshold, frontier, entry -> {
			final var graphs = M.getGraphs(LA, entry.current);
			graphs.forEach(graph -> {
				final var node = graph.locateNode(entry.current);
				graph.removeNode(LA, entry.current);
				// node can be null if the node2graph cache has a stale entry pointing to a
				// graph
				// that no longer contains this location
				// (e.g. after a graph merge/split that cleared nodes without going through
				// removeNode).
				//
				// usually it won't happen. nonetheless here we suppress a NPE into
				// TrackGraphSync, and log it for diags.
				// if the warning triggered, code has somewhere messed up.
				if (node == null) {
					L.warn("node2graph cache inconsistency: graph {} returned by getGraphs " +
					       "but locateNode({}) returned null; skipping nodeRemoved sync.", graph.id, entry.current);
					connectedGraphs.add(graph);
					return;
				}
				M.sync.nodeRemoved(graph, node);
				connectedGraphs.add(graph);
			});

			// if this location was already a graph node, no need expand the BFS past it;
			// it was already a boundary of the previous graph structure.
			if (!graphs.isEmpty()) return;

			final var connectedLocs = ITrackBlock.walkConnectedTracks(LA, entry.current, false);
			if (entry.previous != null)
				connectedLocs.remove(entry.previous);

			BFS(frontier, visited, entry, connectedLocs);
		}, () -> L.warn("threshold reached while removing reachable nodes, aborting search."));

		return connectedGraphs;
	}

	private @NotNull TrackGraph canonicalizeGraph(@NotNull Set<TrackGraph> connectedGraphs) {
		// remove empty graphs, unless it's the only graph left
		connectedGraphs.removeIf(railGraph -> {
			if (!railGraph.isEmpty() || connectedGraphs.size() == 1) return false;
			M.removeGraphAndGroup(railGraph);
			M.sync.graphRemoved(railGraph);
			return true;
		});

		final var canonical = connectedGraphs.stream()
			.findFirst()
			.orElseGet(() -> M.putGraphWithDefaultGroup(new TrackGraph()));

		connectedGraphs.stream().skip(1).forEach(other -> {
			NeoForge.EVENT_BUS.post(new TrackGraphMergeEvent(other, canonical));
			other.transferAll(canonical);
			M.removeGraphAndGroup(other);
			M.sync.graphRemoved(other);
		});

		return canonical;
	}

	private @Nullable DiscoveredLocation getStartNode(Queue<FrontierEntry> frontier) {
		final var visited = new HashSet<DiscoveredLocation>();

		return withThreshold(kMaxThreshold, frontier, entry -> {
			final var connectedLocs = ITrackBlock.walkConnectedTracks(LA, entry.current, false);
			final var first = entry.previous == null;
			if (!first)
				connectedLocs.remove(entry.previous);
			if (eligibleAsNode(entry.current, connectedLocs, first))
				return entry.current;

			BFS(frontier, visited, entry, connectedLocs);
			return null;
		}, () -> L.warn("threshold reached while finding start node, aborting search."));
	}

	private @NotNull Set<TrackNode> buildGraph(@NotNull TrackGraph graph, @NotNull DiscoveredLocation startNode) {
		final var addedNodes = new HashSet<TrackNode>();
		final var frontiers = new ArrayDeque<FrontierEntry>();
		graph.createNodeIfAbsent(startNode);
		frontiers.add(new FrontierEntry(startNode, null, startNode));

		withThreshold(kMaxThreshold, frontiers, entry -> {
			// var parent = Objects.requireNonNull(entry.parent, "manual CFG inspection: never fails");
			var parent = entry.parent;
			final var connectedLocs = ITrackBlock.walkConnectedTracks(LA, entry.current, false);
			final var first = entry.previous == null;
			if (!first)
				connectedLocs.remove(entry.previous);

			if (eligibleAsNode(entry.current, connectedLocs, first) && entry.current != startNode) {
				final var added = graph.createNodeIfAbsent(entry.current);
				//noinspection DataFlowIssue
				graph.connectNodes(LA, parent, entry.current, entry.current.getTurn());
				addedNodes.add(graph.locateNode(entry.current));
				parent = entry.current;
				if (!added) return;
			}

			//noinspection DataFlowIssue
			BFS(frontiers, entry, connectedLocs, parent);
		}, () -> L.warn("threshold reached while building graph from nodes, aborting."));

		return addedNodes;
	}

	private @NotNull Queue<FrontierEntry> getFrontiers(boolean ignoreTurns) {
		return TB.getConnected(LA, BP, BS, ignoreTurns, null).stream()
			.map(location -> new FrontierEntry(null, null, location))
			.collect(Collectors.toCollection(ArrayDeque::new));
	}

	private static void BFS(@NotNull Queue<FrontierEntry> frontiers, @NotNull Set<DiscoveredLocation> visited,
	                        @NotNull FrontierEntry entry, @NotNull Collection<DiscoveredLocation> connectedLocs) {
		connectedLocs.stream()
			.filter(visited::add)
			.map(location -> new FrontierEntry(null, entry.current, location))
			.forEach(frontiers::add);
	}

	private static void BFS(@NotNull Queue<FrontierEntry> frontiers, @NotNull FrontierEntry entry,
	                        @NotNull Collection<DiscoveredLocation> connectedLocs, @NotNull DiscoveredLocation parent) {
		connectedLocs.stream()
			.map(location -> new FrontierEntry(parent, entry.current, location))
			.forEach(frontiers::add);
	}

	/**
	 * Checks whether the node position falls on a chunk boundary (% 16 == 0).
	 * <p>
	 * This is necessary for connection search as well as AABB spatial boundings.
	 */
	private static boolean isChunkBoundaryNode(@NotNull Vec3 vec) {
		final var centeredX = !Mth.equal(vec.x, Math.round(vec.x));
		final var centeredZ = !Mth.equal(vec.z, Math.round(vec.z));
		if (centeredX && !centeredZ)
			return Math.round(vec.z) % 16 == 0;
		return Math.round(vec.x) % 16 == 0;
	}

	private static boolean eligibleAsNode(@NotNull DiscoveredLocation location,
	                                      @NotNull Collection<DiscoveredLocation> next, boolean first) {
		final var size = next.size() - (first ? 1 : 0);
		if (size != 1)
			return true;
		if (location.shouldForceNode())
			return true;
		if (location.differentMaterials())
			return true;
		if (next.stream().anyMatch(DiscoveredLocation::shouldForceNode))
			return true;

		final var direction = location.getDirection();
		if (direction != null && next.stream().anyMatch(dl -> dl.notInLineWith(direction)))
			return true;

		return isChunkBoundaryNode(location.getLocation());
	}

	/**
	 * see {@link #withThreshold(int, Queue, Function, Runnable)} for details.
	 */
	private static <T> void withThreshold(
		int maxIterations,
		@NotNull Queue<T> frontiers,
		@NotNull Consumer<T> processor,
		@NotNull Runnable callback) {
		withThreshold(maxIterations, frontiers, entry -> {
			processor.accept(entry);
			return null;
		}, callback);
	}

	/**
	 * @param <T>           type
	 * @param <R>           return type
	 * @param frontiers     process object
	 * @param maxIterations threshold
	 * @param processor     loop logic
	 * @param callback      callback if threshold reached
	 * @return the result of the processor, or null if the queue was exhausted or
	 * the threshold was reached
	 */
	private static <T, R> @Nullable R withThreshold(int maxIterations, @NotNull Queue<T> frontiers,
	                                                @NotNull Function<T, R> processor, @NotNull Runnable callback) {
		var budget = 0;

		while (!frontiers.isEmpty()) {
			if (budget++ >= maxIterations) {
				callback.run();
				break;
			}

			final T entry = frontiers.poll();
			final R result = processor.apply(entry);
			if (result != null)
				return result;
		}

		return null;
	}

}
