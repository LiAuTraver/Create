package com.simibubi.create.content.trains;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.mutable.MutableObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.simibubi.create.CreateClient;
import com.simibubi.create.content.kinetics.KineticDebugger;
import com.simibubi.create.content.trains.display.GlobalTrainDisplayData;
import com.simibubi.create.content.trains.entity.AddTrainPacket;
import com.simibubi.create.content.trains.entity.RemoveTrainPacket;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackGraphSync;
import com.simibubi.create.content.trains.graph.TrackGraphVisualizer;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.signal.EdgeGroupColor;
import com.simibubi.create.content.trains.signal.SignalEdgeGroup;
import com.simibubi.create.infrastructure.config.AllConfigs;

import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

public class GlobalRailwayManager {

	public @NotNull Map<UUID, TrackGraph> trackNetworks;
	public Map<UUID, SignalEdgeGroup> signalEdgeGroups;
	public Map<UUID, Train> trains;
	public TrackGraphSync sync;
	/**
	 * cache, O(1) lookup for graphs containing a node, updated on graph/node add/remove.
	 * <p>
	 * used to avoid iterating all graphs (O(N), where N is the number of graphs) every time a node is looked up,
	 * when looking up a node in function {@link #getGraph(LevelAccessor, TrackNodeLocation)}.
	 *
	 * @implNote Multiple graphs can cross over at the same node but keep its independence; e.g., portal tracks.
	 * Also, during track propagation, temporary boundary nodes are evaluated
	 * while two graphs are being merged or split before final consolidation.
	 * <p>
	 * The is locally updated and lazily updated, server and client maintains different instances of it but kept in sync.
	 * <p>
	 * The memory cost of the cache is negligible -- described in {@link com.simibubi.create.infrastructure.gametest.tests.TestTrackGraph}
	 */
	private @NotNull
	final Map<TrackNodeLocation, Set<TrackGraph>> node2graph = new HashMap<>();

	private List<Train> movingTrains;
	private List<Train> waitingTrains;

	private RailwaySavedData savedData;

	public int version;

	public GlobalRailwayManager() {
		cleanUp();
	}

	protected void do_add_node(@NotNull TrackGraph graph, @NotNull TrackNodeLocation location) {
		node2graph.computeIfAbsent(location, $ -> new HashSet<>()).add(graph);
	}

	public void onNodeAdded(@NotNull TrackGraph graph, @NotNull TrackNodeLocation location) {
		// only index graphs that actually belong to this manager instance.
		// the singleplayer (i.e., integrated server) mode, client-side packet processing creates client TrackGraph
		// instances with the same UUID as server graphs. and reference would be passed by as well as `increase` the RefCnt.
		// this results in cross-references between client and server i.e., polluting the node2graph map; massed up horribly.
		//
		// performance panelty for the check: O(1), yet still noticable in our tests: time cost slightly increases.
		if (trackNetworks.get(graph.id) != graph)
			return;
		do_add_node(graph, location);
	}

	protected void onNodeAdded(@NotNull TrackGraph graph, @NotNull Collection<TrackNodeLocation> locations) {
		locations.forEach(location -> do_add_node(graph, location));
	}

	public void do_remove_node(@NotNull TrackGraph graph, @NotNull TrackNodeLocation location) {
		final var S = node2graph.get(location);
		if (S == null) return;
		S.remove(graph);
		if (S.isEmpty())
			node2graph.remove(location);
	}

	public void onNodeRemoved(@NotNull TrackGraph graph, @NotNull TrackNodeLocation location) {
		// ditto.
		if (trackNetworks.get(graph.id) != graph)
			return;
		do_remove_node(graph, location);
	}

	public void onNodeRemoved(@NotNull TrackGraph graph, @NotNull Collection<TrackNodeLocation> locations) {
		locations.forEach(location -> onNodeRemoved(graph, location));
	}

