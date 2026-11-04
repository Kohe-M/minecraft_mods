package com.omuraisu.minimap.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.mojang.blaze3d.platform.NativeImage;
import com.omuraisu.minimap.client.MinimapClient;
import com.omuraisu.minimap.client.Pins;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.Iris;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.MapColor;

public class MinimapClientGameTest implements FabricClientGameTest {
	private static final Identifier OVERWORLD = Level.OVERWORLD.identifier();
	private static final Identifier NETHER = Level.NETHER.identifier();

	// テクスチャの端（144 の倍数）が通る列に目印を置く
	private static final int SEAM = 288;
	private static final int PLAYER_X = SEAM + 10;
	private static final int PLAYER_Z = SEAM + 20;
	private static final int FLOOR_Y = 150;
	private static final int FEET_Y = FLOOR_Y + 1;

	private static final int FRAME_COLOR = 0xFF0B0D12;
	// 全列を描き直すのにかかる時間より長く待つ
	private static final int MAP_REFRESH_TICKS = 45;

	@Override
	public void runTest(ClientGameTestContext context) {
		TestWorldSave save;
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			save = singleplayer.getWorldSave();
			buildFloor(singleplayer);
			teleport(context, singleplayer, PLAYER_X, PLAYER_Z);

			testScale(context);
			testTerrain(context, singleplayer);
			testHidden(context);
			testPins(context, singleplayer);
			testNether(context, singleplayer);
			if (FabricLoader.getInstance().isModLoaded("iris")) {
				testShaderPack(context, singleplayer);
			}
		}

		// 開き直してもピンが残っている。壊れた行は読み飛ばす
		appendPinFile(save, "broken line\nminecraft:overworld abc 1\n");
		try (TestSingleplayerContext singleplayer = save.open()) {
			singleplayer.getConnection().waitForChunksRender();
			assertPins(OVERWORLD, new Pins.Pin(OVERWORLD, PLAYER_X, PLAYER_Z));
		}

