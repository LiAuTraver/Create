package com.simibubi.create.infrastructure.gametest.tests;

import static com.simibubi.create.infrastructure.gametest.CreateGameTestHelper.TEN_SECONDS;
import static com.simibubi.create.infrastructure.gametest.CreateGameTestHelper.THIRTY_SECONDS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.GlobalRailwayManager;
import com.simibubi.create.content.trains.graph.DimensionPalette;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.infrastructure.gametest.CreateGameTestHelper;
import com.simibubi.create.infrastructure.gametest.GameTestGroup;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackShape;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.filter.RegexFilter;
import org.apache.logging.log4j.core.LoggerContext;
import org.slf4j.Logger;

/**
 * Track graph tests.
 * <p>
 * NOTE: The test suite takes about 1 hour to run (original) or ~15 min (patched).
 * <p>
 * The track graph data was recorded from the Steams 'n' Rails community public server with Create mod,
 * as well as relavent mods installed:
 * <li> Create 5 </li>
 * <li> Create: Steams 'n' Rails (introduces additional signals aka track nodes) </li>
 * <li> Nature's Spirit (track materials) </li>
 * <p>
 * The graph has nearly 3.5k track graphs with over 110k graph nodes, all within (-15k, -15k) to (15k, 15k).
 * (Few graphs seems to be corrupted and reside outside the world border.)
 * <p>
 * Original in-game lag:
 * <li>
 * placing 16 stright-line non-overlapping tracks as a new independent graph freezes the game about 2s.
 * no observable difference if placed outside any major graphs AABB;
 * no observable difference if it's extending existing graph.
 * </li>
 * <p>
 * Patched in-game lag predicted via test results: (ft. ms-jdk-21.0.11.10-hotspot, overall memory 32GB, i9-13900HX)
 * <li>
 * the time was reduced by half when the track is non-overlapping and graph independent,
 * if the placed track was 'far away' from existings AABB, there should be no observable lag.
 * TODO: find a way to test extending existing graph. it's logically sound as well as performance boosting, but needs testing.
 * </li>
 * <p>
 * subsequent tests indicate that, thanks to the node2graph cache, the delay would be half as original.
 * as for 110k node, the node2graph field would increase memory like 18.7MB(per side, hence 37.4MB in singleplayer)
 * (per: 32 bytes: outer node, 128 bytes: inner set, 10 bytes: bucket overhead) , which is almost negligible.
 * <p>
 * cross-referencing issues updated sol. original see commit 681fd6e8561729a80af0e9fcf5e6ef6d8985ae58.
 * the immediate commit solution should, in theory, slightly faster;
 * however in these tests the result does not seem to be consistent.
 * <p>
 * TODO: test beizer curve case; theoritically it should drop from O(n^2) to O(n) + time(AABB testing).
 */
@GameTestGroup(path = "track")
public class TestTrackGraph {

	private static final Logger L = LogUtils.getLogger();
	private static final GlobalRailwayManager M = Create.RAILWAYS;
	private static final int U = Block.UPDATE_ALL;
	private static final @SuppressWarnings("unused") Void V = denoiseLogs();

	private record LoadResult(List<TrackGraph> GL, int TN, Path SP) {
	}

	private static Void denoiseLogs() {
		try {
			final var context = (LoggerContext) LogManager.getContext(false);
			final var config = context.getConfiguration();
			// just deserilizing error, material failed to load (replaced by default andesite), and it wont bother test.
			final var filter = RegexFilter.createFilter(
				".*Failed to locate serialized track material.*",
				null,
				true,
				Filter.Result.DENY,
				Filter.Result.NEUTRAL
			);

			config.addFilter(filter);
			// maybe redundant vvv
			config.getRootLogger().addFilter(filter);

			// merely missing materials still ,as well as SnR blocks, won't bother either
			final var filter2 = RegexFilter.createFilter(
				".*Tried to load invalid item: 'Item must not be minecraft:air'.*",
				null,
				false,
				Filter.Result.DENY,
				Filter.Result.NEUTRAL
			);

			config.addFilter(filter2);
			config.getRootLogger().addFilter(filter2);

			context.updateLoggers();
		} catch (IllegalAccessException e) {
			L.warn("Failed to denoise logs for track graph tests", e);
		}
		return null;
	}

	private static Path findDataFile() {
		final var dir = System.getProperty("create.testDataDir");
		final var candidates = new ArrayList<Path>();

		if (dir != null && !dir.isBlank()) {
			candidates.add(Paths.get(dir, "create_tracks.dat"));
		}
		candidates.add(Paths.get("tmp", "create_tracks.dat"));

		return candidates.stream().filter(Files::exists).findFirst().orElse(null);
	}