	public void playerLogin(Player player) {
		if (player instanceof ServerPlayer serverPlayer) {
			loadTrackData(serverPlayer.getServer());
			for (TrackGraph g : trackNetworks.values()) {
				sync.sendFullGraphTo(g, serverPlayer);
			}

			List<UUID> ids = new ArrayList<>(signalEdgeGroups.size());
			List<EdgeGroupColor> colors = new ArrayList<>(signalEdgeGroups.size());
			for (SignalEdgeGroup group : signalEdgeGroups.values()) {
				ids.add(group.id);
				colors.add(group.color);
			}
			sync.sendEdgeGroups(ids, colors, serverPlayer);

			for (Train train : trains.values()) {
				CatnipServices.NETWORK.sendToClient(serverPlayer, new AddTrainPacket(train));
			}
		}
	}

	public void playerLogout(@SuppressWarnings("unused") Player player) {
	}

	public void levelLoaded(LevelAccessor level) {
		MinecraftServer server = level.getServer();
		if (server == null || server.overworld() != level)
			return;
		cleanUp();
		savedData = null;
		loadTrackData(server);
	}

	private void loadTrackData(MinecraftServer server) {
		if (savedData != null)
			return;
		savedData = RailwaySavedData.load(server);
		trains = savedData.getTrains();
		trackNetworks = savedData.getTrackNetworks();
		signalEdgeGroups = savedData.getSignalBlocks();
		movingTrains.addAll(trains.values());
		node2graph.clear();
		trackNetworks.values().forEach(graph -> onNodeAdded(graph, graph.getNodes()));
	}

	public void cleanUp() {
		trackNetworks = new HashMap<>();
		signalEdgeGroups = new HashMap<>();
		trains = new HashMap<>();
		sync = new TrackGraphSync();
		movingTrains = new LinkedList<>();
		waitingTrains = new LinkedList<>();
		node2graph.clear();
		GlobalTrainDisplayData.statusByDestination.clear();
	}

	public void markTracksDirty() {
		if (savedData != null)
			savedData.setDirty();
	}

	public void addTrain(Train train) {
		trains.put(train.id, train);
		movingTrains.add(train);
	}

	public void removeTrain(UUID id) {
		Train removed = trains.remove(id);
		if (removed == null)
			return;
		movingTrains.remove(removed);
		waitingTrains.remove(removed);
	}

	//

	public TrackGraph getOrCreateGraph(UUID graphID, int netId) {
		return trackNetworks.computeIfAbsent(graphID, uid -> {
			TrackGraph trackGraph = new TrackGraph(graphID);
			trackGraph.setNetId(netId);
			return trackGraph;
		});
	}

	public @NotNull TrackGraph putGraphWithDefaultGroup(@NotNull TrackGraph graph) {
		SignalEdgeGroup group = new SignalEdgeGroup(graph.id);
		signalEdgeGroups.put(graph.id, group.asFallback());
		sync.edgeGroupCreated(graph.id, group.color);
		putGraph(graph);
		return graph;
	}

	public void putGraph(@NotNull TrackGraph graph) {
		trackNetworks.put(graph.id, graph);
		onNodeAdded(graph, graph.getNodes());

		markTracksDirty();
	}

	public void removeGraphAndGroup(TrackGraph graph) {
		signalEdgeGroups.remove(graph.id);
		sync.edgeGroupRemoved(graph.id);
		removeGraph(graph);
	}

	public void removeGraph(@NotNull TrackGraph graph) {
		trackNetworks.remove(graph.id);
		onNodeRemoved(graph, graph.getNodes());

		markTracksDirty();
	}

	public void updateSplitGraph(@NotNull LevelAccessor level, @NotNull TrackGraph graph) {
		final var disconnected = graph.findDisconnectedGraphs(level, null);

		if (disconnected.isEmpty()) return;

		disconnected.forEach(this::putGraphWithDefaultGroup);

		sync.graphSplit(graph, disconnected);
		markTracksDirty();
	}

	public @Nullable TrackGraph getGraph(@SuppressWarnings("unused") LevelAccessor level, TrackNodeLocation vertex) {
		if (vertex == null)
			return null;

		final var set = node2graph.get(vertex);
		if (set != null && !set.isEmpty())
			return set.iterator().next();

		for (var railGraph : trackNetworks.values()) {
			if (railGraph.locateNode(vertex) != null) {
				onNodeAdded(railGraph, vertex);
				return railGraph;
			}
		}
		return null;
	}

