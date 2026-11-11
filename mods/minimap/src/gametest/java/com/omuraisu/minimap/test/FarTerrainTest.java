package com.omuraisu.minimap.test;

import static com.omuraisu.minimap.test.MapTests.*;
import static com.omuraisu.testkit.Asserts.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.mojang.blaze3d.platform.InputConstants;
import com.omuraisu.minimap.MapPriority;
import com.omuraisu.minimap.Minimap;
import com.omuraisu.minimap.TerrainHave;
import com.omuraisu.minimap.TerrainRegions;
import com.omuraisu.minimap.TerrainServer;
import com.omuraisu.minimap.TerrainView;
import com.omuraisu.minimap.TerrainWorkers;
import com.omuraisu.minimap.client.MinimapClient;
import com.omuraisu.minimap.mixin.ChunkMapInvoker;
import com.omuraisu.minimap.mixin.ChunkTaskDispatcherAccessor;
import com.omuraisu.testkit.Scenario;
import com.omuraisu.testkit.Screenshots;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkTaskPriorityQueue;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

// 遠くの地形: 設定・セーブから読む・生成の順番・マップ距離・開き直したときに読み直さない・自然な地形
public class FarTerrainTest implements Scenario {
	@Override
	public String name() {
		return "terrain";
	}

	@Override
	public void run(ClientGameTestContext context) {
		watchFullLoads();
		testWorkerFailures(context);
		testConfig(context);

		TestWorldSave save;
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			save = singleplayer.getWorldSave();
			buildFloor(singleplayer);
			teleport(context, singleplayer, PLAYER_X, PLAYER_Z);

			testFarTerrain(context, singleplayer);
			testGenerationOrder(singleplayer);
			testMapDistance(context, singleplayer);
		}

		// 前に届いた遠くの地形は、クライアントのファイルから出る。メモリにあったチャンクの色は版が残らないので、ここで読み直される
		try (TestSingleplayerContext singleplayer = save.open()) {
			singleplayer.getConnection().waitForChunksRender();
			waitForQuietTerrain(context);
		}
		// もう一度開き直すと、セーブが変わっていないチャンクは版が同じなので、サーバーは読み直さない
		try (TestSingleplayerContext singleplayer = save.open()) {
			singleplayer.getConnection().waitForChunksRender();
			waitForQuietTerrain(context);
			int known = context.computeOnClient(client -> MinimapClient.FAR_TERRAIN.count(OVERWORLD));
			int[] reads = singleplayer.getServer().computeOnServer(server -> TerrainServer.readCounts());
			System.out.printf("far-terrain-unchanged: known %d, read %d (vanilla %d)%n", known, reads[0], reads[1]);
			if (known < 1000) {
				throw new AssertionError("far terrain saved on the client should be remembered after reopening the world but only " + known + " chunks were");
			}
			if (reads[0] * 4 > known) {
				throw new AssertionError("chunks that did not change should not be read again: read " + reads[0] + " of " + known);
			}
		}

