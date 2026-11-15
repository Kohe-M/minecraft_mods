package com.omuraisu.minimap.test;

import static com.omuraisu.testkit.Asserts.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.omuraisu.minimap.Pin;
import com.omuraisu.minimap.client.MapScreen;
import com.omuraisu.minimap.client.MinimapClient;
import com.omuraisu.testkit.Screenshots;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.MapColor;

// minimap のクライアントのゲームテストで使う道具と、シナリオに共通の場所。シナリオは *Test（testkit の Scenario）
final class MapTests {
	private MapTests() {
	}

	static final Identifier OVERWORLD = Level.OVERWORLD.identifier();
	static final Identifier NETHER = Level.NETHER.identifier();

	// テクスチャの端（176 の倍数）が通る列に目印を置く
	static final int SEAM = 352;
	static final int PLAYER_X = SEAM + 10;
	static final int PLAYER_Z = SEAM + 20;
	static final int FLOOR_Y = 150;
	static final int FEET_Y = FLOOR_Y + 1;

	static final int FRAME_COLOR = 0xFF0B0D12;
	static final int EMPTY_COLOR = 0xFF1A1E26;
	// 全列を描き直すのにかかる時間より長く待つ
	static final int MAP_REFRESH_TICKS = 45;

	// サーバーがワールドに読み込んだチャンク（FULL）。サーバーのスレッドでだけ触る
	static final LongSet FULL_LOADS = new LongOpenHashSet();
	private static boolean watchingFullLoads;