	/**
	 * Locate and load the track graph dataset from file.
	 * <p>
	 * Returns null if no test dataset file is found -- safely skip if that case.
	 */
	private static LoadResult load(HolderLookup.Provider R) {
		final var path = findDataFile();
		if (path == null) {
			L.info("No track graph test data file found. Skipping data-file tests.");
			return null;
		}

		try {
			L.info("Loading track graph test dataset from {}...", path.toAbsolutePath());
			final var root = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
			final var data = root.contains("data") ? root.getCompound("data") : root;

			final var dimensions = DimensionPalette.read(data);
			final var graphs = new ArrayList<TrackGraph>();
			var totalNodes = 0;

			final var graphList = data.getList("RailGraphs", Tag.TAG_COMPOUND);
			for (var i = 0; i < graphList.size(); i++) {
				final var c = graphList.getCompound(i);
				final var graph = TrackGraph.read(c, R, dimensions);
				graphs.add(graph);
				totalNodes += graph.getNodes().size();
			}
			// 3517 track graphs with 111762 total nodes. w/ SnR, Nature's Spirit, from SnR S2 public server.
			L.info("Loaded {} track graphs with {} total nodes from {}", graphs.size(), totalNodes, path.getFileName());

			return new LoadResult(graphs, totalNodes, path);

		} catch (IOException e) {
			L.error("Failed to load track graph dataset from {}. relavent tests will be skipped", path, e);
			return null;
		}
	}

	private static final Vec3 N = new Vec3(0, 1, 0);

	private static void withGraphs(Runnable C, TrackGraph... LG) {
		Arrays.stream(LG).forEach(M::putGraph);
		C.run();
		// i dont think thers necessity to run it in reverse order, but anyways
		IntStream.iterate(LG.length - 1, i -> i >= 0, i -> i - 1)
			.mapToObj(i -> LG[i])
			.forEach(M::removeGraph);
	}

	private static void withGraphs(Runnable C, List<TrackGraph> LG) {
		LG.forEach(M::putGraph);
		C.run();
		// ditto
		LG.reversed().forEach(M::removeGraph);
	}


	private static void mutateTracks(ServerLevel SL) {
		mutateTracks(SL, new Vec3i(1, 1, 1), false);
	}

	private static void mutateTracks(ServerLevel SL, Vec3i C, boolean R90) {

		final var trackState = AllBlocks.TRACK.get().defaultBlockState()
			.setValue(TrackBlock.SHAPE, R90 ? TrackShape.ZO : TrackShape.XO);

		if (R90) {
			IntStream.range(0, 128).forEach(i -> SL.setBlock(new BlockPos(C.getX(), C.getY(), C.getZ() + i), trackState, U));
			IntStream.range(0, 128).forEach(i -> SL.setBlock(new BlockPos(C.getX(), C.getY(), C.getZ() + i), Blocks.STONE.defaultBlockState(), U));
		} else {
			// X-axis
			IntStream.range(0, 128).forEach(i -> SL.setBlock(new BlockPos(C.getX() + i, C.getY(), C.getZ()), trackState, U));
			IntStream.range(0, 128).forEach(i -> SL.setBlock(new BlockPos(C.getX() + i, C.getY(), C.getZ()), Blocks.STONE.defaultBlockState(), U));
		}
	}

	private static void mutateDiagTracks(ServerLevel SL, Vec3i C) {
		final var trackState = AllBlocks.TRACK.get().defaultBlockState()
			.setValue(TrackBlock.SHAPE, TrackShape.PD);

		IntStream.range(0, 128).forEach(i -> SL.setBlock(new BlockPos(C.getX() + i, C.getY(), C.getZ() + i), trackState, U));
		IntStream.range(0, 128).forEach(i -> SL.setBlock(new BlockPos(C.getX() + i, C.getY(), C.getZ() + i), Blocks.STONE.defaultBlockState(), U));
	}

	private static void mutateCrossTracks(ServerLevel SL, Vec3i C) {
		final var xoState = AllBlocks.TRACK.get().defaultBlockState()
			.setValue(TrackBlock.SHAPE, TrackShape.XO);
		final var zoState = AllBlocks.TRACK.get().defaultBlockState()
			.setValue(TrackBlock.SHAPE, TrackShape.ZO);

		int startSize = Create.RAILWAYS.trackNetworks.size();

		// X-axis track
		IntStream.range(0, 128).forEach(i -> SL.setBlock(new BlockPos(C.getX() + i, C.getY(), C.getZ() + 64), xoState, U));
		// Z-axis track, crossing the X axis at index 64
		IntStream.range(0, 128).forEach(i -> SL.setBlock(new BlockPos(C.getX() + 64, C.getY(), C.getZ() + i), zoState, U));

		IntStream.range(0, 128).forEach(i -> SL.setBlock(new BlockPos(C.getX() + i, C.getY(), C.getZ() + 64), Blocks.STONE.defaultBlockState(), U));
		IntStream.range(0, 128).forEach(i -> SL.setBlock(new BlockPos(C.getX() + 64, C.getY(), C.getZ() + i), Blocks.STONE.defaultBlockState(), U));

		int endSize = Create.RAILWAYS.trackNetworks.size();
		if (endSize != startSize) {
			L.error("Graph count mismatch! Start size: {}, End size: {}", startSize, endSize);
		}
	}