		testNaturalTerrain(context);
	}

	// 自前のスレッドの仕事が何で失敗しても、後始末はメインスレッドの列に入る。後始末が抜けると、読み込みの予算や待っているチャンクの印が戻らない。
	// 本物の読み込みで Error を起こすのは難しいので、TerrainWorkers を 1 つ作って確かめる
	private static void testWorkerFailures(ClientGameTestContext context) {
		TerrainWorkers workers = new TerrainWorkers();
		// 後始末はこのスレッドの drain で動く
		Map<String, String> finished = new HashMap<>();
		try {
			workers.run(() -> "colors", (result, failure) -> finished.put("returned", result + " / " + failure));
			workers.run(() -> {
				throw new IllegalStateException("broken chunk");
			}, (result, failure) -> finished.put("runtime", result + " / " + failure.getClass().getSimpleName()));
			workers.run(() -> {
				throw new StackOverflowError();
			}, (result, failure) -> finished.put("error", result + " / " + failure.getClass().getSimpleName()));
			workers.run(() -> {
				throw new NoSuchMethodError("changed by another mod");
			}, (result, failure) -> finished.put("linkage", result + " / " + failure.getClass().getSimpleName()));
			for (int i = 0; finished.size() < 4; i++) {
				if (i >= 200) {
					throw new AssertionError("finishes of the terrain workers should all arrive but only " + finished.keySet() + " did");
				}
				context.waitTick();
				workers.drain();
			}
		} finally {
			workers.close();
		}
		// 止めた後に渡した仕事も、動かせなかったことが後始末に届く
		workers.run(() -> "late", (result, failure) -> finished.put("closed", result + " / " + failure.getClass().getSimpleName()));
		workers.drain();
		assertEquals("finish of a work that returned", "colors / null", finished.get("returned"));
		assertEquals("finish of a work that threw a runtime exception", "null / IllegalStateException", finished.get("runtime"));
		assertEquals("finish of a work that threw an error", "null / StackOverflowError", finished.get("error"));
		assertEquals("finish of a work that threw a linkage error", "null / NoSuchMethodError", finished.get("linkage"));
		assertEquals("finish of a work given after closing", "null / RejectedExecutionException", finished.get("closed"));
	}

	// マップ距離は config/minimap.txt。なければ既定の値で作り、範囲の外や壊れた値でも落ちない
	private static void testConfig(ClientGameTestContext context) {
		try {
			Files.deleteIfExists(MinimapClient.CONFIG);
			context.runOnClient(client -> MinimapClient.load());
			assertFileEquals(MinimapClient.CONFIG, "map_distance=" + MinimapClient.DEFAULT_MAP_DISTANCE + "\n");
			assertMapDistance(context, MinimapClient.DEFAULT_MAP_DISTANCE);

			setMapDistance(context, 999);
			assertMapDistance(context, TerrainServer.RADIUS);
			setMapDistance(context, -5);
			assertMapDistance(context, 0);
			Files.writeString(MinimapClient.CONFIG, "map_distance=abc\n");
			context.runOnClient(client -> MinimapClient.load());
			assertMapDistance(context, MinimapClient.DEFAULT_MAP_DISTANCE);
			setMapDistance(context, MinimapClient.DEFAULT_MAP_DISTANCE);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void assertMapDistance(ClientGameTestContext context, int expected) {
		assertEquals("map distance", expected, (int) context.computeOnClient(client -> MinimapClient.mapDistance()));
	}

	// 描画距離の外も、サーバーからマップ画面に出す。生成済みのチャンクはワールドに読み込まずにセーブから読み、
	// 未生成のチャンクは地物を置くところ（FEATURES）まで生成させる
	private static void testFarTerrain(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		teleport(context, singleplayer, PLAYER_X, PLAYER_Z);
		int renderDistance = context.computeOnClient(client -> client.options.getEffectiveRenderDistance());
		ChunkPos home = new ChunkPos(PLAYER_X >> 4, PLAYER_Z >> 4);
		// 目印を置いたチャンクと、何もないチャンク。どちらもクライアントは持っていない
		ChunkPos marked = new ChunkPos(home.x() + renderDistance + 8, home.z());
		ChunkPos plain = new ChunkPos(home.x() - renderDistance - 12, home.z() + 6);
		// 手元の地形のテクスチャの角。読み込まれていないので透明になり、下の遠くの地形が見える
		ChunkPos corner = new ChunkPos(home.x() + renderDistance + 1, home.z() + renderDistance);
		// セーブにだけある目印のチャンク。プレイヤーの札は描画距離の外にも弱く及び、その間はメモリに残るので、それより遠くに置く
		ChunkPos stored = new ChunkPos(home.x() - renderDistance - 24, home.z() - 4);
		// 北の端の起伏は、ひとつ北のチャンクと比べて決める。南のチャンクの北の端の 1 列目は北より高く、2 列目は同じ、3 列目は北より低い
		ChunkPos north = new ChunkPos(home.x() - renderDistance - 24, home.z() + 4);
		ChunkPos south = new ChunkPos(north.x(), north.z() + 1);
		singleplayer.getServer().runOnServer(server -> {
			ServerLevel level = server.overworld();
			fill(level, new BlockPos(marked.getMinBlockX(), FLOOR_Y, marked.getMinBlockZ()),
					new BlockPos(marked.getMaxBlockX(), FLOOR_Y, marked.getMaxBlockZ()), Blocks.GOLD_BLOCK);
			fill(level, new BlockPos(stored.getMinBlockX(), FLOOR_Y, stored.getMinBlockZ()),
					new BlockPos(stored.getMaxBlockX(), FLOOR_Y, stored.getMaxBlockZ()), Blocks.DIAMOND_BLOCK);
			raise(level, south.getMinBlockX(), south.getMinBlockZ());
			raise(level, north.getMinBlockX() + 2, north.getMaxBlockZ());
		});

		// 前に地図を開いたときの色が残っていても、ブロックが変わったチャンクは版が変わるので、今の色が届き直す
		teleport(context, singleplayer, marked.getMiddleBlockX(), marked.getMiddleBlockZ());
		context.waitFor(client -> clientHasChunk(client, marked));
		teleport(context, singleplayer, PLAYER_X, PLAYER_Z);
		context.waitFor(client -> !clientHasChunk(client, marked));
		for (ChunkPos chunk : List.of(marked, plain, corner, stored, north, south)) {
			if (context.computeOnClient(client -> clientHasChunk(client, chunk))) {
				throw new AssertionError("far chunk " + chunk + " should be outside the render distance " + renderDistance);
			}
		}
		waitUntilStored(context, singleplayer, stored, north, south);

		toggleMap(context);
		zoomAtCenter(context, -3);
		assertScale(context, 0.25);
		ScreenView view = new ScreenView(854, 480, 0.25, PLAYER_X + 0.5, PLAYER_Z + 0.5);
		waitForFarTerrain(context, marked, plain, corner, stored, north, south);
		waitForFarColor(context, marked, MapColor.GOLD);
		waitForFarColor(context, stored, MapColor.DIAMOND);
		// セーブから読んだ色の版は、リージョンファイルにあるチャンクを書いた時刻（正の数）。
		// メモリにあったときの色が先に届いていても、書き終えた後のセーブから読み直される
		context.waitFor(client -> MinimapClient.FAR_TERRAIN.stamp(OVERWORLD, stored.x(), stored.z()) > 0, 400);
		view.assertBlock(context, "far-terrain-marked", marked.getMiddleBlockX(), marked.getMiddleBlockZ(), MapColor.GOLD);
		view.assertBlock(context, "far-terrain-plain", plain.getMiddleBlockX(), plain.getMiddleBlockZ(), MapColor.GRASS);
		view.assertBlock(context, "far-terrain-corner", corner.getMiddleBlockX(), corner.getMiddleBlockZ(), MapColor.GRASS);
		view.assertBlock(context, "far-terrain-stored", stored.getMiddleBlockX(), stored.getMiddleBlockZ(), MapColor.DIAMOND);
		for (ChunkPos chunk : List.of(stored, north, south, plain)) {
			assertNotLoadedByServer(singleplayer, chunk);
		}
		byte[] southColors = context.computeOnClient(client -> MinimapClient.FAR_TERRAIN.colors(OVERWORLD, south.x(), south.z()));
		assertEquals("brightness of the north edge higher than the north chunk", MapColor.Brightness.HIGH.id, southColors[0] & 3);
		assertEquals("brightness of the north edge as high as the north chunk", MapColor.Brightness.NORMAL.id, southColors[1] & 3);
		assertEquals("brightness of the north edge lower than the north chunk", MapColor.Brightness.LOW.id, southColors[2] & 3);
		// 平らなワールドは地表の高さを見積もれないので、下書きは届かない
		assertEquals("preview in the flat world", 0, (int) context.computeOnClient(client -> MinimapClient.FAR_TERRAIN.preview(OVERWORLD, plain.getMiddleBlockX(), plain.getMiddleBlockZ())));

		// 開いている間にブロックが変わったチャンクは、色が届き直す
		singleplayer.getServer().runOnServer(server -> {
			ServerLevel level = server.overworld();
			int top = level.getChunk(plain.x(), plain.z()).getHeight(Heightmap.Types.WORLD_SURFACE, plain.getMiddleBlockX(), plain.getMiddleBlockZ());
			level.setBlockAndUpdate(new BlockPos(plain.getMiddleBlockX(), top + 1, plain.getMiddleBlockZ()), Blocks.EMERALD_BLOCK.defaultBlockState());
		});
		waitForFarColor(context, plain, MapColor.EMERALD);
		view.assertBlock(context, "far-terrain-changed", plain.getMiddleBlockX(), plain.getMiddleBlockZ(), MapColor.EMERALD);

		// 手元のテクスチャとのつなぎ目にすき間がない
		int live = (renderDistance + 1) * 16;
		List<ChunkPos> around = new ArrayList<>();
		for (int x = (PLAYER_X - live - 16) >> 4; x <= (PLAYER_X + live + 16) >> 4; x++) {
			for (int z = (PLAYER_Z - live - 16) >> 4; z <= (PLAYER_Z + live + 16) >> 4; z++) {
				around.add(new ChunkPos(x, z));
			}
		}
		waitForFarTerrain(context, around.toArray(ChunkPos[]::new));
		int left = view.x(PLAYER_X - live) - 3;
		int right = view.x(PLAYER_X + live) + 3;
		int top = view.y(PLAYER_Z - live) - 3;
		int bottom = view.y(PLAYER_Z + live) + 3;
		int gaps = Screenshots.read(context.takeScreenshot("far-terrain-seam"), image -> {
			int count = 0;
			for (int y = top; y <= bottom; y++) {
				for (int x = left; x <= right; x++) {
					if (image.getPixel(x, y) == EMPTY_COLOR) {
						count++;
					}
				}
			}
			return count;
		});
		if (gaps != 0) {
			throw new AssertionError("far terrain should fill around the loaded terrain but " + gaps + " pixels were empty");
		}

		// 開き直すと、覚えている色からすぐ描く
		int received = context.computeOnClient(client -> MinimapClient.FAR_TERRAIN.received());
		toggleMap(context);
		toggleMap(context);
		moveCursorAway(context);
		context.waitTicks(15);
		view.assertBlock(context, "far-terrain-reopened", marked.getMiddleBlockX(), marked.getMiddleBlockZ(), MapColor.GOLD);
		byte[] markedColors = context.computeOnClient(client -> MinimapClient.FAR_TERRAIN.colors(OVERWORLD, marked.x(), marked.z()));
		if (markedColors == null) {
			throw new AssertionError("marked chunk should stay remembered after reopening the map");
		}
		if (context.computeOnClient(client -> MinimapClient.FAR_TERRAIN.received()) < received) {
			throw new AssertionError("received count should not go back");
		}

		// 読み込める範囲の外は背景のまま。範囲の端に近いところから読み込む
		context.getInput().setCursorPos(840, 240);
		context.waitTick();
		context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTick();
		context.getInput().setCursorPos(40, 240);
		context.waitTick();
		context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTick();
		moveCursorAway(context);
		ScreenView panned = view.panned(-800, 0);
		ChunkPos edge = new ChunkPos(home.x() + TerrainServer.RADIUS - 24, home.z());
		ChunkPos outside = new ChunkPos(home.x() + TerrainServer.RADIUS + 40, home.z());
		waitForFarTerrain(context, edge);
		panned.assertBlock(context, "far-terrain-edge", edge.getMiddleBlockX(), edge.getMiddleBlockZ(), MapColor.GRASS);
		assertPixel(context, "far-terrain-outside", panned.x(outside.getMiddleBlockX()), panned.y(outside.getMiddleBlockZ()), EMPTY_COLOR);
		toggleMap(context);

		// 地図のために生成したチャンクは FULL まで進めず、地物を置いた段階で保存する
		assertNotLoadedByServer(singleplayer, edge);
		ChunkStatus edgeStatus = heldStatus(singleplayer, edge);
		if (edgeStatus == null) {
			edgeStatus = storedStatus(singleplayer, edge);
		}
		assertEquals("status of the chunk generated for the map", ChunkStatus.FEATURES, edgeStatus);

		// 範囲の外を直接見ても、生成も読み込みもしない
		ChunkPos beyond = new ChunkPos(home.x() + TerrainServer.RADIUS + 40, home.z() + 70);
		context.runOnClient(client -> {
			ClientPlayNetworking.send(new TerrainHave(OVERWORLD, TerrainRegions.coordinate(beyond.x()), TerrainRegions.coordinate(beyond.z()),
					new int[TerrainRegions.COUNT]));
			ClientPlayNetworking.send(new TerrainView(OVERWORLD, beyond.x(), beyond.z(), beyond.x(), beyond.z(), beyond.x(), beyond.z(), false));
		});
		context.waitTicks(40);
		if (heldStatus(singleplayer, beyond) != null || storedStatus(singleplayer, beyond) != null) {
			throw new AssertionError("chunks beyond the radius should not be generated");
		}

		toggleMap(context);
		zoomAtCenter(context, 3);
		assertScale(context, 2);
		toggleMap(context);
	}

	// 生成の列では、地図だけのチャンクの仕事がプレイヤーの周りの仕事の後に回る。誰かが読み込むようになれば（FULL のレベル）前に戻り、
	// 地図の札の届かない遠くのチャンクと、地図のために生成していないときは vanilla と同じくレベルの順
	private static void testGenerationOrder(TestSingleplayerContext singleplayer) {
		singleplayer.getServer().runOnServer(server -> {
			ServerLevel level = server.overworld();
			ChunkPos player = server.getPlayerList().getPlayers().getFirst().chunkPosition();
			ChunkPos target = new ChunkPos(player.x() + 120, player.z());
			ChunkPos neighbor = new ChunkPos(target.x() + 1, target.z());
			ChunkPos border = new ChunkPos(player.x() + 2, player.z());
			ChunkPos other = new ChunkPos(player.x() - 120, player.z());
			int features = ChunkLevel.byStatus(ChunkStatus.FEATURES);
			int full = ChunkLevel.byStatus(FullChunkStatus.FULL);

			MapPriority priority = new MapPriority();
			LongSet targets = new LongOpenHashSet();
			targets.add(target.pack());
			priority.update(level, targets);
			ChunkTaskPriorityQueue queue = new ChunkTaskPriorityQueue("test");
			((MapPriority.Queue) queue).minimap$setPriority(priority);
			List<String> order = new ArrayList<>();
			submit(queue, () -> order.add("map"), target, features);
			submit(queue, () -> order.add("map neighbor"), neighbor, features + 1);
			submit(queue, () -> order.add("player"), border, features + 2);
			submit(queue, () -> order.add("other"), other, features + 3);
			submit(queue, () -> order.add("map again"), target, features);
			drain(queue);
			assertEquals("order with map-only chunks", List.of("player", "other", "map", "map again", "map neighbor"), order);

			order.clear();
			submit(queue, () -> order.add("map"), target, features);
			submit(queue, () -> order.add("player"), border, features + 2);
			resort(queue, features, target, full);
			drain(queue);
			assertEquals("order after the map chunk is loaded by someone", List.of("map", "player"), order);

			order.clear();
			priority.update(level, new LongOpenHashSet());
			submit(queue, () -> order.add("player"), border, features + 2);
			submit(queue, () -> order.add("map"), target, features);
			drain(queue);
			assertEquals("order without generation for the map", List.of("map", "player"), order);
		});
	}

	private static void submit(ChunkTaskPriorityQueue queue, Runnable task, ChunkPos chunk, int level) {
		invoke(queue, "submit", new Class<?>[] {Runnable.class, long.class, int.class}, task, chunk.pack(), level);
	}

	private static void resort(ChunkTaskPriorityQueue queue, int oldLevel, ChunkPos chunk, int newLevel) {
		invoke(queue, "resortChunkTasks", new Class<?>[] {int.class, ChunkPos.class, int.class}, oldLevel, chunk, newLevel);
	}

	// 列の操作は protected なので、vanilla の ChunkTaskDispatcher の代わりに呼ぶ
	private static void invoke(ChunkTaskPriorityQueue queue, String name, Class<?>[] types, Object... args) {
		try {
			Method method = ChunkTaskPriorityQueue.class.getDeclaredMethod(name, types);
			method.setAccessible(true);
			method.invoke(queue, args);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void drain(ChunkTaskPriorityQueue queue) {
		for (ChunkTaskPriorityQueue.TasksForChunk tasks = queue.pop(); tasks != null; tasks = queue.pop()) {
			tasks.tasks().forEach(Runnable::run);
		}
	}

	// マップ画面を開いていなくても、プレイヤーの周りのマップ距離の分を頼んでおく。描画距離の外は FEATURES まで生成し、ワールドには読み込まない。
	// 家の周りの地図のテストに重ならないよう、まだ地図に出したことのない南の遠くで確かめる
	private static void testMapDistance(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		int renderDistance = context.computeOnClient(client -> client.options.getEffectiveRenderDistance());
		int distance = MinimapClient.mapDistance();
		ChunkPos home = new ChunkPos(PLAYER_X >> 4, PLAYER_Z >> 4);

		ChunkPos area = new ChunkPos(home.x(), home.z() + 400);
		// プレイヤーの札で生成の進むチャンクより遠く、マップ距離の中
		int reach = Math.min(distance - 2, renderDistance + ChunkLevel.RADIUS_AROUND_FULL_CHUNK + 2);
		ChunkPos near = new ChunkPos(area.x() + renderDistance + 2, area.z() - 1);
		ChunkPos inside = new ChunkPos(area.x() - reach, area.z() + 3);
		ChunkPos beyond = new ChunkPos(area.x() + distance + 6, area.z());
		long start = context.computeOnClient(client -> client.level.getGameTime());
		teleport(context, singleplayer, area.getMiddleBlockX(), area.getMiddleBlockZ());
		assertMapOpen(context, false);
		// 地図のために生成している間は、生成の列に地図の順番が付いている
		boolean ordered = false;
		for (int i = 0; i < 400 && !ordered; i++) {
			ordered = singleplayer.getServer().computeOnServer(server -> {
				var dispatcher = ((ChunkMapInvoker) server.overworld().getChunkSource().chunkMap).minimap$worldgenTaskDispatcher();
				MapPriority priority = ((MapPriority.Queue) ((ChunkTaskDispatcherAccessor) dispatcher).minimap$queue()).minimap$priority();
				return priority != null && priority.hasTargets();
			});
			context.waitTick();
		}
		if (!ordered) {
			throw new AssertionError("generation for the map should be ordered after the player's chunks");
		}
		waitForFarTerrain(context, near, inside);
		long elapsed = context.computeOnClient(client -> client.level.getGameTime()) - start;
		System.out.printf("map-distance: %d chunks within %d ticks while the map was closed%n",
				context.computeOnClient(client -> MinimapClient.FAR_TERRAIN.count(OVERWORLD)), elapsed);
		assertMapOpen(context, false);
		assertNotLoadedByServer(singleplayer, inside);
		ChunkStatus insideStatus = heldStatus(singleplayer, inside);
		if (insideStatus == null) {
			insideStatus = storedStatus(singleplayer, inside);
		}
		assertEquals("status of the chunk generated for the map distance", ChunkStatus.FEATURES, insideStatus);
		// マップ距離の外は頼まない
		context.waitTicks(100);
		if (context.computeOnClient(client -> MinimapClient.FAR_TERRAIN.colors(OVERWORLD, beyond.x(), beyond.z())) != null) {
			throw new AssertionError("chunk " + beyond + " beyond the map distance " + distance + " should not be requested");
		}

		// 無効の間は頼まず、生成の列も vanilla のまま
		context.runOnClient(client -> Minimap.setActive(false));
		ChunkPos quiet = new ChunkPos(home.x(), home.z() + 500);
		ChunkPos quietNear = new ChunkPos(quiet.x() + renderDistance + 2, quiet.z());
		teleport(context, singleplayer, quiet.getMiddleBlockX(), quiet.getMiddleBlockZ());
		context.waitTicks(100);
		boolean cleared = singleplayer.getServer().computeOnServer(server -> {
			var dispatcher = ((ChunkMapInvoker) server.overworld().getChunkSource().chunkMap).minimap$worldgenTaskDispatcher();
			MapPriority priority = ((MapPriority.Queue) ((ChunkTaskDispatcherAccessor) dispatcher).minimap$queue()).minimap$priority();
			return priority == null || !priority.hasTargets();
		});
		boolean quietArrived = context.computeOnClient(client -> MinimapClient.FAR_TERRAIN.colors(OVERWORLD, quietNear.x(), quietNear.z()) != null);
		context.runOnClient(client -> Minimap.setActive(true));
		if (!cleared) {
			throw new AssertionError("generation order should be vanilla while the minimap is inactive");
		}
		if (quietArrived) {
			throw new AssertionError("far terrain should not be requested while the minimap is inactive");
		}
		waitForFarTerrain(context, quietNear);

		// マップ距離が 0 なら開いたときだけ頼む
		setMapDistance(context, 0);
		ChunkPos off = new ChunkPos(home.x(), home.z() + 600);
		ChunkPos offNear = new ChunkPos(off.x() + renderDistance + 2, off.z());
		teleport(context, singleplayer, off.getMiddleBlockX(), off.getMiddleBlockZ());
		context.waitTicks(100);
		boolean offArrived = context.computeOnClient(client -> MinimapClient.FAR_TERRAIN.colors(OVERWORLD, offNear.x(), offNear.z()) != null);
		setMapDistance(context, distance);
		if (offArrived) {
			throw new AssertionError("far terrain should not be requested in the background when the map distance is 0");
		}
		teleport(context, singleplayer, PLAYER_X, PLAYER_Z);
	}

	// 地表に 1 つ積む。Level.getHeight は読み込まれていないチャンクでは一番下を返すので、チャンクから高さを取る
	private static void raise(ServerLevel level, int x, int z) {
		int top = level.getChunk(x >> 4, z >> 4).getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
		level.setBlockAndUpdate(new BlockPos(x, top + 1, z), Blocks.STONE.defaultBlockState());
	}

	// 置いたチャンクがワールドから外れ、保存し終えるのを待つ（それまではサーバーがメモリの方から送る）。
	// ここから後にワールドに読み込まれたチャンクを数え直す
	private static void waitUntilStored(ClientGameTestContext context, TestSingleplayerContext singleplayer, ChunkPos... chunks) {
		for (int i = 0; ; i++) {
			String remaining = singleplayer.getServer().computeOnServer(server -> {
				ChunkMapInvoker chunkMap = (ChunkMapInvoker) server.overworld().getChunkSource().chunkMap;
				List<String> held = new ArrayList<>();
				for (ChunkPos chunk : chunks) {
					ChunkHolder holder = chunkMap.minimap$getVisibleChunkIfPresent(chunk.pack());
					if (holder != null || chunkMap.minimap$pendingUnloads().containsKey(chunk.pack())) {
						held.add(chunk + " level=" + (holder == null ? "unloading" : holder.getTicketLevel()));
					}
				}
				return held.isEmpty() ? null : held + " player=" + server.getPlayerList().getPlayers().getFirst().chunkPosition();
			});
			if (remaining == null) {
				break;
			}
			if (i >= 1200) {
				throw new AssertionError("chunks should be saved and unloaded: " + remaining);
			}
			context.waitTick();
		}
		for (ChunkPos chunk : chunks) {
			assertEquals("saved status of " + chunk, ChunkStatus.FULL, storedStatus(singleplayer, chunk));
		}
		singleplayer.getServer().runOnServer(server -> FULL_LOADS.clear());
	}

	// メモリにあるチャンクの状態。なければ null。
	// テストの内蔵サーバーは tick の合間に時間が余らないので、外れたチャンクの保存は溜まった分の 2000 を超えた分しか進まない
	private static ChunkStatus heldStatus(TestSingleplayerContext singleplayer, ChunkPos chunk) {
		return singleplayer.getServer().computeOnServer(server -> {
			ChunkMapInvoker chunkMap = (ChunkMapInvoker) server.overworld().getChunkSource().chunkMap;
			ChunkHolder holder = chunkMap.minimap$getVisibleChunkIfPresent(chunk.pack());
			if (holder == null) {
				holder = chunkMap.minimap$pendingUnloads().get(chunk.pack());
			}
			return holder == null || holder.getLatestChunk() == null ? null : holder.getLatestChunk().getPersistedStatus();
		});
	}

	// セーブに書かれた状態。なければ null
	private static ChunkStatus storedStatus(TestSingleplayerContext singleplayer, ChunkPos chunk) {
		CompletableFuture<Optional<CompoundTag>> tag = singleplayer.getServer().computeOnServer(server -> server.overworld().getChunkSource().chunkMap.read(chunk));
		return tag.join().map(SerializableChunkData::getChunkStatusFromTag).orElse(null);
	}

	// 地図のためにワールドへ読み込んでいない（読み込むとエンティティや光の計算まで走る）
	private static void assertNotLoadedByServer(TestSingleplayerContext singleplayer, ChunkPos chunk) {
		boolean loaded = singleplayer.getServer().computeOnServer(server -> FULL_LOADS.contains(chunk.pack())
				|| server.overworld().getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null);
		if (loaded) {
			throw new AssertionError("chunk " + chunk + " should not be loaded into the world for the map");
		}
	}

	// ClientLevel.hasChunk は常に true を返す
	private static boolean clientHasChunk(Minecraft client, ChunkPos chunk) {
		return client.level.getChunkSource().getChunk(chunk.x(), chunk.z(), ChunkStatus.FULL, false) != null;
	}

	// チャンクの真ん中の列が color になるまで待つ（前に届いた色が残っていることがある）
	private static void waitForFarColor(ClientGameTestContext context, ChunkPos chunk, MapColor color) {
		context.waitFor(client -> {
			byte[] colors = MinimapClient.FAR_TERRAIN.colors(client.level.dimension().identifier(), chunk.x(), chunk.z());
			return colors != null && Byte.toUnsignedInt(colors[8 * 16 + 8]) >> 2 == color.id;
		}, 2400);
		context.waitTicks(15);
	}

	// 起伏や水の見え方はスクリーンショットで確かめる
	private static void testNaturalTerrain(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder()
				.setUseConsistentSettings(false)
				.adjustSettings(creator -> creator.setSeed("minimap"))
				.create()) {
			singleplayer.getConnection().waitForChunksRender();
			context.getInput().resizeWindow(1920, 1080);
			context.waitTicks(MAP_REFRESH_TICKS);
			frame(context, "natural-terrain", 1920, 1080);

			toggleMap(context);
			moveCursorAway(context);
			context.waitTicks(MAP_REFRESH_TICKS);
			context.takeScreenshot("natural-map");

			// 日本語でも操作の説明が出る
			String english = context.computeOnClient(client -> client.options.languageCode);
			setLanguage(context, "ja_jp");
			String hint = context.computeOnClient(client -> Component.translatable("gui.minimap.hint_remove").getString());
			if (!hint.contains("左クリック")) {
				throw new AssertionError("hint should be in Japanese but was " + hint);
			}
			assertHints(context, "natural-map-ja", true);
			setLanguage(context, english);

			toggleMap(context);
		}
		context.getInput().resizeWindow(854, 480);
		context.waitTicks(3);
	}

	private static void setLanguage(ClientGameTestContext context, String language) {
		CompletableFuture<Void> reload = context.computeOnClient(client -> {
			client.getLanguageManager().setSelected(language);
			client.options.languageCode = language;
			return client.reloadResourcePacks();
		});
		context.waitFor(client -> reload.isDone() && client.gui.overlay() == null);
		context.waitTicks(3);
	}
}
