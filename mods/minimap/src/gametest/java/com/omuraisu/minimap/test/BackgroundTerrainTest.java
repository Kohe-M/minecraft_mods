package com.omuraisu.minimap.test;

import static com.omuraisu.minimap.test.MapTests.*;
import static com.omuraisu.testkit.Asserts.assertEquals;

import com.omuraisu.minimap.Minimap;
import com.omuraisu.minimap.TerrainServer;
import com.omuraisu.minimap.TerrainView;
import com.omuraisu.minimap.client.MinimapClient;
import com.omuraisu.testkit.Scenario;
import com.omuraisu.testkit.Screenshots;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;

// 開いていない間の範囲: 一番引いた地図に入る四角（ウィンドウの形とマップ距離の小さい方）が、開く前に本物の色で埋まり、
// 一番引いて開くとすぐに空きのない地図が出る。開いている間も、画面がその四角に収まっていれば四角の全体を頼む
public class BackgroundTerrainTest implements Scenario {
	// 一番引いた画面は 1 ブロックが 1/4 画素なので、横 427 画素で ±54 チャンク、縦 240 画素で ±30 チャンクが映る。
	// 横はマップ距離で、縦はウィンドウで切れる。生成する数を抑えるため、小さいウィンドウとマップ距離で確かめる
	private static final int WIDTH = 427;
	private static final int HEIGHT = 240;
	private static final int DISTANCE = 36;
	// 一番引いた画面がちょうど四角（±36 × ±30 チャンク）になるウィンドウの幅
	private static final int FIT_WIDTH = 288;
	// 拡大して開いたときに範囲を確かめるマップ距離
	private static final int ZOOMED_DISTANCE = 16;

	@Override
	public String name() {
		return "background";
	}

