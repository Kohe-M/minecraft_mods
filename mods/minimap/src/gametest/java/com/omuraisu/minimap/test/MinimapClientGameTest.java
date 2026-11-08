package com.omuraisu.minimap.test;

import static com.omuraisu.testkit.Asserts.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.omuraisu.minimap.Minimap;
import com.omuraisu.minimap.TerrainRequest;
import com.omuraisu.minimap.TerrainServer;
import com.omuraisu.minimap.client.MapScreen;
import com.omuraisu.minimap.client.MinimapClient;
import com.omuraisu.minimap.client.Pins;
import com.omuraisu.testkit.Screenshots;
import com.omuraisu.testkit.TestServers;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.Iris;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ARGB;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.MapColor;

public class MinimapClientGameTest implements FabricClientGameTest {
	private static final Identifier OVERWORLD = Level.OVERWORLD.identifier();
	private static final Identifier NETHER = Level.NETHER.identifier();

	// テクスチャの端（176 の倍数）が通る列に目印を置く
	private static final int SEAM = 352;
	private static final int PLAYER_X = SEAM + 10;
	private static final int PLAYER_Z = SEAM + 20;
	private static final int FLOOR_Y = 150;
	private static final int FEET_Y = FLOOR_Y + 1;

	private static final int FRAME_COLOR = 0xFF0B0D12;
	private static final int EMPTY_COLOR = 0xFF1A1E26;
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
			testEffects(context, singleplayer);
			testPins(context, singleplayer);
			testMapScreen(context, singleplayer);
			testPinNames(context, singleplayer, save);
			testFarTerrain(context, singleplayer);
			testBeams(context, singleplayer);
			testOtherPlayers(context, singleplayer);
			testNether(context, singleplayer);
			if (FabricLoader.getInstance().isModLoaded("iris")) {
				testShaderPack(context, singleplayer);
			}
		}

		// 開き直してもピンが残っている。壊れた行は読み飛ばす
		appendPinFile(save, "broken line\nminecraft:overworld abc 1\nminecraft:overworld 7 8  my  base \n");
		try (TestSingleplayerContext singleplayer = save.open()) {
			singleplayer.getConnection().waitForChunksRender();
			assertPins(OVERWORLD, new Pins.Pin(OVERWORLD, PLAYER_X, PLAYER_Z), new Pins.Pin(OVERWORLD, 7, 8, "my  base"));
		}

		// 別のワールドには持ち越さない
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();
			assertPins(OVERWORLD);
		}

		testServerFile();
		testDedicatedServer(context);
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

	// 解像度に合わせて大きくなり、GUI スケールの設定には左右されない
	private static void testScale(ClientGameTestContext context) {
		Frame small = frame(context, "scale-480p", 854, 480);
		assertEquals("map at 854x480", 162, small.size());
		assertEquals("right edge at 854x480", 854 - 7, small.x() + small.size());
		assertEquals("top edge at 854x480", 7, small.y());

		Frame fullHd = frame(context, "scale-1080p", 1920, 1080);
		assertEquals("map at 1920x1080", 324, fullHd.size());
		assertEquals("right edge at 1920x1080", 1920 - 14, fullHd.x() + fullHd.size());
		assertEquals("top edge at 1920x1080", 14, fullHd.y());

		int guiScale = context.computeOnClient(client -> client.options.guiScale().get());
		for (int scale : new int[] {1, 2, 4}) {
			setGuiScale(context, scale);
			Frame scaled = frame(context, "scale-1080p-gui" + scale, 1920, 1080);
			if (!scaled.equals(fullHd)) {
				throw new AssertionError("gui scale " + scale + " changed the map to " + scaled + " from " + fullHd);
			}
		}
		setGuiScale(context, guiScale);

		assertEquals("map at 2560x1440", 486, frame(context, "scale-1440p", 2560, 1440).size());
		assertEquals("map at 3840x2160", 648, frame(context, "scale-2160p", 3840, 2160).size());

		// 縦長のウィンドウでは幅に合わせる
		assertEquals("map at 1080x1920", 324, frame(context, "scale-portrait", 1080, 1920).size());

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

	// ステータス効果のアイコンと重ならない
	private static void testEffects(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		int guiScale = context.computeOnClient(client -> client.getWindow().getGuiScale());

		singleplayer.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst()
				.addEffect(new MobEffectInstance(MobEffects.SPEED, 2000)));
		context.waitTicks(3);
		assertEquals("map under a beneficial effect", 25 * guiScale + 7, frame(context, "effect-beneficial", 854, 480).y());

		singleplayer.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst()
				.addEffect(new MobEffectInstance(MobEffects.POISON, 2000)));
		context.waitTicks(3);
		assertEquals("map under a harmful effect", 51 * guiScale + 7, frame(context, "effect-harmful", 854, 480).y());

		// アイコンを出さない効果ではずらさない
		singleplayer.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			player.removeAllEffects();
			player.addEffect(new MobEffectInstance(MobEffects.POISON, 2000, 0, false, false, false));
		});
		context.waitTicks(3);
		assertEquals("map with a hidden effect", 7, frame(context, "effect-hidden", 854, 480).y());

		singleplayer.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst().removeAllEffects());
		context.waitTicks(3);
		assertEquals("map without effects", 7, frame(context, "effect-none", 854, 480).y());
	}

	// F1 と F3 では出さない
	private static void testHidden(ClientGameTestContext context) {
		context.runOnClient(client -> client.gui.hud.toggle());
		assertNoFrame(context, "hidden-f1");
		context.runOnClient(client -> client.gui.hud.toggle());

		context.runOnClient(client -> client.debugEntries.setOverlayVisible(true));
		assertNoFrame(context, "hidden-f3");
		context.runOnClient(client -> client.debugEntries.setOverlayVisible(false));

		// Jori で無効にされている間は出さず、M キーでもマップ画面を開かない
		context.runOnClient(client -> Minimap.setActive(false));
		try {
			assertNoFrame(context, "hidden-inactive");
			toggleMap(context);
			if (context.computeOnClient(client -> client.gui.screen()) != null) {
				throw new AssertionError("map screen should not open while inactive");
			}
		} finally {
			context.runOnClient(client -> Minimap.setActive(true));
		}

		frame(context, "hidden-restored", 854, 480);
	}

	private static void testPins(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		assertPins(OVERWORLD);

		// 刺すキーはマップ画面の中でだけ効き、外でピンを操作するキーはない
		List<String> keys = context.computeOnClient(client -> Arrays.stream(client.options.keyMappings)
				.map(KeyMapping::getName)
				.filter(name -> name.startsWith("key.minimap."))
				.toList());
		if (!keys.equals(List.of("key.minimap.open_map", "key.minimap.place_pin"))) {
			throw new AssertionError("only the map and place keys should be registered but was " + keys);
		}
		context.getInput().pressKey(MinimapClient.placeKey);
		context.getInput().pressKey(InputConstants.KEY_N);
		context.waitTicks(2);
		assertPins(OVERWORLD);

		Pins.Pin first = new Pins.Pin(OVERWORLD, PLAYER_X, PLAYER_Z);
		place(context, first);
		assertPins(OVERWORLD, first);

		// 同じ場所には増えない
		place(context, new Pins.Pin(OVERWORLD, PLAYER_X, PLAYER_Z, "Other"));
		assertPins(OVERWORLD, first);

		// 離れると、離れた分だけずれたところに見える
		teleport(context, singleplayer, PLAYER_X + 25, PLAYER_Z - 15);
		map(context, "pin-near", 854, 480).assertPin(first);
		map(context, "pin-near-1080p", 1920, 1080).assertPin(first);
		context.getInput().resizeWindow(854, 480);
		context.waitTicks(3);

		// 範囲外のピンは方角を保って縁に出る。抜くと消えることで確かめる
		teleport(context, singleplayer, PLAYER_X + 400, PLAYER_Z);
		int withPin = map(context, "pin-far", 854, 480).farPinPixel();
		context.runOnClient(client -> MinimapClient.PINS.remove(first));
		context.waitTicks(2);
		assertPins(OVERWORLD);
		int withoutPin = map(context, "pin-far-removed", 854, 480).farPinPixel();
		if (withPin == withoutPin) {
			throw new AssertionError("far pin should be drawn at the west edge but pixel stayed " + Integer.toHexString(withPin));
		}

		// 開き直しの確認用に 1 本残す
		teleport(context, singleplayer, PLAYER_X, PLAYER_Z);
		place(context, first);
		assertPins(OVERWORLD, first);
	}

	private static void place(ClientGameTestContext context, Pins.Pin pin) {
		context.runOnClient(client -> MinimapClient.PINS.place(pin));
		context.waitTicks(2);
	}

	private static void testMapScreen(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		Pins.Pin first = new Pins.Pin(OVERWORLD, PLAYER_X, PLAYER_Z);
		int playerX = PLAYER_X + 5;
		int playerZ = PLAYER_Z + 5;
		teleport(context, singleplayer, playerX, playerZ);

		// M と Esc で閉じる
		assertMapOpen(context, false);
		toggleMap(context);
		assertMapOpen(context, true);
		toggleMap(context);
		assertMapOpen(context, false);
		toggleMap(context);
		assertMapOpen(context, true);
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		context.waitTicks(2);
		assertMapOpen(context, false);

		toggleMap(context);
		moveCursorAway(context);
		context.waitTicks(MAP_REFRESH_TICKS);
		assertScale(context, 2);

		// ワールドの位置どおりに出る。全画面なのでミニマップは隠れる
		ScreenView view = new ScreenView(854, 480, 2, playerX, playerZ);
		view.assertBlock(context, "map-open", SEAM, SEAM, MapColor.FIRE);
		view.assertBlock(context, "map-open-stone", SEAM + 3, SEAM, MapColor.STONE);
		view.assertPin(context, "map-open-pin", first);
		assertNoFrame(context, "map-no-minimap");

		// 刺す・抜く操作が左下に出る。名前の入力中は隠れる
		assertHints(context, "map-hints", true);

		// 何もないところの左クリックでは刺さらない
		Pins.Pin clicked = new Pins.Pin(OVERWORLD, playerX + 20, playerZ - 10);
		click(context, view.x(clicked.x()), view.y(clicked.z()));
		assertPins(OVERWORLD, first);
		assertNotEditing(context);

		// 刺すキーでカーソルの位置に刺す
		placeAt(context, view.x(clicked.x()), view.y(clicked.z()));
		assertPins(OVERWORLD, first, clicked);
		assertHints(context, "map-hints-editing", false);
		confirmName(context);
		assertHints(context, "map-hints-restored", true);
		view.assertPin(context, "map-pin-placed", clicked);

		// ピンの上の左クリックと刺すキーでは、抜けず、増えず、入力も始まらない。頭の上でも同じ
		click(context, view.x(clicked.x()), view.y(clicked.z()));
		click(context, view.x(clicked.x()) + 2, view.y(clicked.z()) - 8);
		placeAt(context, view.x(clicked.x()), view.y(clicked.z()));
		placeAt(context, view.x(clicked.x()) + 2, view.y(clicked.z()) - 8);
		assertPins(OVERWORLD, first, clicked);
		assertNotEditing(context);

		// Shift + 左クリックで抜く
		shiftClick(context, view.x(clicked.x()), view.y(clicked.z()));
		assertPins(OVERWORLD, first);

		// 何もないところの Shift + 左クリックでは刺さらない
		shiftClick(context, view.x(clicked.x()), view.y(clicked.z()));
		assertPins(OVERWORLD, first);
		assertNotEditing(context);

		// ピンの頭を押しても抜ける
		shiftClick(context, view.x(first.x()) + 2, view.y(first.z()) - 8);
		assertPins(OVERWORLD);
		placeAt(context, view.x(first.x()), view.y(first.z()));
		assertPins(OVERWORLD, first);
		confirmName(context);

		testRebinding(context, view, new Pins.Pin(OVERWORLD, playerX - 20, playerZ + 10), first);

		// ドラッグは地図を動かすだけでピンは刺さらない
		context.getInput().setCursorPos(300, 300);
		context.waitTick();
		context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTick();
		context.getInput().setCursorPos(360, 340);
		context.waitTick();
		context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTick();
		moveCursorAway(context);
		assertPins(OVERWORLD, first);
		view = view.panned(60, 40);
		view.assertBlock(context, "map-dragged", SEAM, SEAM, MapColor.FIRE);

		// ピンの上から Shift を押してドラッグしても、抜けずに地図が動く
		int dragX = view.x(first.x());
		int dragY = view.y(first.z());
		context.getInput().setCursorPos(dragX, dragY);
		context.waitTick();
		context.getInput().holdShift();
		context.waitTick();
		context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTick();
		context.getInput().setCursorPos(dragX - 30, dragY + 20);
		context.waitTick();
		context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTick();
		context.getInput().releaseShift();
		context.waitTick();
		moveCursorAway(context);
		assertPins(OVERWORLD, first);
		view = view.panned(-30, 20);
		view.assertBlock(context, "map-shift-dragged", SEAM, SEAM, MapColor.FIRE);

		// ホイールではカーソルの下の地点が動かない
		int anchorX = view.x(SEAM);
		int anchorY = view.y(SEAM);
		context.getInput().setCursorPos(anchorX, anchorY);
		context.waitTick();
		context.getInput().scroll(1);
		context.waitTick();
		assertScale(context, 4);
		moveCursorAway(context);
		view = new ScreenView(854, 480, 4.0, SEAM + 0.5 - (anchorX - 427) / 4.0, SEAM + 0.5 - (anchorY - 240) / 4.0);
		view.assertBlock(context, "map-zoomed", SEAM + 1, SEAM + 1, MapColor.FIRE);
		view.assertBlock(context, "map-zoomed-stone", SEAM + 3, SEAM, MapColor.STONE);

		// 倍率には上限と下限がある
		scroll(context, 3);
		assertScale(context, 8);
		scroll(context, -8);
		assertScale(context, 0.25);

		// 開き直すと自分の位置に戻り、倍率は保つ
		toggleMap(context);
		toggleMap(context);
		assertScale(context, 0.25);
		moveCursorAway(context);

		// 描画距離の外にも刺せる
		Pins.Pin far = new Pins.Pin(OVERWORLD, playerX + 1600, playerZ);
		placeAt(context, 427 + 400, 240);
		assertPins(OVERWORLD, first, far);
		confirmName(context);
		shiftClick(context, 427 + 400, 240);
		assertPins(OVERWORLD, first);

		scroll(context, 3);
		assertScale(context, 2);

		// GUI スケールや解像度を変えても、開いたままカーソルの位置が合う
		toggleMap(context);
		toggleMap(context);
		int guiScale = context.computeOnClient(client -> client.options.guiScale().get());
		context.getInput().resizeWindow(1920, 1080);
		context.waitTicks(3);
		for (int scale : new int[] {1, 3}) {
			setGuiScale(context, scale);
			moveCursorAway(context);
			assertScale(context, 4);
			ScreenView large = new ScreenView(1920, 1080, 4, playerX, playerZ);
			large.assertBlock(context, "map-1080p-gui" + scale, SEAM, SEAM, MapColor.FIRE);
			Pins.Pin scaled = new Pins.Pin(OVERWORLD, playerX + 30, playerZ + 12);
			placeAt(context, large.x(scaled.x()), large.y(scaled.z()));
			assertPins(OVERWORLD, first, scaled);
			confirmName(context);
			shiftClick(context, large.x(scaled.x()), large.y(scaled.z()));
			assertPins(OVERWORLD, first);
		}
		setGuiScale(context, guiScale);
		context.getInput().resizeWindow(854, 480);
		context.waitTicks(3);

		toggleMap(context);
		assertMapOpen(context, false);
		frame(context, "map-closed", 854, 480);
		teleport(context, singleplayer, PLAYER_X, PLAYER_Z);
	}

	private static void testPinNames(ClientGameTestContext context, TestSingleplayerContext singleplayer, TestWorldSave save) {
		Pins.Pin first = new Pins.Pin(OVERWORLD, PLAYER_X, PLAYER_Z);
		int x = PLAYER_X + 12;
		int z = PLAYER_Z + 8;
		teleport(context, singleplayer, PLAYER_X + 2, PLAYER_Z - 25);
		ScreenView view = new ScreenView(854, 480, 2, PLAYER_X + 2, PLAYER_Z - 25);
		toggleMap(context);
		moveCursorAway(context);

		// 刺すとそのまま名前を入力できる。実際の打鍵と同じく、刺すキーの押下と離す間に届く文字は名前に入らない
		context.getInput().setCursorPos(view.x(x), view.y(z));
		context.waitTick();
		context.getInput().holdKey(MinimapClient.placeKey);
		context.getInput().typeChars("b");
		context.getInput().releaseKey(MinimapClient.placeKey);
		context.waitTick();
		moveCursorAway(context);
		assertPins(OVERWORLD, first, new Pins.Pin(OVERWORLD, x, z));

		// 入力中は M と刺すキーも文字として入り、地図は閉じず、ピンも増えない
		context.getInput().typeChars("m 拠点");
		context.getInput().pressKey(MinimapClient.mapKey);
		context.getInput().pressKey(MinimapClient.placeKey);
		context.getInput().typeChars("b");
		context.waitTicks(2);
		assertMapOpen(context, true);
		confirmName(context);
		Pins.Pin named = new Pins.Pin(OVERWORLD, x, z, "m 拠点b");
		assertPins(OVERWORLD, first, named);
		assertMapOpen(context, true);
		assertPinFileContains(save, "minecraft:overworld " + x + " " + z + " m 拠点b\n");
		view.assertPin(context, "pin-named", named);

		// 右クリックで変更できる
		rightClick(context, view.x(x), view.y(z));
		context.getInput().typeChars("2");
		confirmName(context);
		Pins.Pin renamed = new Pins.Pin(OVERWORLD, x, z, "m 拠点b2");
		assertPins(OVERWORLD, first, renamed);

		// Esc は入力だけを取り消して、地図は閉じない
		rightClick(context, view.x(x), view.y(z));
		context.getInput().typeChars("x");
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		context.waitTicks(2);
		assertMapOpen(context, true);
		assertPins(OVERWORLD, first, renamed);

		// 外を押すと確定するだけで、新しいピンは刺さらない
		rightClick(context, view.x(x), view.y(z));
		context.getInput().typeChars("3");
		click(context, 40, 400);
		Pins.Pin clicked = new Pins.Pin(OVERWORLD, x, z, "m 拠点b23");
		assertPins(OVERWORLD, first, clicked);

		// 入力中にウィンドウの大きさが変わっても文字は残る
		rightClick(context, view.x(x), view.y(z));
		context.getInput().resizeWindow(1000, 600);
		context.waitTicks(3);
		context.getInput().typeChars("4");
		confirmName(context);
		context.getInput().resizeWindow(854, 480);
		context.waitTicks(3);
		assertPins(OVERWORLD, first, new Pins.Pin(OVERWORLD, x, z, "m 拠点b234"));

		// 空白だけの名前は名前なしになる
		rightClick(context, view.x(x), view.y(z));
		context.runOnClient(client -> {
			((EditBox) client.gui.screen().getFocused()).setValue("   ");
		});
		confirmName(context);
		Pins.Pin blank = new Pins.Pin(OVERWORLD, x, z);
		assertPins(OVERWORLD, first, blank);

		// 名前付きのスクリーンショットを残して片付ける
		rightClick(context, view.x(x), view.y(z));
		context.getInput().typeChars("Base 拠点");
		confirmName(context);
		context.takeScreenshot("pin-name-label");
		shiftClick(context, view.x(x), view.y(z));
		assertPins(OVERWORLD, first);

		toggleMap(context);
		teleport(context, singleplayer, PLAYER_X, PLAYER_Z);
	}

	// 描画距離の外も、サーバーに読み込ませて（なければ生成させて）マップ画面に出す
	private static void testFarTerrain(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		teleport(context, singleplayer, PLAYER_X, PLAYER_Z);
		int renderDistance = context.computeOnClient(client -> client.options.getEffectiveRenderDistance());
		ChunkPos home = new ChunkPos(PLAYER_X >> 4, PLAYER_Z >> 4);
		// 目印を置いたチャンクと、何もないチャンク。どちらもクライアントは持っていない
		ChunkPos marked = new ChunkPos(home.x() + renderDistance + 8, home.z());
		ChunkPos plain = new ChunkPos(home.x() - renderDistance - 12, home.z() + 6);
		// 手元の地形のテクスチャの角。読み込まれていないので透明になり、下の遠くの地形が見える
		ChunkPos corner = new ChunkPos(home.x() + renderDistance + 1, home.z() + renderDistance);
		singleplayer.getServer().runOnServer(server -> fill(server.overworld(),
				new BlockPos(marked.getMinBlockX(), FLOOR_Y, marked.getMinBlockZ()),
				new BlockPos(marked.getMaxBlockX(), FLOOR_Y, marked.getMaxBlockZ()), Blocks.GOLD_BLOCK));

		// 前に地図を開いたときの色が残っていても、近づいて読み込めば捨て、離れてから開くと今の色で描く
		teleport(context, singleplayer, marked.getMiddleBlockX(), marked.getMiddleBlockZ());
		context.waitFor(client -> clientHasChunk(client, marked));
		if (context.computeOnClient(client -> MinimapClient.FAR_TERRAIN.colors(OVERWORLD, marked.x(), marked.z())) != null) {
			throw new AssertionError("chunks loaded by the client should be forgotten");
		}
		teleport(context, singleplayer, PLAYER_X, PLAYER_Z);
		context.waitFor(client -> !clientHasChunk(client, marked));
		for (ChunkPos chunk : List.of(marked, plain, corner)) {
			if (context.computeOnClient(client -> clientHasChunk(client, chunk))) {
				throw new AssertionError("far chunk " + chunk + " should be outside the render distance " + renderDistance);
			}
		}

		toggleMap(context);
		zoomAtCenter(context, -3);
		assertScale(context, 0.25);
		ScreenView view = new ScreenView(854, 480, 0.25, PLAYER_X + 0.5, PLAYER_Z + 0.5);
		waitForFarTerrain(context, marked, plain, corner);
		view.assertBlock(context, "far-terrain-marked", marked.getMiddleBlockX(), marked.getMiddleBlockZ(), MapColor.GOLD);
		view.assertBlock(context, "far-terrain-plain", plain.getMiddleBlockX(), plain.getMiddleBlockZ(), MapColor.GRASS);
		view.assertBlock(context, "far-terrain-corner", corner.getMiddleBlockX(), corner.getMiddleBlockZ(), MapColor.GRASS);

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

		// 範囲の外を直接頼んでも読み込まない
		ChunkPos beyond = new ChunkPos(home.x() + TerrainServer.RADIUS + 5, home.z());
		context.runOnClient(client -> ClientPlayNetworking.send(new TerrainRequest(List.of(beyond.pack()))));
		context.waitTicks(40);
		if (context.computeOnClient(client -> MinimapClient.FAR_TERRAIN.colors(OVERWORLD, beyond.x(), beyond.z())) != null) {
			throw new AssertionError("chunks beyond the radius should not be sent");
		}

		toggleMap(context);
		zoomAtCenter(context, 3);
		assertScale(context, 2);
		toggleMap(context);
	}

	// ClientLevel.hasChunk は常に true を返す
	private static boolean clientHasChunk(Minecraft client, ChunkPos chunk) {
		return client.level.getChunkSource().getChunk(chunk.x(), chunk.z(), ChunkStatus.FULL, false) != null;
	}

	// カーソルを真ん中に置いて回すと、地図の中心が動かない
	private static void zoomAtCenter(ClientGameTestContext context, int steps) {
		context.getInput().setCursorPos(427, 240);
		context.waitTick();
		scroll(context, steps);
		moveCursorAway(context);
	}

	// 生成を待つ。テクスチャは届いた次の tick までに描かれる
	private static void waitForFarTerrain(ClientGameTestContext context, ChunkPos... chunks) {
		context.waitFor(client -> Arrays.stream(chunks)
				.allMatch(chunk -> MinimapClient.FAR_TERRAIN.colors(client.level.dimension().identifier(), chunk.x(), chunk.z()) != null), 2400);
		context.waitTicks(15);
	}

	private static void confirmName(ClientGameTestContext context) {
		context.getInput().pressKey(InputConstants.KEY_RETURN);
		context.waitTicks(2);
	}

	private static void rightClick(ClientGameTestContext context, int x, int y) {
		context.getInput().setCursorPos(x, y);
		context.waitTick();
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
		context.waitTick();
		moveCursorAway(context);
	}

	private static void assertPinFileContains(TestWorldSave save, String line) {
		try {
			String actual = Files.readString(save.getSaveDirectory().resolve("minimap_pins.txt"));
			if (!actual.contains(line)) {
				throw new AssertionError("pin file should contain " + line + " but was " + actual);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void testBeams(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		context.runOnClient(client -> client.options.cloudStatus().set(CloudStatus.OFF));
		teleport(context, singleplayer, PLAYER_X, PLAYER_Z);

		// 近くのピン
		Pins.Pin near = new Pins.Pin(OVERWORLD, PLAYER_X + 20, PLAYER_Z - 15);
		lookAtHorizon(context, near);
		assertBeamVisible(context, "beam-near", near);

		// 読み込まれていない遠くのピンも方角が分かる
		Pins.Pin far = new Pins.Pin(OVERWORLD, PLAYER_X - 2000, PLAYER_Z + 1000);
		lookAtHorizon(context, far);
		assertBeamVisible(context, "beam-far", far);

		// 違う次元のピンは出ない
		Pins.Pin nether = new Pins.Pin(NETHER, far.x(), far.z());
		assertEquals("beam from another dimension", 0, beamPixels(context, "beam-other-dimension", nether));

		// F1 では出ない
		context.runOnClient(client -> client.gui.hud.toggle());
		assertEquals("beam with hidden hud", 0, beamPixels(context, "beam-hidden", far));
		context.runOnClient(client -> client.gui.hud.toggle());

		// Jori で無効にされている間は出ない
		context.runOnClient(client -> Minimap.setActive(false));
		try {
			assertEquals("beam while inactive", 0, beamPixels(context, "beam-inactive", far));
		} finally {
			context.runOnClient(client -> Minimap.setActive(true));
		}

		// 名前は柱に添えて出る。名前がなければ出ない
		Pins.Pin named = new Pins.Pin(OVERWORLD, near.x(), near.z(), "Base");
		lookAtHorizon(context, near);
		int guiScale = context.computeOnClient(client -> client.getWindow().getGuiScale());
		context.runOnClient(client -> MinimapClient.PINS.place(near));
		context.waitTicks(2);
		assertEquals("label of an unnamed pin", 0, labelPixels(context, "label-unnamed", guiScale));
		context.runOnClient(client -> MinimapClient.PINS.rename(near, named.name()));
		context.waitTicks(2);
		if (labelPixels(context, "label-named", guiScale) == 0) {
			throw new AssertionError("name of the pin should be drawn beside the beam");
		}

		// 真後ろのピンの名前は出ない
		context.getInput().lookAt(new BlockPos(PLAYER_X - 20, FEET_Y + 1, PLAYER_Z + 15));
		context.waitTicks(3);
		assertEquals("label behind the camera", 0, labelPixels(context, "label-behind", guiScale));

		context.runOnClient(client -> MinimapClient.PINS.remove(named));
		context.waitTicks(2);
		assertPins(OVERWORLD, new Pins.Pin(OVERWORLD, PLAYER_X, PLAYER_Z));

		// 離れると名前は消えるが、柱は残る。近づくとまた出る
		Pins.Pin distant = new Pins.Pin(OVERWORLD, PLAYER_X + 36, PLAYER_Z, "Far");
		lookAtHorizon(context, distant);
		assertBeamVisible(context, "beam-without-label", distant);
		context.runOnClient(client -> MinimapClient.PINS.place(distant));
		context.waitTicks(2);
		assertEquals("label of a distant pin", 0, labelPixels(context, "label-distant", guiScale));

		teleport(context, singleplayer, PLAYER_X + 8, PLAYER_Z);
		lookAtHorizon(context, distant);
		if (labelPixels(context, "label-in-range", guiScale) == 0) {
			throw new AssertionError("name of the pin should be drawn within range");
		}

		context.runOnClient(client -> MinimapClient.PINS.remove(distant));
		context.waitTicks(2);
		teleport(context, singleplayer, PLAYER_X, PLAYER_Z);
		assertPins(OVERWORLD, new Pins.Pin(OVERWORLD, PLAYER_X, PLAYER_Z));
	}

	// カメラと同じ高さでピンの方を向く。地平線より上の空を背景に柱を読み取れる
	private static void lookAtHorizon(ClientGameTestContext context, Pins.Pin pin) {
		context.getInput().lookAt(new BlockPos(pin.x(), FEET_Y + 1, pin.z()));
		context.waitTicks(3);
	}

	private static void assertBeamVisible(ClientGameTestContext context, String name, Pins.Pin pin) {
		int pixels = beamPixels(context, name, pin);
		if (pixels == 0) {
			throw new AssertionError(name + ": beam should be visible at the center of the screen");
		}
	}

	// ピンがあるときとないときで、画面の中央の列で色が変わるピクセルの数。外れた列は変わらない
	private static int beamPixels(ClientGameTestContext context, String name, Pins.Pin pin) {
		context.runOnClient(client -> MinimapClient.PINS.place(pin));
		context.waitTicks(3);
		Path with = context.takeScreenshot(name + "-with");
		context.runOnClient(client -> MinimapClient.PINS.remove(pin));
		context.waitTicks(3);
		Path without = context.takeScreenshot(name + "-without");
		return Screenshots.read(with, withImage -> Screenshots.read(without, withoutImage -> {
			int width = withImage.getWidth();
			int height = withImage.getHeight();
			int top = height / 8;
			int bottom = height * 3 / 8;
			int off = differingPixels(withImage, withoutImage, width / 2 + 100, width / 2 + 120, top, bottom);
			if (off != 0) {
				throw new AssertionError(name + ": pixels away from the beam changed: " + off);
			}
			return differingPixels(withImage, withoutImage, width / 2 - 10, width / 2 + 10, top, bottom);
		}));
	}

	private static int differingPixels(NativeImage a, NativeImage b, int left, int right, int top, int bottom) {
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

	// 照準のすぐ上にある名前の文字（白）のピクセルの数
	private static int labelPixels(ClientGameTestContext context, String name, int guiScale) {
		return Screenshots.read(context.takeScreenshot(name), image -> {
			int count = 0;
			int centerX = image.getWidth() / 2;
			int centerY = image.getHeight() / 2;
			for (int y = centerY - 15 * guiScale; y < centerY - 6 * guiScale; y++) {
				for (int x = centerX - 30 * guiScale; x < centerX + 30 * guiScale; x++) {
					if (image.getPixel(x, y) == 0xFFFFFFFF) {
						count++;
					}
				}
			}
			return count;
		});
	}

	private static void testOtherPlayers(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		teleport(context, singleplayer, PLAYER_X, PLAYER_Z);
		int faceDx = 20;
		int faceDz = -12;
		map(context, "player-before", 854, 480).assertBlockRelative(faceDx, faceDz, MapColor.STONE);

		GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes("minimap-test".getBytes(StandardCharsets.UTF_8)), "Alex");
		FakePlayer other = singleplayer.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			FakePlayer fake = FakePlayer.get(level, profile);
			fake.setPos(PLAYER_X + faceDx + 0.5, FEET_Y, PLAYER_Z + faceDz + 0.5);
			server.getPlayerList().broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(fake)));
			level.addNewPlayer(fake);
			return fake;
		});
		context.waitTicks(10);
		int players = context.computeOnClient(client -> client.level.players().size());
		assertEquals("players known to the client", 2, players);

		// ミニマップとマップ画面の両方で見える
		Map map = map(context, "player-minimap", 854, 480);
		map.assertNotBlockRelative(faceDx, faceDz, MapColor.STONE);
		map.assertBlockRelative(faceDx + 12, faceDz, MapColor.STONE);
		toggleMap(context);
		moveCursorAway(context);
		ScreenView view = new ScreenView(854, 480, 2, PLAYER_X, PLAYER_Z);
		view.assertNotBlock(context, "player-map", PLAYER_X + faceDx, PLAYER_Z + faceDz, MapColor.STONE);
		view.assertBlock(context, "player-map-away", PLAYER_X + faceDx + 12, PLAYER_Z + faceDz, MapColor.STONE);

		// 地図を動かして画面の外に出しても、縁で方角が分かる
		context.getInput().setCursorPos(700, 240);
		context.waitTick();
		context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTick();
		context.getInput().setCursorPos(100, 240);
		context.waitTick();
		context.getInput().setCursorPos(700, 240);
		context.waitTick();
		context.getInput().setCursorPos(100, 240);
		context.waitTick();
		context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTick();
		moveCursorAway(context);
		context.takeScreenshot("player-map-panned");
		toggleMap(context);

		// 動くとついてくる
		int movedDx = -24;
		int movedDz = 18;
		singleplayer.getServer().runOnServer(server -> other.setPos(PLAYER_X + movedDx + 0.5, FEET_Y, PLAYER_Z + movedDz + 0.5));
		context.waitTicks(20);
		map = map(context, "player-moved", 854, 480);
		map.assertNotBlockRelative(movedDx, movedDz, MapColor.STONE);
		map.assertBlockRelative(faceDx, faceDz, MapColor.STONE);

		// 透明なプレイヤーは出さない
		singleplayer.getServer().runOnServer(server -> other.setInvisible(true));
		context.waitTicks(5);
		map(context, "player-invisible", 854, 480).assertBlockRelative(movedDx, movedDz, MapColor.STONE);
		singleplayer.getServer().runOnServer(server -> other.setInvisible(false));
		context.waitTicks(5);
		map(context, "player-visible-again", 854, 480).assertNotBlockRelative(movedDx, movedDz, MapColor.STONE);

		// スペクテイターは出さない
		singleplayer.getServer().runOnServer(server -> other.setGameMode(GameType.SPECTATOR));
		context.waitTicks(5);
		map(context, "player-spectator", 854, 480).assertBlockRelative(movedDx, movedDz, MapColor.STONE);
		singleplayer.getServer().runOnServer(server -> other.setGameMode(GameType.SURVIVAL));
		context.waitTicks(5);

		// エンティティが届かない距離の相手も、ロケーターバーの情報で方角が分かる
		Map empty = map(context, "player-far-before", 854, 480);
		singleplayer.getServer().runOnServer(server -> {
			other.setPos(PLAYER_X + 5000.5, FEET_Y, PLAYER_Z + 0.5);
			// FakePlayer は tick されないので、最初の tick を済ませた状態にする。実際のプレイヤーは参加したときに登録される
			other.baseTick();
			server.overworld().getWaypointManager().trackWaypoint(other);
			server.overworld().getWaypointManager().updatePlayer(server.getPlayerList().getPlayers().getFirst());
		});
		context.waitTicks(20);
		assertEquals("far player should not be a tracked entity", 1, (int) context.computeOnClient(client -> client.level.players().size()));
		map = map(context, "player-far", 854, 480);
		int edge = 80 - 5;
		if (map.pixelRelative(edge, 0) == empty.pixelRelative(edge, 0)) {
			throw new AssertionError("far player should be drawn at the east edge of the minimap");
		}
		map.assertBlockRelative(-edge, 0, MapColor.GRASS);

		singleplayer.getServer().runOnServer(server -> {
			server.overworld().getWaypointManager().untrackWaypoint(other);
			server.overworld().removePlayerImmediately(other, Entity.RemovalReason.DISCARDED);
			server.getPlayerList().broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(profile.id())));
		});
		context.waitTicks(10);
		map = map(context, "player-left", 854, 480);
		if (map.pixelRelative(edge, 0) != empty.pixelRelative(edge, 0)) {
			throw new AssertionError("player who left should disappear from the minimap");
		}
	}

	// 専用サーバーに接続しても動く。ピンはサーバーごとのファイルに保存され、他のプレイヤーは通信で届く
	private static void testDedicatedServer(ClientGameTestContext context) {
		try {
			// 前回の実行のピンが残らないようにする
			Path pinDirectory = Pins.serverFile("x").getParent();
			if (Files.isDirectory(pinDirectory)) {
				try (Stream<Path> files = Files.list(pinDirectory)) {
					for (Path file : files.toList()) {
						Files.delete(file);
					}
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		try (TestDedicatedServerContext server = TestServers.create(context)) {
			Pins.Pin pin;
			Pins.Pin named;
			try (TestDedicatedServerConnection connection = server.connect()) {
				connection.waitForChunksRender();
				context.waitTicks(40);
				context.runOnClient(client -> client.gui.toastManager().clear());
				context.waitTicks(3);
				frame(context, "server-minimap", 854, 480);

				int[] feet = context.computeOnClient(client -> new int[] {client.player.getBlockX(), client.player.getBlockZ()});
				toggleMap(context);
				moveCursorAway(context);
				context.waitTicks(MAP_REFRESH_TICKS);
				ScreenView view = new ScreenView(854, 480, 2, feet[0], feet[1]);

				pin = new Pins.Pin(OVERWORLD, feet[0] + 10, feet[1] - 8);
				placeAt(context, view.x(pin.x()), view.y(pin.z()));
				confirmName(context);
				assertPins(OVERWORLD, pin);
				Path file = context.computeOnClient(client -> Pins.serverFile(client.getCurrentServer().ip));
				assertFileEquals(file, "minecraft:overworld " + pin.x() + " " + pin.z() + "\n");

				// 名前も残る
				named = new Pins.Pin(OVERWORLD, feet[0] + 30, feet[1] + 20, "Camp");
				placeAt(context, view.x(named.x()), view.y(named.z()));
				context.getInput().typeChars(named.name());
				confirmName(context);
				assertPins(OVERWORLD, pin, named);
				context.takeScreenshot("server-map");

				// サーバーにも入っていれば、描画距離の外も出る
				int renderDistance = context.computeOnClient(client -> client.options.getEffectiveRenderDistance());
				ChunkPos far = new ChunkPos((feet[0] >> 4) + renderDistance + 8, feet[1] >> 4);
				zoomAtCenter(context, -3);
				waitForFarTerrain(context, far);
				ScreenView farView = new ScreenView(854, 480, 0.25, feet[0] + 0.5, feet[1] + 0.5);
				int farPixel = Screenshots.read(context.takeScreenshot("server-far-terrain"),
						image -> image.getPixel(farView.x(far.getMiddleBlockX()), farView.y(far.getMiddleBlockZ())));
				if (farPixel == EMPTY_COLOR) {
					throw new AssertionError("far terrain from the dedicated server should be drawn");
				}
				zoomAtCenter(context, 3);
				toggleMap(context);
				assertFileEquals(file, "minecraft:overworld " + pin.x() + " " + pin.z() + "\n"
						+ "minecraft:overworld " + named.x() + " " + named.z() + " Camp\n");

				testServerPlayers(context, server, feet[0], feet[1]);
			}

			// 切断するとピンは閉じ、接続し直すと読み直される
			assertPins(OVERWORLD);
			try (TestDedicatedServerConnection connection = server.connect()) {
				connection.waitForChunksRender();
				assertPins(OVERWORLD, pin, named);
			}
		}
	}

	private static void testServerPlayers(ClientGameTestContext context, TestDedicatedServerContext server, int x, int z) {
		int dx = 20;
		int dz = -12;
		int edge = 80 - 5;
		Map before = map(context, "server-player-before", 854, 480);

		GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes("minimap-server-test".getBytes(StandardCharsets.UTF_8)), "Steve");
		double y = context.computeOnClient(client -> client.player.getY());
		FakePlayer other = server.computeOnServer(minecraft -> {
			ServerLevel level = minecraft.overworld();
			FakePlayer fake = FakePlayer.get(level, profile);
			fake.setPos(x + dx + 0.5, y, z + dz + 0.5);
			minecraft.getPlayerList().broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(fake)));
			level.addNewPlayer(fake);
			return fake;
		});
		context.waitTicks(20);
		assertEquals("players known to the client", 2, (int) context.computeOnClient(client -> client.level.players().size()));
		if (map(context, "server-player", 854, 480).pixelRelative(dx, dz) == before.pixelRelative(dx, dz)) {
			throw new AssertionError("nearby player should be drawn on the minimap");
		}

		// エンティティが届かない距離の相手は、ロケーターバーのパケットで方角が分かる
		server.runOnServer(minecraft -> {
			ServerLevel level = minecraft.overworld();
			other.setPos(x + 5000.5, y, z + 0.5);
			other.baseTick();
			level.getWaypointManager().trackWaypoint(other);
			level.getWaypointManager().updatePlayer(minecraft.getPlayerList().getPlayers().getFirst());
		});
		context.waitTicks(20);
		assertEquals("far player should not be a tracked entity", 1, (int) context.computeOnClient(client -> client.level.players().size()));
		if (map(context, "server-player-far", 854, 480).pixelRelative(edge, 0) == before.pixelRelative(edge, 0)) {
			throw new AssertionError("far player should be drawn at the east edge of the minimap");
		}

		server.runOnServer(minecraft -> {
			minecraft.overworld().getWaypointManager().untrackWaypoint(other);
			minecraft.overworld().removePlayerImmediately(other, Entity.RemovalReason.DISCARDED);
			minecraft.getPlayerList().broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(profile.id())));
		});
		context.waitTicks(10);
		if (map(context, "server-player-left", 854, 480).pixelRelative(edge, 0) != before.pixelRelative(edge, 0)) {
			throw new AssertionError("player who left should disappear from the minimap");
		}
	}

	private static void assertFileEquals(Path file, String expected) {
		try {
			String actual = Files.readString(file);
			if (!actual.equals(expected)) {
				throw new AssertionError("expected " + file + " to be " + expected + " but was " + actual);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void shiftClick(ClientGameTestContext context, int x, int y) {
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

	private static void assertNotEditing(ClientGameTestContext context) {
		boolean editing = context.computeOnClient(client -> client.gui.screen().getFocused() != null);
		if (editing) {
			throw new AssertionError("name box should not be open");
		}
	}

	// 左下の操作の説明を 1 行ずつ読む。文字は真っ白で、地形や背景には出ない色
	private static void assertHints(ClientGameTestContext context, String name, boolean visible) {
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

	private static void toggleMap(ClientGameTestContext context) {
		context.getInput().pressKey(MinimapClient.mapKey);
		context.waitTicks(2);
	}

	private static void click(ClientGameTestContext context, int x, int y) {
		context.getInput().setCursorPos(x, y);
		context.waitTick();
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTick();
		moveCursorAway(context);
	}

	private static void placeAt(ClientGameTestContext context, int x, int y) {
		context.getInput().setCursorPos(x, y);
		context.waitTick();
		context.getInput().pressKey(MinimapClient.placeKey);
		context.waitTick();
		moveCursorAway(context);
	}

	// 刺すキーは割り当てを変えられる。マウスのボタンにも割り当てられ、左下の説明もいまのキーになる
	private static void testRebinding(ClientGameTestContext context, ScreenView view, Pins.Pin pin, Pins.Pin first) {
		Path before = context.takeScreenshot("map-hints-default");
		rebindPlaceKey(context, InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_R));
		Path after = context.takeScreenshot("map-hints-rebound");
		int guiScale = context.computeOnClient(client -> client.getWindow().getGuiScale());
		Screenshots.read(before, beforeImage -> Screenshots.read(after, afterImage -> {
			int rowHeight = 11 * guiScale;
			int bottom = beforeImage.getHeight() - 4 * guiScale;
			int width = 200 * guiScale;
			if (differingPixels(beforeImage, afterImage, 0, width, bottom - 2 * rowHeight, bottom - rowHeight) == 0) {
				throw new AssertionError("place hint should show the rebound key");
			}
			int removeRow = differingPixels(beforeImage, afterImage, 0, width, bottom - rowHeight, bottom);
			if (removeRow != 0) {
				throw new AssertionError("remove hint should not change but " + removeRow + " pixels did");
			}
			return null;
		}));

		// 元のキーでは刺さらない
		context.getInput().setCursorPos(view.x(pin.x()), view.y(pin.z()));
		context.waitTick();
		context.getInput().pressKey(InputConstants.KEY_B);
		context.waitTick();
		moveCursorAway(context);
		assertPins(OVERWORLD, first);
		assertNotEditing(context);

		placeAt(context, view.x(pin.x()), view.y(pin.z()));
		assertPins(OVERWORLD, first, pin);
		confirmName(context);
		shiftClick(context, view.x(pin.x()), view.y(pin.z()));
		assertPins(OVERWORLD, first);

		rebindPlaceKey(context, InputConstants.Type.MOUSE.getOrCreate(InputConstants.MOUSE_BUTTON_MIDDLE));
		context.getInput().setCursorPos(view.x(pin.x()), view.y(pin.z()));
		context.waitTick();
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_MIDDLE);
		context.waitTick();
		moveCursorAway(context);
		assertPins(OVERWORLD, first, pin);
		confirmName(context);
		shiftClick(context, view.x(pin.x()), view.y(pin.z()));
		assertPins(OVERWORLD, first);

		rebindPlaceKey(context, MinimapClient.placeKey.getDefaultKey());
	}

	private static void rebindPlaceKey(ClientGameTestContext context, InputConstants.Key key) {
		context.runOnClient(client -> {
			MinimapClient.placeKey.setKey(key);
			KeyMapping.resetMapping();
		});
		context.waitTicks(2);
	}

	private static void scroll(ClientGameTestContext context, int steps) {
		for (int i = 0; i < Math.abs(steps); i++) {
			context.getInput().scroll(Math.signum(steps));
			context.waitTick();
		}
	}

	// ホバーで大きくなったピンがカーソルの下に残らないようにする
	private static void moveCursorAway(ClientGameTestContext context) {
		context.getInput().setCursorPos(1, 1);
		context.waitTick();
	}

	private static void assertMapOpen(ClientGameTestContext context, boolean open) {
		boolean actual = context.computeOnClient(client -> client.gui.screen() instanceof MapScreen);
		if (actual != open) {
			throw new AssertionError("map screen open should be " + open + " but was " + actual);
		}
	}

	private static void assertScale(ClientGameTestContext context, double expected) {
		double actual = context.computeOnClient(client -> ((MapScreen) client.gui.screen()).scale());
		if (actual != expected) {
			throw new AssertionError("map scale should be " + expected + " but was " + actual);
		}
	}

	private static void assertPixel(ClientGameTestContext context, String name, int x, int y, int expected) {
		int pixel = Screenshots.read(context.takeScreenshot(name), image -> image.getPixel(x, y));
		if (pixel != expected) {
			throw new AssertionError(name + ": expected " + Integer.toHexString(expected) + " at " + x + ", " + y
					+ " but was " + Integer.toHexString(pixel));
		}
	}

	// マップ画面の座標。テストでは実装に頼らず自分で計算する
	private record ScreenView(int width, int height, double scale, double centerX, double centerZ) {
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

		void assertPin(ClientGameTestContext context, String name, Pins.Pin pin) {
			int unit = Math.max(1, Math.round(Math.min(width, height) / 540f));
			assertPixel(context, name, x(pin.x()) + 2 * unit, y(pin.z()) - 8 * unit, pin.color());
		}
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
		// 実績のトーストは一時的に地図に重なるので消しておく
		context.runOnClient(client -> client.gui.toastManager().clear());
		context.waitTicks(3);
		Map map = map(context, "nether", 854, 480);
		map.assertBlockRelative(10, 0, MapColor.GOLD);
		map.assertBlockRelative(-10, 10, MapColor.GOLD);

		// マップ画面でも足元を映す。ピンは今いる次元に刺さり、抜いても他の次元のピンは残る
		toggleMap(context);
		moveCursorAway(context);
		ScreenView view = new ScreenView(854, 480, 2, 0, 0);
		view.assertBlock(context, "map-nether", 10, 5, MapColor.GOLD);
		placeAt(context, view.x(10), view.y(5));
		assertPins(NETHER, new Pins.Pin(NETHER, 10, 5));
		confirmName(context);
		assertPins(OVERWORLD, new Pins.Pin(OVERWORLD, PLAYER_X, PLAYER_Z));
		shiftClick(context, view.x(10), view.y(5));
		assertPins(NETHER);
		toggleMap(context);

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

			toggleMap(context);
			moveCursorAway(context);
			ScreenView view = new ScreenView(854, 480, 2, PLAYER_X + 25, PLAYER_Z);
			view.assertBlock(context, "shader-pack-map", SEAM, SEAM, MapColor.FIRE);
			view.assertPin(context, "shader-pack-map-pin", new Pins.Pin(OVERWORLD, PLAYER_X, PLAYER_Z));
			toggleMap(context);

			Pins.Pin beam = new Pins.Pin(OVERWORLD, PLAYER_X + 45, PLAYER_Z - 20);
			lookAtHorizon(context, beam);
			assertBeamVisible(context, "shader-pack-beam", beam);
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
		Screenshots.read(context.takeScreenshot(name), image -> {
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

	private static Map map(ClientGameTestContext context, String name, int width, int height) {
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

	private static boolean isMapColor(int pixel, MapColor color) {
		for (MapColor.Brightness brightness : MapColor.Brightness.values()) {
			if (ARGB.opaque(color.calculateARGBColor(brightness)) == pixel) {
				return true;
			}
		}
		return false;
	}

	private record Frame(int x, int y, int size) {
	}

	// スクリーンショットから切り出した地図。pixels は枠を含む
	private record Map(String name, int[][] pixels, int unit, int playerX, int playerZ) {
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