	// 地図のためにワールドへ読み込んでいないことを確かめるシナリオで、始めに 1 度呼ぶ
	static void watchFullLoads() {
		if (watchingFullLoads) {
			return;
		}
		watchingFullLoads = true;
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
			if (level.dimension() == Level.OVERWORLD) {
				FULL_LOADS.add(chunk.getPos().pack());
			}
		});
	}

	// サーバーが遅れず、地図のためにチャンクをワールドへ読み込んでいないかを見る。作ったときから数え、sample を 100 tick ごとに呼ぶ
	// （tick の時間は直近 100 tick の分しか残らない）
	static final class ServerWatch {
		private final TestSingleplayerContext singleplayer;
		private long maxTick;

		ServerWatch(TestSingleplayerContext singleplayer) {
			this.singleplayer = singleplayer;
			singleplayer.getServer().runOnServer(server -> FULL_LOADS.clear());
		}

		void sample() {
			maxTick = Math.max(maxTick, singleplayer.getServer().computeOnServer(server -> Arrays.stream(server.getTickTimesNanos()).max().orElse(0)));
		}

		// 1 tick が 1 秒を超えた・直近の平均が 50 ms を超えた・地図のためにワールドへ読み込んだ、なら失敗
		void check(ClientGameTestContext context, String name) {
			sample();
			long averageTick = singleplayer.getServer().computeOnServer(server -> server.getAverageTickTimeNanos());
			int loads = singleplayer.getServer().computeOnServer(server -> FULL_LOADS.size());
			int chunks = context.computeOnClient(client -> MinimapClient.FAR_TERRAIN.count(OVERWORLD));
			System.out.printf("%s: %d chunks, max tick %d ms, average tick %d ms, loaded into the world %d%n",
					name, chunks, maxTick / 1_000_000, averageTick / 1_000_000, loads);
			if (loads > 100) {
				throw new AssertionError(name + ": " + loads + " chunks were loaded into the world for the map");
			}
			if (maxTick > 1_000_000_000L || averageTick > 50_000_000L) {
				throw new AssertionError(name + ": server fell behind (max tick " + maxTick / 1_000_000 + " ms, average " + averageTick / 1_000_000 + " ms)");
			}
		}
	}

	// 前のシナリオや前の実行がマップ距離を変えていても、既定の値で始める
	static void resetConfig(ClientGameTestContext context) {
		setMapDistance(context, MinimapClient.DEFAULT_MAP_DISTANCE);
	}

	static void setMapDistance(ClientGameTestContext context, int distance) {
		try {
			Files.writeString(MinimapClient.CONFIG, "map_distance=" + distance + "\n");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		context.runOnClient(client -> MinimapClient.load());
	}

	// 遠くの地形が届かなくなるまで待つ
	static void waitForQuietTerrain(ClientGameTestContext context) {
		int quiet = 0;
		int received = -1;
		for (int i = 0; quiet < 60; i++) {
			if (i >= 2400) {
				throw new AssertionError("far terrain should stop arriving once everything around is sent");
			}
			context.waitTick();
			int now = context.computeOnClient(client -> MinimapClient.FAR_TERRAIN.received());
			quiet = now == received ? quiet + 1 : 0;
			received = now;
		}
	}

	// 真っ白な画素が並ぶ範囲。{左, 右, 上, 下}（どれも含む）。なければ null
	static int[] whiteExtent(NativeImage image, int left, int right, int top, int bottom) {
		int[] extent = null;
		for (int y = top; y < Math.min(bottom, image.getHeight()); y++) {
			for (int x = left; x < Math.min(right, image.getWidth()); x++) {
				if (image.getPixel(x, y) != 0xFFFFFFFF) {
					continue;
				}
				if (extent == null) {
					extent = new int[] {x, x, y, y};
				} else {
					extent[0] = Math.min(extent[0], x);
					extent[1] = Math.max(extent[1], x);
					extent[2] = Math.min(extent[2], y);
					extent[3] = Math.max(extent[3], y);
				}
			}
		}
		return extent;
	}

	// カーソルを真ん中に置いて回すと、地図の中心が動かない
	static void zoomAtCenter(ClientGameTestContext context, int steps) {
		context.getInput().setCursorPos(427, 240);
		context.waitTick();
		scroll(context, steps);
		moveCursorAway(context);
	}

	// 生成を待つ。テクスチャは届いた次の tick までに描かれる
	static void waitForFarTerrain(ClientGameTestContext context, ChunkPos... chunks) {
		context.waitFor(client -> Arrays.stream(chunks)
				.allMatch(chunk -> MinimapClient.FAR_TERRAIN.colors(client.level.dimension().identifier(), chunk.x(), chunk.z()) != null), 2400);
		context.waitTicks(15);
	}

	static void confirmName(ClientGameTestContext context) {
		context.getInput().pressKey(InputConstants.KEY_RETURN);
		context.waitTicks(2);
	}

	static void rightClick(ClientGameTestContext context, int x, int y) {
		context.getInput().setCursorPos(x, y);
		context.waitTick();
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
		context.waitTick();
		moveCursorAway(context);
	}

	static int differingPixels(NativeImage a, NativeImage b, int left, int right, int top, int bottom) {
		int count = 0;
		for (int y = top; y < bottom; y++) {
			for (int x = left; x < right; x++) {
				int p = a.getPixel(x, y);
				int q = b.getPixel(x, y);
				int difference = Math.abs(ARGB.red(p) - ARGB.red(q)) + Math.abs(ARGB.green(p) - ARGB.green(q)) + Math.abs(ARGB.blue(p) - ARGB.blue(q));
				if (difference >= 8) {
					count++;
				}
			}
		}
		return count;
	}

	static void assertFileEquals(Path file, String expected) {
		try {
			String actual = Files.readString(file);
			if (!actual.equals(expected)) {
				throw new AssertionError("expected " + file + " to be " + expected + " but was " + actual);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	static void shiftClick(ClientGameTestContext context, int x, int y) {
		context.getInput().setCursorPos(x, y);
		context.waitTick();
		context.getInput().holdShift();
		context.waitTick();
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTick();
		context.getInput().releaseShift();
		context.waitTick();
		moveCursorAway(context);
	}

	static void assertNotEditing(ClientGameTestContext context) {
		boolean editing = context.computeOnClient(client -> client.gui.screen().getFocused() != null);
		if (editing) {
			throw new AssertionError("name box should not be open");
		}
	}

	// 左下の操作の説明を 1 行ずつ読む。文字は真っ白で、地形や背景には出ない色
	static void assertHints(ClientGameTestContext context, String name, boolean visible) {
		int guiScale = context.computeOnClient(client -> client.getWindow().getGuiScale());
		Screenshots.read(context.takeScreenshot(name), image -> {
			int rowHeight = 11 * guiScale;
			int bottom = image.getHeight() - 4 * guiScale;
			for (int row = 1; row <= 2; row++) {
				int pixels = 0;
				for (int y = bottom - row * rowHeight; y < bottom - (row - 1) * rowHeight; y++) {
					for (int x = 0; x < 200 * guiScale; x++) {
						if (image.getPixel(x, y) == 0xFFFFFFFF) {
							pixels++;
						}
					}
				}
				if (visible != (pixels > 0)) {
					throw new AssertionError(name + ": hint row " + row + " should be " + (visible ? "shown" : "hidden") + " but had " + pixels + " pixels");
				}
			}
			return null;
		});
	}

	// 右下の座標の文字を読み、背景の画素を返す。文字は真っ白で GUI スケールで描かれ、画面の端から 4 離れる。
	// 地形に雪の白が出ることがあるので、背景の中だけを読む。幅で何の文字かを確かめる
	static int[][] assertCursorCoordinates(ClientGameTestContext context, String name, int x, int y, Pin block) {
		context.getInput().setCursorPos(x, y);
		context.waitTick();
		String expected = block.x() + ", " + block.z();
		int[] gui = context.computeOnClient(client -> new int[] {
				client.getWindow().getGuiScale(), client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight(),
				client.font.width(expected)});
		int guiScale = gui[0];
		int textLeft = gui[1] - 4 - gui[3];
		int labelTop = gui[2] - 4 - 11;
		return Screenshots.read(context.takeScreenshot(name), image -> {
			int left = (textLeft - 2) * guiScale;
			int right = (gui[1] - 2) * guiScale;
			int top = labelTop * guiScale;
			int bottom = (labelTop + 10) * guiScale;
			int[] text = whiteExtent(image, left, right, top, bottom);
			if (text == null) {
				throw new AssertionError(name + ": cursor coordinates " + expected + " should be shown at the bottom right");
			}
			// 最後の字の後の 1 画素の間隔は描かれない
			assertEquals(name + ": left of " + expected, textLeft * guiScale, text[0]);
			assertEquals(name + ": right of " + expected, (gui[1] - 5) * guiScale - 1, text[1]);

			int[][] pixels = new int[bottom - top][right - left];
			for (int row = 0; row < pixels.length; row++) {
				for (int column = 0; column < pixels[row].length; column++) {
					pixels[row][column] = image.getPixel(left + column, top + row);
				}
			}
			return pixels;
		});
	}

	static void toggleMap(ClientGameTestContext context) {
		context.getInput().pressKey(MinimapClient.mapKey);
		context.waitTicks(2);
	}

	static void click(ClientGameTestContext context, int x, int y) {
		context.getInput().setCursorPos(x, y);
		context.waitTick();
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTick();
		moveCursorAway(context);
	}

	static void placeAt(ClientGameTestContext context, int x, int y) {
		context.getInput().setCursorPos(x, y);
		context.waitTick();
		context.getInput().pressKey(MinimapClient.placeKey);
		context.waitTick();
		moveCursorAway(context);
	}

	static void scroll(ClientGameTestContext context, int steps) {
		for (int i = 0; i < Math.abs(steps); i++) {
			context.getInput().scroll(Math.signum(steps));
			context.waitTick();
		}
	}

	// ホバーで大きくなったピンがカーソルの下に残らないようにする
	static void moveCursorAway(ClientGameTestContext context) {
		context.getInput().setCursorPos(1, 1);
		context.waitTick();
	}

	static void assertMapOpen(ClientGameTestContext context, boolean open) {
		boolean actual = context.computeOnClient(client -> client.gui.screen() instanceof MapScreen);
		if (actual != open) {
			throw new AssertionError("map screen open should be " + open + " but was " + actual);
		}
	}

	static void assertScale(ClientGameTestContext context, double expected) {
		double actual = context.computeOnClient(client -> ((MapScreen) client.gui.screen()).scale());
		if (actual != expected) {
			throw new AssertionError("map scale should be " + expected + " but was " + actual);
		}
	}

	static void assertPixel(ClientGameTestContext context, String name, int x, int y, int expected) {
		int pixel = Screenshots.read(context.takeScreenshot(name), image -> image.getPixel(x, y));
		if (pixel != expected) {
			throw new AssertionError(name + ": expected " + Integer.toHexString(expected) + " at " + x + ", " + y
					+ " but was " + Integer.toHexString(pixel));
		}
	}

	// マップ画面の座標。テストでは実装に頼らず自分で計算する
	record ScreenView(int width, int height, double scale, double centerX, double centerZ) {
		ScreenView(int width, int height, int scale, int playerX, int playerZ) {
			this(width, height, (double) scale, playerX + 0.5, playerZ + 0.5);
		}

		ScreenView panned(int dx, int dy) {
			return new ScreenView(width, height, scale, centerX - dx / scale, centerZ - dy / scale);
		}

		int x(int blockX) {
			return (int) Math.floor(width / 2.0 + (blockX + 0.5 - centerX) * scale);
		}

		int y(int blockZ) {
			return (int) Math.floor(height / 2.0 + (blockZ + 0.5 - centerZ) * scale);
		}

		void assertBlock(ClientGameTestContext context, String name, int blockX, int blockZ, MapColor color) {
			int pixel = Screenshots.read(context.takeScreenshot(name), image -> image.getPixel(x(blockX), y(blockZ)));
			for (MapColor.Brightness brightness : MapColor.Brightness.values()) {
				if (ARGB.opaque(color.calculateARGBColor(brightness)) == pixel) {
					return;
				}
			}
			throw new AssertionError(name + ": block " + blockX + ", " + blockZ + " should have map color " + color.col
					+ " but pixel was " + Integer.toHexString(pixel));
		}

		void assertNotBlock(ClientGameTestContext context, String name, int blockX, int blockZ, MapColor color) {
			int pixel = Screenshots.read(context.takeScreenshot(name), image -> image.getPixel(x(blockX), y(blockZ)));
			if (isMapColor(pixel, color)) {
				throw new AssertionError(name + ": something should be drawn at " + blockX + ", " + blockZ + " but it was terrain");
			}
		}

		void assertPin(ClientGameTestContext context, String name, Pin pin) {
			int unit = Math.max(1, Math.round(Math.min(width, height) / 540f));
			assertPixel(context, name, x(pin.x()) + 2 * unit, y(pin.z()) - 8 * unit, pin.color());
		}
	}

	static void buildFloor(TestSingleplayerContext singleplayer) {
		singleplayer.getServer().runOnServer(server -> {
			ServerLevel level = server.overworld();
			BlockPos center = new BlockPos(PLAYER_X, FLOOR_Y, PLAYER_Z);
			fill(level, center.offset(-40, -1, -40), center.offset(40, 0, 40), Blocks.STONE);
			fill(level, center.offset(-40, 1, -40), center.offset(40, 12, 40), Blocks.AIR);
			fill(level, new BlockPos(SEAM - 1, FLOOR_Y, SEAM - 1), new BlockPos(SEAM + 1, FLOOR_Y, SEAM + 1), Blocks.REDSTONE_BLOCK);
			level.setBlockAndUpdate(new BlockPos(PLAYER_X + 20, FLOOR_Y, PLAYER_Z), Blocks.WATER.defaultBlockState());
			// 床のない場所でも落ちないように飛ばす
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			player.setGameMode(GameType.CREATIVE);
			player.getAbilities().flying = true;
			player.onUpdateAbilities();
		});
	}

	static void fill(ServerLevel level, BlockPos from, BlockPos to, Block block) {
		for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
			level.setBlockAndUpdate(pos, block.defaultBlockState());
		}
	}

	static void teleport(ClientGameTestContext context, TestSingleplayerContext singleplayer, int x, int z) {
		singleplayer.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			player.teleportTo(server.overworld(), x + 0.5, FEET_Y, z + 0.5, Set.of(), 0, 0, true);
		});
		context.waitTicks(10);
		singleplayer.getConnection().waitForChunksRender();
		context.waitTicks(MAP_REFRESH_TICKS);
	}

	static void setGuiScale(ClientGameTestContext context, int scale) {
		context.runOnClient(client -> {
			client.options.guiScale().set(scale);
			client.resizeGui();
		});
		context.waitTicks(3);
	}

	static void assertNoFrame(ClientGameTestContext context, String name) {
		context.waitTicks(3);
		Screenshots.read(context.takeScreenshot(name), image -> {
			Frame frame = findFrame(image);
			if (frame != null) {
				throw new AssertionError(name + ": map should be hidden but found " + frame);
			}
			return null;
		});
	}

	static Frame frame(ClientGameTestContext context, String name, int width, int height) {
		context.getInput().resizeWindow(width, height);
		context.waitTicks(3);
		return Screenshots.read(context.takeScreenshot(name), image -> {
			if (image.getWidth() != width || image.getHeight() != height) {
				throw new AssertionError(name + ": screenshot was " + image.getWidth() + "x" + image.getHeight());
			}
			Frame frame = findFrame(image);
			if (frame == null) {
				throw new AssertionError(name + ": map frame not found");
			}
			return frame;
		});
	}

	static Map map(ClientGameTestContext context, String name, int width, int height) {
		context.getInput().resizeWindow(width, height);
		context.waitTicks(3);
		return Screenshots.read(context.takeScreenshot(name), image -> {
			Frame frame = findFrame(image);
			if (frame == null) {
				throw new AssertionError(name + ": map frame not found");
			}
			int[][] pixels = new int[frame.size()][frame.size()];
			for (int y = 0; y < frame.size(); y++) {
				for (int x = 0; x < frame.size(); x++) {
					pixels[y][x] = image.getPixel(frame.x() + x, frame.y() + y);
				}
			}
			int[] player = context.computeOnClient(client -> new int[] {client.player.getBlockX(), client.player.getBlockZ()});
			return new Map(name, pixels, frame.size() / 162, player[0], player[1]);
		});
	}

	// 枠の上辺を探す。枠の色が横に 100 ピクセル以上続く最初の行
	static Frame findFrame(NativeImage image) {
		for (int y = 0; y < image.getHeight() / 2; y++) {
			int run = 0;
			for (int x = 0; x < image.getWidth(); x++) {
				if (image.getPixel(x, y) == FRAME_COLOR) {
					run++;
				} else if (run > 0) {
					if (run >= 100) {
						return new Frame(x - run, y, run);
					}
					run = 0;
				}
			}
		}
		return null;
	}

	static void assertPins(Identifier dimension, Pin... expected) {
		List<Pin> actual = MinimapClient.PINS.in(dimension);
		if (!actual.equals(List.of(expected))) {
			throw new AssertionError("expected pins " + List.of(expected) + " but was " + actual);
		}
	}

	static boolean isMapColor(int pixel, MapColor color) {
		for (MapColor.Brightness brightness : MapColor.Brightness.values()) {
			if (ARGB.opaque(color.calculateARGBColor(brightness)) == pixel) {
				return true;
			}
		}
		return false;
	}

	record Frame(int x, int y, int size) {
	}

	// スクリーンショットから切り出した地図。pixels は枠を含む
	record Map(String name, int[][] pixels, int unit, int playerX, int playerZ) {
		// プレイヤーから offset ブロック離れた列の位置。枠の 1 ブロック分を足す
		private int pixelAt(int offset) {
			return unit + 80 * unit + offset * unit;
		}

		void assertBlock(int x, int z, MapColor color) {
			assertBlockRelative(x - playerX, z - playerZ, color);
		}

		int pixelRelative(int dx, int dz) {
			return pixels[pixelAt(dz)][pixelAt(dx)];
		}

		void assertNotBlockRelative(int dx, int dz, MapColor color) {
			int pixel = pixelRelative(dx, dz);
			if (isMapColor(pixel, color)) {
				throw new AssertionError(name + ": something should be drawn at offset " + dx + ", " + dz + " but it was terrain");
			}
		}

		void assertBlockRelative(int dx, int dz, MapColor color) {
			int pixel = pixelRelative(dx, dz);
			for (MapColor.Brightness brightness : MapColor.Brightness.values()) {
				if (ARGB.opaque(color.calculateARGBColor(brightness)) == pixel) {
					return;
				}
			}
			throw new AssertionError(name + ": block at offset " + dx + ", " + dz + " should have map color " + color.col
					+ " but pixel was " + Integer.toHexString(pixel));
		}

		// ピンの頭。真ん中は穴なので少しずらして読む
		void assertPin(Pin pin) {
			int x = pixelAt(pin.x() - playerX) + 2 * unit;
			int y = pixelAt(pin.z() - playerZ) - 8 * unit;
			int pixel = pixels[y][x];
			if (pixel != pin.color()) {
				throw new AssertionError(name + ": pin " + pin + " should be " + Integer.toHexString(pin.color())
						+ " at " + x + ", " + y + " but pixel was " + Integer.toHexString(pixel));
			}
		}

		// 真西の遠くにあるピンの頭が来る位置
		int farPinPixel() {
			return pixels[pixelAt(0) - 8 * unit][unit + 6 * unit];
		}
	}
}