	public @NotNull List<TrackGraph> getGraphs(@SuppressWarnings("unused") LevelAccessor level, @Nullable TrackNodeLocation vertex) {
		if (vertex == null)
			return Collections.emptyList();

		var set = node2graph.get(vertex);
		if (set != null && !set.isEmpty())
			// hit
			return new ArrayList<>(set);

		// not hit, calculate intersections
		return trackNetworks.values().stream()
			.filter(railGraph -> railGraph.locateNode(vertex) != null)
			.peek(railGraph -> onNodeAdded(railGraph, vertex))
			.toList();
	}

	public void tick(Level level) {
		if (level.dimension() != Level.OVERWORLD)
			return;

		for (SignalEdgeGroup group : signalEdgeGroups.values()) {
			group.trains.clear();
			group.reserved = null;
		}

		for (TrackGraph graph : trackNetworks.values()) {
			graph.tickPoints(true);
			graph.resolveIntersectingEdgeGroups(level);
		}

		tickTrains(level);

		for (TrackGraph graph : trackNetworks.values()) {
			graph.tickPoints(false);
		}

		GlobalTrainDisplayData.updateTick = level.getGameTime() % 100 == 0;
		if (GlobalTrainDisplayData.updateTick)
			GlobalTrainDisplayData.refresh();

//		if (AllKeys.isKeyDown(GLFW.GLFW_KEY_H) && AllKeys.altDown())
//			for (TrackGraph trackGraph : trackNetworks.values())
//				TrackGraphVisualizer.debugViewSignalData(trackGraph);
//		if (AllKeys.isKeyDown(GLFW.GLFW_KEY_J) && AllKeys.altDown())
//			for (TrackGraph trackGraph : trackNetworks.values())
//				TrackGraphVisualizer.debugViewNodes(trackGraph);
	}

	private void tickTrains(Level level) {
		// keeping two lists ensures a tick order starting at longest waiting
		for (Train train : waitingTrains)
			train.earlyTick(level);
		for (Train train : movingTrains)
			train.earlyTick(level);
		for (Train train : waitingTrains)
			train.tick(level);
		for (Train train : movingTrains)
			train.tick(level);

		for (Iterator<Train> iterator = waitingTrains.iterator(); iterator.hasNext(); ) {
			Train train = iterator.next();

			if (train.invalid) {
				iterator.remove();
				trains.remove(train.id);
				CatnipServices.NETWORK.sendToAllClients(new RemoveTrainPacket(train));
				continue;
			}

			if (train.navigation.waitingForSignal != null)
				continue;
			movingTrains.add(train);
			iterator.remove();
		}

		for (Iterator<Train> iterator = movingTrains.iterator(); iterator.hasNext(); ) {
			Train train = iterator.next();

			if (train.invalid) {
				iterator.remove();
				trains.remove(train.id);
				CatnipServices.NETWORK.sendToAllClients(new RemoveTrainPacket(train));
				continue;
			}

			if (train.navigation.waitingForSignal == null)
				continue;
			waitingTrains.add(train);
			iterator.remove();
		}
	}

	public void tickSignalOverlay() {
		if (!isTrackGraphDebugActive())
			for (TrackGraph trackGraph : trackNetworks.values())
				TrackGraphVisualizer.visualiseSignalEdgeGroups(trackGraph);
	}

	public void clientTick() {
		if (isTrackGraphDebugActive())
			for (TrackGraph trackGraph : trackNetworks.values())
				TrackGraphVisualizer.debugViewGraph(trackGraph, isTrackGraphDebugExtended());
	}

	private static boolean isTrackGraphDebugActive() {
		return KineticDebugger.isF3DebugModeActive() && AllConfigs.client().showTrackGraphOnF3.get();
	}

	private static boolean isTrackGraphDebugExtended() {
		return AllConfigs.client().showExtendedTrackGraphOnF3.get();
	}

	public GlobalRailwayManager sided(LevelAccessor level) {
		if (level != null && !level.isClientSide())
			return this;
		MutableObject<GlobalRailwayManager> m = new MutableObject<>();
		CatnipServices.PLATFORM.executeOnClientOnly(() -> () -> clientManager(m));
		return m.getValue();
	}

	@OnlyIn(Dist.CLIENT)
	private void clientManager(MutableObject<GlobalRailwayManager> m) {
		m.setValue(CreateClient.RAILWAYS);
	}
}