	private static double measure(int iteration, Runnable action) {
		final var startTime = System.nanoTime();
		IntStream.range(0, iteration).forEach($ -> action.run());
		final var endTime = System.nanoTime();
		return (endTime - startTime) / (double) iteration / 1000000.0; // milli
	}

	@SuppressWarnings("unused")
	@GameTest(template = "empty", timeoutTicks = TEN_SECONDS)
	public static void mergeSimple(CreateGameTestHelper H) {
		final var level = H.getLevel();

		final var S = new TrackGraph();
		final var T = new TrackGraph();

		final var loc1 = new TrackNodeLocation(new Vec3(100, 10, 100)).in(level);
		final var loc2 = new TrackNodeLocation(new Vec3(100, 10, 102)).in(level);


		final var node1 = new TrackNode(loc1, TrackGraph.nextNodeId(), N);
		final var node2 = new TrackNode(loc2, TrackGraph.nextNodeId(), N);

		S.addNode(node1);
		S.addNode(node2);

		withGraphs(() -> {
			// merge src into target
			S.transferAll(T);

			// source graph should be removed from node2graph cache for loc1 and loc2
			final var graphsForLoc1 = M.getGraphs(level, loc1);
			if (graphsForLoc1.contains(S))
				H.fail("node2graph cache still contains source graph for loc1 after transferAll!");

			final var graphsForLoc2 = M.getGraphs(level, loc2);
			if (graphsForLoc2.contains(S))
				H.fail("node2graph cache still contains source graph for loc2 after transferAll!");
		}, S, T);

		H.succeed();
	}

	/// node2graph cache test
	@SuppressWarnings("unused")
	@GameTest(template = "empty", timeoutTicks = TEN_SECONDS)
	public static void transfer(CreateGameTestHelper H) {
		final var level = H.getLevel();

		final var S = new TrackGraph();
		final var T = new TrackGraph();

		final var loc = new TrackNodeLocation(new Vec3(200, 10, 200)).in(level);
		final var node = new TrackNode(loc, TrackGraph.nextNodeId(), N);

		S.addNode(node);

		withGraphs(() -> {
			// move a node
			S.transfer(level, node, T);

			// check that node2graph cache should no longer references src for loc
			final var G = M.getGraphs(level, loc);
			if (G.contains(S))
				H.fail("node2graph cache still contains source graph for loc after transfer!");
		}, S, T);

		H.succeed();
	}

	// 8500ms~9000ms, X-axis, original
	// ~200ms, X-axis, patched
	@SuppressWarnings("unused")
	@GameTest(template = "empty", timeoutTicks = THIRTY_SECONDS)
	public static void outOfAABB(CreateGameTestHelper H) {
		final var LR = load(H.getLevel().registryAccess());
		if (LR == null) {
			// skip test
			H.succeed();
			return;
		}

		if (LR.GL.isEmpty()) H.fail("Loaded track dataset has 0 graphs...");
		if (LR.TN <= 0) H.fail("Loaded track dataset has 0 nodes");

		withGraphs(() -> {
			final var level = H.getLevel();
			// verify a sample
			var checked = 0;
			for (final var g : LR.GL) {
				for (final var loc : g.getNodes()) {
					final var found = M.getGraphs(level, loc);
					if (!found.contains(g))
						H.fail("getGraphs failed to return graph " + g.id + " for location " + loc);
					checked++;
					if (checked >= 100) break;
				}
				if (checked >= 100) break;
			}
			// the test graph inside the dataset is guranteed to be inside (-15k, -15k) to (15k, 15k) in XZ plane,
			// hence just test 128 placements outside of that range to avoid any accidental overlap.

			// warnup
			IntStream.range(0, 5).forEach($ -> mutateTracks(level, new Vec3i(50000, 128, 50000), false));

			// test
			var avgMsPerRun = measure(32, () -> mutateTracks(level, new Vec3i(50000, 128, 50000), false));

			L.info("[Out Of Major AABB] Average 128 consecutive track placement time: {} ms", avgMsPerRun);
		}, LR.GL);

		H.succeed();
	}