		// 別のワールドには持ち越さない
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();
			assertPins(OVERWORLD);
		}

		testServerFile();
		testNaturalTerrain(context);
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
		}
		context.getInput().resizeWindow(854, 480);
		context.waitTicks(3);
	}

	// 解像度に合わせて大きくなり、GUI スケールの設定には左右されない
	private static void testScale(ClientGameTestContext context) {
		assertEquals("map at 854x480", 130, frame(context, "scale-480p", 854, 480).size());

		Frame fullHd = frame(context, "scale-1080p", 1920, 1080);
		assertEquals("map at 1920x1080", 260, fullHd.size());

		int guiScale = context.computeOnClient(client -> client.options.guiScale().get());
		for (int scale : new int[] {1, 2, 4}) {
			setGuiScale(context, scale);
			Frame scaled = frame(context, "scale-1080p-gui" + scale, 1920, 1080);
			if (!scaled.equals(fullHd)) {
				throw new AssertionError("gui scale " + scale + " changed the map to " + scaled + " from " + fullHd);
			}
		}
		setGuiScale(context, guiScale);

		assertEquals("map at 2560x1440", 390, frame(context, "scale-1440p", 2560, 1440).size());
		assertEquals("map at 3840x2160", 520, frame(context, "scale-2160p", 3840, 2160).size());

		// 縦長のウィンドウでは幅に合わせる
		assertEquals("map at 1080x1920", 260, frame(context, "scale-portrait", 1080, 1920).size());

		context.getInput().resizeWindow(854, 480);
		context.waitTicks(3);
	}

	private static void testTerrain(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		context.waitTicks(MAP_REFRESH_TICKS);
		for (int[] window : new int[][] {{854, 480}, {1920, 1080}, {3840, 2160}}) {
			Map map = map(context, "terrain-" + window[1], window[0], window[1]);
			// 北が上、東が右。テクスチャの端をまたいでも途切れない
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					map.assertBlock(SEAM + dx, SEAM + dz, MapColor.FIRE);
				}
			}
			map.assertBlock(SEAM + 3, SEAM, MapColor.STONE);
			map.assertBlock(SEAM - 3, SEAM, MapColor.STONE);
			map.assertBlock(SEAM, SEAM + 3, MapColor.STONE);
			map.assertBlock(SEAM, SEAM - 3, MapColor.STONE);
			map.assertBlock(PLAYER_X + 20, PLAYER_Z, MapColor.WATER);
		}
		context.getInput().resizeWindow(854, 480);
		context.waitTicks(3);

		// 置いたブロックが反映される
		singleplayer.getServer().runOnServer(server -> server.overworld()
				.setBlockAndUpdate(new BlockPos(PLAYER_X - 15, FLOOR_Y, PLAYER_Z + 15), Blocks.GOLD_BLOCK.defaultBlockState()));
		context.waitTicks(MAP_REFRESH_TICKS);
		map(context, "terrain-placed", 854, 480).assertBlock(PLAYER_X - 15, PLAYER_Z + 15, MapColor.GOLD);

		// 歩いた分だけ地図が動く
		teleport(context, singleplayer, PLAYER_X + 7, PLAYER_Z - 5);
		Map moved = map(context, "terrain-moved", 854, 480);
		for (int dx = -1; dx <= 1; dx++) {
			moved.assertBlock(SEAM + dx, SEAM, MapColor.FIRE);
		}
		teleport(context, singleplayer, PLAYER_X, PLAYER_Z);
	}

	// F1 と F3 では出さない
	private static void testHidden(ClientGameTestContext context) {
		context.runOnClient(client -> client.gui.hud.toggle());
		assertNoFrame(context, "hidden-f1");
		context.runOnClient(client -> client.gui.hud.toggle());

		context.runOnClient(client -> client.debugEntries.setOverlayVisible(true));
		assertNoFrame(context, "hidden-f3");
		context.runOnClient(client -> client.debugEntries.setOverlayVisible(false));

		frame(context, "hidden-restored", 854, 480);
	}

	private static void testPins(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		assertPins(OVERWORLD);

		// ピンがないときに抜いても何も起きない
		context.getInput().pressKey(MinimapClient.removeKey);
		context.waitTicks(2);
		assertPins(OVERWORLD);

		Pins.Pin first = new Pins.Pin(OVERWORLD, PLAYER_X, PLAYER_Z);
		context.getInput().pressKey(MinimapClient.placeKey);
		context.waitTicks(2);
		assertPins(OVERWORLD, first);

		// 同じ場所には増えない
		context.getInput().pressKey(MinimapClient.placeKey);
		context.waitTicks(2);
		assertPins(OVERWORLD, first);

		// 離れると、離れた分だけずれたところに見える
		teleport(context, singleplayer, PLAYER_X + 25, PLAYER_Z - 15);
		map(context, "pin-near", 854, 480).assertPin(first);
		map(context, "pin-near-1080p", 1920, 1080).assertPin(first);
		context.getInput().resizeWindow(854, 480);
		context.waitTicks(3);

		Pins.Pin second = new Pins.Pin(OVERWORLD, PLAYER_X + 25, PLAYER_Z - 15);
		context.getInput().pressKey(MinimapClient.placeKey);
		context.waitTicks(2);
		assertPins(OVERWORLD, first, second);

		// 抜くのは一番近いピン
		teleport(context, singleplayer, PLAYER_X + 20, PLAYER_Z - 10);
		context.getInput().pressKey(MinimapClient.removeKey);
		context.waitTicks(2);
		assertPins(OVERWORLD, first);
		context.takeScreenshot("pin-removed");

		// 範囲外のピンは方角を保って縁に出る。抜くと消えることで確かめる
		teleport(context, singleplayer, PLAYER_X + 400, PLAYER_Z);
		int withPin = map(context, "pin-far", 854, 480).farPinPixel();
		context.getInput().pressKey(MinimapClient.removeKey);
		context.waitTicks(2);
		assertPins(OVERWORLD);
		int withoutPin = map(context, "pin-far-removed", 854, 480).farPinPixel();
		if (withPin == withoutPin) {
			throw new AssertionError("far pin should be drawn at the west edge but pixel stayed " + Integer.toHexString(withPin));
		}

		// 開き直しの確認用に 1 本残す
		teleport(context, singleplayer, PLAYER_X, PLAYER_Z);
		context.getInput().pressKey(MinimapClient.placeKey);
		context.waitTicks(2);
		assertPins(OVERWORLD, first);
	}

	// 天井ではなく足元を映す。ピンは次元ごとに分かれる
	private static void testNether(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		singleplayer.getServer().runOnServer(server -> {
			ServerLevel nether = server.getLevel(Level.NETHER);
			for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(-20, 60, -20), new BlockPos(20, 70, 20))) {
				nether.setBlockAndUpdate(pos, pos.getY() == 60 ? Blocks.GOLD_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState());
			}
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			player.teleportTo(nether, 0.5, 61, 0.5, Set.of(), 0, 0, true);
		});
		context.waitTicks(20);
		singleplayer.getConnection().waitForChunksRender();
		context.waitTicks(MAP_REFRESH_TICKS);

		assertPins(NETHER);
		Map map = map(context, "nether", 854, 480);
		map.assertBlockRelative(10, 0, MapColor.GOLD);
		map.assertBlockRelative(-10, 10, MapColor.GOLD);

		context.getInput().pressKey(MinimapClient.placeKey);
		context.waitTicks(2);
		assertPins(NETHER, new Pins.Pin(NETHER, 0, 0));
		assertPins(OVERWORLD, new Pins.Pin(OVERWORLD, PLAYER_X, PLAYER_Z));

		// ネザーから抜いてもオーバーワールドのピンは残る
		context.getInput().pressKey(MinimapClient.removeKey);
		context.waitTicks(2);
		assertPins(NETHER);
		assertPins(OVERWORLD, new Pins.Pin(OVERWORLD, PLAYER_X, PLAYER_Z));

		singleplayer.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst()
				.teleportTo(server.overworld(), PLAYER_X + 0.5, FEET_Y, PLAYER_Z + 0.5, Set.of(), 0, 0, true));
		context.waitTicks(20);
		singleplayer.getConnection().waitForChunksRender();
		context.waitTicks(MAP_REFRESH_TICKS);
	}

	// シェーダーパックを有効にしても色が変わらない
	private static void testShaderPack(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		teleport(context, singleplayer, PLAYER_X + 25, PLAYER_Z);
		context.runOnClient(client -> IrisPack.enable());
		context.waitTicks(20);
		singleplayer.getConnection().waitForChunksRender();
		try {
			Map map = map(context, "shader-pack", 854, 480);
			map.assertBlock(SEAM, SEAM, MapColor.FIRE);
			map.assertPin(new Pins.Pin(OVERWORLD, PLAYER_X, PLAYER_Z));
		} finally {
			context.runOnClient(client -> IrisPack.disable());
			context.waitTicks(20);
			singleplayer.getConnection().waitForChunksRender();
		}
	}

	// 接続先のアドレスをそのままファイル名にしない
	private static void testServerFile() {
		String name = Pins.serverFile("example.com:25565/*").getFileName().toString();
		if (!name.equals("example.com%3A25565%2F%2A.txt")) {
			throw new AssertionError("unexpected server pin file name " + name);
		}
	}

	private static void buildFloor(TestSingleplayerContext singleplayer) {
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

	private static void fill(ServerLevel level, BlockPos from, BlockPos to, Block block) {
		for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
			level.setBlockAndUpdate(pos, block.defaultBlockState());
		}
	}

	private static void teleport(ClientGameTestContext context, TestSingleplayerContext singleplayer, int x, int z) {
		singleplayer.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			player.teleportTo(server.overworld(), x + 0.5, FEET_Y, z + 0.5, Set.of(), 0, 0, true);
		});
		context.waitTicks(10);
		singleplayer.getConnection().waitForChunksRender();
		context.waitTicks(MAP_REFRESH_TICKS);
	}

	private static void setGuiScale(ClientGameTestContext context, int scale) {
		context.runOnClient(client -> {
			client.options.guiScale().set(scale);
			client.resizeGui();
		});
		context.waitTicks(3);
	}

	private static void assertNoFrame(ClientGameTestContext context, String name) {
		context.waitTicks(3);
		readImage(context.takeScreenshot(name), image -> {
			Frame frame = findFrame(image);
			if (frame != null) {
				throw new AssertionError(name + ": map should be hidden but found " + frame);
			}
			return null;
		});
	}

	private static Frame frame(ClientGameTestContext context, String name, int width, int height) {
		context.getInput().resizeWindow(width, height);
		context.waitTicks(3);
		return readImage(context.takeScreenshot(name), image -> {
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

	private static Map map(ClientGameTestContext context, String name, int width, int height) {
		context.getInput().resizeWindow(width, height);
		context.waitTicks(3);
		return readImage(context.takeScreenshot(name), image -> {
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
			return new Map(name, pixels, frame.size() / 130, player[0], player[1]);
		});
	}

	// 枠の上辺を探す。枠の色が横に 100 ピクセル以上続く最初の行
	private static Frame findFrame(NativeImage image) {
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

	private static void appendPinFile(TestWorldSave save, String text) {
		Path file = save.getSaveDirectory().resolve("minimap_pins.txt");
		try {
			Files.writeString(file, Files.readString(file) + text);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void assertPins(Identifier dimension, Pins.Pin... expected) {
		List<Pins.Pin> actual = MinimapClient.PINS.in(dimension);
		if (!actual.equals(List.of(expected))) {
			throw new AssertionError("expected pins " + List.of(expected) + " but was " + actual);
		}
	}

	private static void assertEquals(String name, int expected, int actual) {
		if (expected != actual) {
			throw new AssertionError(name + ": expected " + expected + " but was " + actual);
		}
	}

	private static <T> T readImage(Path path, ImageReader<T> reader) {
		try (InputStream in = Files.newInputStream(path); NativeImage image = NativeImage.read(in)) {
			return reader.read(image);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private interface ImageReader<T> {
		T read(NativeImage image);
	}

	private record Frame(int x, int y, int size) {
	}

	// スクリーンショットから切り出した地図。pixels は枠を含む
	private record Map(String name, int[][] pixels, int unit, int playerX, int playerZ) {
		// プレイヤーから offset ブロック離れた列の位置。枠の 1 ブロック分を足す
		private int pixelAt(int offset) {
			return unit + 64 * unit + offset * unit;
		}

		void assertBlock(int x, int z, MapColor color) {
			assertBlockRelative(x - playerX, z - playerZ, color);
		}

		void assertBlockRelative(int dx, int dz, MapColor color) {
			int pixel = pixels[pixelAt(dz)][pixelAt(dx)];
			for (MapColor.Brightness brightness : MapColor.Brightness.values()) {
				if (ARGB.opaque(color.calculateARGBColor(brightness)) == pixel) {
					return;
				}
			}
			throw new AssertionError(name + ": block at offset " + dx + ", " + dz + " should have map color " + color.col
					+ " but pixel was " + Integer.toHexString(pixel));
		}

		// ピンの頭。真ん中は穴なので少しずらして読む
		void assertPin(Pins.Pin pin) {
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

	// Iris がないときに読み込まれないようにクラスを分ける
	private static final class IrisPack {
		private static final String NAME = "minimap-probe";

		private static final String VERTEX = """
				#version 120
				void main() {
					gl_Position = ftransform();
				}
				""";

		private static final String FRAGMENT = """
				#version 120
				void main() {
					gl_FragData[0] = vec4(1.0, 0.0, 1.0, 1.0);
				}
				""";

		static void enable() {
			try {
				Path shaders = Iris.getShaderpacksDirectory().resolve(NAME).resolve("shaders");
				Files.createDirectories(shaders);
				Files.writeString(shaders.resolve("gbuffers_terrain.vsh"), VERTEX);
				Files.writeString(shaders.resolve("gbuffers_terrain.fsh"), FRAGMENT);

				Iris.getIrisConfig().setShaderPackName(NAME);
				Iris.getIrisConfig().setShadersEnabled(true);
				Iris.getIrisConfig().save();
				Iris.reload();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

		static void disable() {
			try {
				Iris.getIrisConfig().setShadersEnabled(false);
				Iris.getIrisConfig().save();
				Iris.reload();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
	}
}