	@Override
	public void run(ClientGameTestContext context) {
		watchFullLoads();
		setMapDistance(context, DISTANCE);
		context.getInput().resizeWindow(WIDTH, HEIGHT);
		context.waitTicks(3);
		try (TestSingleplayerContext singleplayer = context.worldBuilder()
				.setUseConsistentSettings(false)
				.adjustSettings(creator -> creator.setSeed("background terrain"))
				.create()) {
			singleplayer.getConnection().waitForChunksRender();
			// テレポートの先で落ちないように飛ばす
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				player.setGameMode(GameType.CREATIVE);
				player.getAbilities().flying = true;
				player.onUpdateAbilities();
			});
			TerrainView range = testFill(context, singleplayer);
			testOpen(context, singleplayer, range);
			testOpenZoomedIn(context, singleplayer);
		}
		resetConfig(context);
		context.getInput().resizeWindow(854, 480);
		context.waitTicks(3);
	}

	// マップ画面を開かずにいると、範囲のうちプレイヤーが読み込んでいない（手元の地形で描けない）チャンクの本物の色がすべて届く。
	// その間もサーバーは遅れず、地図のためにワールドへ読み込まない。範囲の外は頼まない
	private static TerrainView testFill(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		int windowReachX = reach(WIDTH, WIDTH, HEIGHT);
		int windowReachZ = reach(HEIGHT, WIDTH, HEIGHT);
		if (windowReachX <= DISTANCE || windowReachZ >= DISTANCE) {
			throw new AssertionError("the window should be wider and shorter than the map distance: " + windowReachX + " x " + windowReachZ);
		}
		ChunkPos player = context.computeOnClient(client -> client.player.chunkPosition());
		TerrainView range = new TerrainView(OVERWORLD, player.x() - DISTANCE, player.z() - windowReachZ, player.x() + DISTANCE, player.z() + windowReachZ,
				player.x(), player.z(), true);
		context.waitTicks(10);
		assertEquals("range requested while the map is closed", range, context.computeOnClient(client -> MinimapClient.FAR_TERRAIN.requested()));

		LongList expected = singleplayer.getServer().computeOnServer(server -> {
			ChunkTrackingView tracking = server.getPlayerList().getPlayers().getFirst().getChunkTrackingView();
			LongList chunks = new LongArrayList();
			for (int z = range.minZ(); z <= range.maxZ(); z++) {
				for (int x = range.minX(); x <= range.maxX(); x++) {
					if (!tracking.contains(x, z)) {
						chunks.add(ChunkPos.pack(x, z));
					}
				}
			}
			return chunks;
		});
		ServerWatch watch = new ServerWatch(singleplayer);
		long start = System.nanoTime();
		int ticks = 0;
		for (int missing = missing(context, expected); missing > 0; missing = missing(context, expected)) {
			if (ticks >= 2400) {
				throw new AssertionError("far terrain in the background range should all arrive but " + missing + " of " + expected.size() + " chunks did not");
			}
			context.waitTicks(20);
			ticks += 20;
			if (ticks % 100 == 0) {
				watch.sample();
			}
		}
		double seconds = (System.nanoTime() - start) / 1e9;
		System.out.printf("background-fill: %d chunks within %d ticks (%.1f s, %.0f chunks/s) while the map was closed%n",
				expected.size(), ticks, seconds, expected.size() / seconds);
		watch.check(context, "background-fill");

		context.waitTicks(40);
		int outside = context.computeOnClient(client -> {
			int count = 0;
			for (int z = range.minZ() - 1; z <= range.maxZ() + 1; z++) {
				for (int x = range.minX() - 1; x <= range.maxX() + 1; x++) {
					boolean edge = x < range.minX() || x > range.maxX() || z < range.minZ() || z > range.maxZ();
					if (edge && MinimapClient.FAR_TERRAIN.colors(OVERWORLD, x, z) != null) {
						count++;
					}
				}
			}
			return count;
		});
		if (outside != 0) {
			throw new AssertionError(outside + " chunks just outside the background range should not be requested");
		}
		return range;
	}

	// 一番引いて開くと、数 tick で画面に空きがなくなる。色は開く前に届いているので、開いた後にセーブの読み込みや生成はほとんど起きない
	private static void testOpen(ClientGameTestContext context, TestSingleplayerContext singleplayer, TerrainView range) {
		// 一番引いた画面がちょうど範囲に収まるウィンドウにする。範囲はマップ距離とウィンドウで決まったまま変わらない
		context.getInput().resizeWindow(FIT_WIDTH, HEIGHT);
		context.waitTicks(10);
		assertEquals("range in the fitting window", range, context.computeOnClient(client -> MinimapClient.FAR_TERRAIN.requested()));
		// 生成したチャンクが書かれると、メモリの版で送った色をセーブの版で読み直す。それが済むまで待ってから数える
		waitForQuietServer(context, singleplayer);
		int reads = singleplayer.getServer().computeOnServer(server -> TerrainServer.readCounts()[0]);
		int generations = singleplayer.getServer().computeOnServer(server -> TerrainServer.generationCount());

		toggleMap(context);
		context.getInput().setCursorPos(FIT_WIDTH / 2, HEIGHT / 2);
		scroll(context, -6);
		assertScale(context, 0.25);
		int ticks = 0;
		for (int empty = emptyPixels(context); empty > 0; empty = emptyPixels(context)) {
			if (ticks >= 20) {
				throw new AssertionError("the zoomed out map should be filled within 20 ticks but " + empty + " pixels were empty");
			}
			context.waitTicks(2);
			ticks += 2;
		}
		// 開いている間は、範囲をそのまま開いている人として頼む（中心は画面の中心）
		assertEquals("range requested while the map is open",
				new TerrainView(OVERWORLD, range.minX(), range.minZ(), range.maxX(), range.maxZ(), range.centerX(), range.centerZ(), false),
				context.computeOnClient(client -> MinimapClient.FAR_TERRAIN.requested()));
		context.waitTicks(100);
		int read = singleplayer.getServer().computeOnServer(server -> TerrainServer.readCounts()[0]) - reads;
		int generated = singleplayer.getServer().computeOnServer(server -> TerrainServer.generationCount()) - generations;
		int chunks = (range.maxX() - range.minX() + 1) * (range.maxZ() - range.minZ() + 1);
		System.out.printf("background-open: filled within %d ticks, read %d and generated %d of %d chunks after opening%n", ticks, read, generated, chunks);
		if (generated > 0 || read * 20 > chunks) {
			throw new AssertionError("far terrain should be ready before opening but " + read + " chunks were read and " + generated + " generated after opening");
		}
		// 次に開いたときは拡大した画面にする
		scroll(context, 3);
		assertScale(context, 2);
		toggleMap(context);
	}

	// 開いている間も、画面が開いていない間の範囲に収まっていれば、拡大していても範囲の全体を頼み、すべて届く。
	// 開く前に届かないよう、無効にしてまだ地図に出したことのない場所へ動き、有効に戻してすぐに開く
	private static void testOpenZoomedIn(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		setMapDistance(context, ZOOMED_DISTANCE);
		context.runOnClient(client -> Minimap.setActive(false));
		ChunkPos start = context.computeOnClient(client -> client.player.chunkPosition());
		ChunkPos area = new ChunkPos(start.x() + 200, start.z());
		teleport(context, singleplayer, area.getMiddleBlockX(), area.getMiddleBlockZ());
		context.runOnClient(client -> Minimap.setActive(true));
		toggleMap(context);
		assertScale(context, 2);
		TerrainView range = new TerrainView(OVERWORLD, area.x() - ZOOMED_DISTANCE, area.z() - ZOOMED_DISTANCE,
				area.x() + ZOOMED_DISTANCE, area.z() + ZOOMED_DISTANCE, area.x(), area.z(), false);
		LongList expected = new LongArrayList();
		for (int z = range.minZ(); z <= range.maxZ(); z++) {
			for (int x = range.minX(); x <= range.maxX(); x++) {
				expected.add(ChunkPos.pack(x, z));
			}
		}
		int ticks = 0;
		for (int missing = missing(context, expected); missing > 0; missing = missing(context, expected)) {
			if (ticks >= 1200) {
				throw new AssertionError("far terrain in the range should all arrive while the map is open but " + missing + " of " + expected.size() + " chunks did not");
			}
			context.waitTicks(20);
			ticks += 20;
		}
		System.out.printf("background-zoomed-in: %d chunks within %d ticks while the map was open and zoomed in%n", expected.size(), ticks);
		assertEquals("range requested while the map is open and zoomed in", range, context.computeOnClient(client -> MinimapClient.FAR_TERRAIN.requested()));
		toggleMap(context);
	}

	// 地図のための生成とセーブの読み込みが 60 tick 止まるまで待つ
	private static void waitForQuietServer(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		long last = -1;
		for (int ticks = 0, quiet = 0; quiet < 60; ticks += 10) {
			if (ticks >= 1200) {
				throw new AssertionError("reading and generating for the map should stop once the background range is filled");
			}
			context.waitTicks(10);
			long now = singleplayer.getServer().computeOnServer(server -> (long) TerrainServer.readCounts()[0] + TerrainServer.generationCount());
			quiet = now == last ? quiet + 10 : 0;
			last = now;
		}
	}

	// 一番引いた画面に映るチャンクの、真ん中のチャンクからの数。実装に頼らず自分で計算する（1 ブロックは 1 画素の 1/4 倍）
	private static int reach(int windowSize, int width, int height) {
		int unit = Math.max(1, Math.round(Math.min(width, height) / 540f));
		return (int) Math.ceil(windowSize / (unit / 4.0) / 2 / 16);
	}

	// 色が届いていないチャンクの数
	private static int missing(ClientGameTestContext context, LongList chunks) {
		return context.computeOnClient(client -> {
			int count = 0;
			for (int i = 0; i < chunks.size(); i++) {
				long chunk = chunks.getLong(i);
				if (MinimapClient.FAR_TERRAIN.stamp(OVERWORLD, ChunkPos.getX(chunk), ChunkPos.getZ(chunk)) == 0) {
					count++;
				}
			}
			return count;
		});
	}

	private static int emptyPixels(ClientGameTestContext context) {
		return Screenshots.read(context.takeScreenshot("background-open"), image -> {
			int count = 0;
			for (int y = 0; y < image.getHeight(); y++) {
				for (int x = 0; x < image.getWidth(); x++) {
					if (image.getPixel(x, y) == EMPTY_COLOR) {
						count++;
					}
				}
			}
			return count;
		});
	}
}