	// 8500ms~9000ms original
	// 4000ms~4500ms, X-axis, patched
	@SuppressWarnings("unused")
	@GameTest(template = "empty", timeoutTicks = THIRTY_SECONDS)
	public static void withinNonOverlapping(CreateGameTestHelper H) {
		final var LR = load(H.getLevel().registryAccess());
		if (LR == null) {
			// skip test
			H.succeed();
			return;
		}

		if (LR.GL.isEmpty()) H.fail("Loaded track dataset has 0 graphs...");
		if (LR.TN <= 0) H.fail("Loaded track dataset has 0 nodes");

		withGraphs(() -> {
			final var level = H.getLevel();

			// vvv this check isn't correct: there's literally no existing track in the test world, it's just graph.
			// the blockId always be air.
			//
			// assert the chosen coord does not overlap with existing, and INSIDE many existing track graph's AABB,
			// roughly covers the whole map
			//
			// assert no track inside (-2958, 62, 6879) ~ (-2830, 66, 7011).
			BlockPos.betweenClosedStream(-2958, 62, 6879, -2958 + 132, 66, 6879 + 132)
				.forEach(pos -> {
					var state = level.getBlockState(pos);
					var blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
					if (blockId.contains("track"))
						throw new IllegalStateException("Found track block at " + pos + ": " + blockId);
				});
			// warmup
			IntStream.range(0, 5).forEach($ -> mutateTracks(level, new Vec3i(-2960, 64, 6881), false));

			// test
			var avgMsPerRun = measure(32, () -> mutateTracks(level, new Vec3i(-2960, 64, 6881), false));
			L.info("[Within Major AABB, Non Overlapping] Average 128 consecutive track placement time: {} ms", avgMsPerRun);
		}, LR.GL);

		H.succeed();
	}

	// 17500ms, original
	// 8000ms~9500ms, patched
	@SuppressWarnings("unused")
	@GameTest(template = "empty", timeoutTicks = THIRTY_SECONDS)
	public static void withinNonOverlappingCrossing(CreateGameTestHelper H) {
		final var LR = load(H.getLevel().registryAccess());
		if (LR == null) {
			H.succeed();
			return;
		}

		withGraphs(() -> {
			final var level = H.getLevel();
			// warmup
			IntStream.range(0, 5).forEach($ -> mutateCrossTracks(level, new Vec3i(-2960, 64, 6881)));

			// test
			var avgMsPerRun = measure(32, () -> mutateCrossTracks(level, new Vec3i(-2960, 64, 6881)));
			L.info("[Within Major AABB, Non Overlapping w/ Crossing] Average 128 consecutive crossing track placement time: {} ms", avgMsPerRun);
		}, LR.GL);

		H.succeed();
	}

	// 8825ms, original
	// 3800ms~5000ms, patched
	@SuppressWarnings("unused")
	@GameTest(template = "empty", timeoutTicks = THIRTY_SECONDS)
	public static void withinNonOverlappingDiagonal(CreateGameTestHelper H) {
		final var LR = load(H.getLevel().registryAccess());
		if (LR == null) {
			H.succeed();
			return;
		}

		withGraphs(() -> {
			final var level = H.getLevel();
			// warmup
			IntStream.range(0, 5).forEach($ -> mutateDiagTracks(level, new Vec3i(-2960, 64, 6881)));

			// test
			var avgMsPerRun = measure(32, () -> mutateDiagTracks(level, new Vec3i(-2960, 64, 6881)));
			L.info("[Within Major AABB, Non Overlapping, w/ Diag] Average 128 consecutive diagonal track placement time: {} ms", avgMsPerRun);
		}, LR.GL);

		H.succeed();
	}

	// 34500ms, original
	// 1300ms~2600ms, patched
	@SuppressWarnings("unused")
	@GameTest(template = "empty", timeoutTicks = TEN_SECONDS)
	public static void isolatedWithLarge(CreateGameTestHelper H) {
		final var level = H.getLevel();

		// setup synthetic graph...
		final var largeGraph = new TrackGraph();
		IntStream.range(0, 100000)
			.mapToObj(i -> new TrackNodeLocation(new Vec3(1000 + i * 2, 10, 1000)).in(level))
			.map(loc -> new TrackNode(loc, TrackGraph.nextNodeId(), N))
			.forEach(largeGraph::addNode);

		withGraphs(() -> {
			// warmup for JIT
			IntStream.range(0, 5).mapToObj(r -> level).forEach(TestTrackGraph::mutateTracks);

			var avgMsPerRun = measure(32, () -> IntStream.range(0, 128).mapToObj(r -> level).forEach(TestTrackGraph::mutateTracks));

			L.info("[Isolated With Large Graph] Average 128 consecutive track placement time over {} runs: {} ms", 32, avgMsPerRun);
		}, largeGraph);

		H.succeed();
	}

}
