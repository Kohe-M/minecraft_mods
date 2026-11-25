package com.omuraisu.minimap.test;

import static com.omuraisu.minimap.test.MapTests.*;
import static com.omuraisu.testkit.Asserts.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.mojang.blaze3d.platform.InputConstants;
import com.omuraisu.minimap.Minimap;
import com.omuraisu.minimap.Pin;
import com.omuraisu.minimap.client.MinimapClient;
import com.omuraisu.minimap.client.Pins;
import com.omuraisu.testkit.Scenario;
import com.omuraisu.testkit.Screenshots;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.MapColor;

// マップ画面とピン（シングルプレイ）: 操作・名前・光の柱・ネザー・シェーダーパック・開き直し
public class MapScreenTest implements Scenario {
	@Override
	public String name() {
		return "map";
	}

	@Override
	public void run(ClientGameTestContext context) {
		resetConfig(context);
		TestWorldSave save;
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			save = singleplayer.getWorldSave();
			buildFloor(singleplayer);
			teleport(context, singleplayer, PLAYER_X, PLAYER_Z);

			testPins(context, singleplayer);
			testMapScreen(context, singleplayer);
			testPinNames(context, singleplayer, save);
			testBeams(context, singleplayer);
			testNether(context, singleplayer);
			if (FabricLoader.getInstance().isModLoaded("iris")) {
				testShaderPack(context, singleplayer);
			}
		}

		// 開き直してもピンが残っている。壊れた行は読み飛ばす
		appendPinFile(save, "broken line\nminecraft:overworld abc 1\nminecraft:overworld 7 8  my  base \n");
		try (TestSingleplayerContext singleplayer = save.open()) {
			singleplayer.getConnection().waitForChunksRender();
			assertPins(OVERWORLD, new Pin(OVERWORLD, PLAYER_X, PLAYER_Z), new Pin(OVERWORLD, 7, 8, "my  base"));
		}

		// 別のワールドには持ち越さない
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();
			assertPins(OVERWORLD);
		}

		testServerFile();
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

		Pin first = new Pin(OVERWORLD, PLAYER_X, PLAYER_Z);
		place(context, first);
		assertPins(OVERWORLD, first);

		// 同じ場所には増えない
		place(context, new Pin(OVERWORLD, PLAYER_X, PLAYER_Z, "Other"));
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

	private static void place(ClientGameTestContext context, Pin pin) {
		context.runOnClient(client -> MinimapClient.PINS.place(pin));
		context.waitTicks(2);
	}

	private static void testMapScreen(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		Pin first = new Pin(OVERWORLD, PLAYER_X, PLAYER_Z);
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

		// 右下にカーソルの位置のブロックの座標が出る
		Pin clicked = new Pin(OVERWORLD, playerX + 20, playerZ - 10);
		int[][] cursor = assertCursorCoordinates(context, "map-cursor", view.x(clicked.x()), view.y(clicked.z()), clicked);
		// 同じ桁数なので幅では分からない。1 ブロックずらすと文字が変わる
		int[][] west = assertCursorCoordinates(context, "map-cursor-west", view.x(clicked.x() - 1), view.y(clicked.z()),
				new Pin(OVERWORLD, clicked.x() - 1, clicked.z()));
		int[][] north = assertCursorCoordinates(context, "map-cursor-north", view.x(clicked.x()), view.y(clicked.z() - 1),
				new Pin(OVERWORLD, clicked.x(), clicked.z() - 1));
		if (Arrays.deepEquals(cursor, west) || Arrays.deepEquals(cursor, north)) {
			throw new AssertionError("cursor coordinates should change when the cursor moves to another block");
		}

		// 何もないところの左クリックでは刺さらない
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

		testRebinding(context, view, new Pin(OVERWORLD, playerX - 20, playerZ + 10), first);

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

		// 負の座標は切り捨てる
		assertCursorCoordinates(context, "map-cursor-negative", 1, 1, new Pin(OVERWORLD,
				Mth.floor(playerX + 0.5 + (1 - 427) / 0.25), Mth.floor(playerZ + 0.5 + (1 - 240) / 0.25)));

		// 描画距離の外にも刺せる。刺す前に出ていた座標に刺さる
		Pin far = new Pin(OVERWORLD, playerX + 1600, playerZ);
		assertCursorCoordinates(context, "map-cursor-far", 427 + 400, 240, far);
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
			Pin scaled = new Pin(OVERWORLD, playerX + 30, playerZ + 12);
			assertCursorCoordinates(context, "map-cursor-1080p-gui" + scale, large.x(scaled.x()), large.y(scaled.z()), scaled);
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

	// 刺すキーは割り当てを変えられる。マウスのボタンにも割り当てられ、左下の説明もいまのキーになる
	private static void testRebinding(ClientGameTestContext context, ScreenView view, Pin pin, Pin first) {
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

	private static void testPinNames(ClientGameTestContext context, TestSingleplayerContext singleplayer, TestWorldSave save) {
		Pin first = new Pin(OVERWORLD, PLAYER_X, PLAYER_Z);
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
		assertPins(OVERWORLD, first, new Pin(OVERWORLD, x, z));

		// 入力中は M と刺すキーも文字として入り、地図は閉じず、ピンも増えない
		context.getInput().typeChars("m 拠点");
		context.getInput().pressKey(MinimapClient.mapKey);
		context.getInput().pressKey(MinimapClient.placeKey);
		context.getInput().typeChars("b");
		context.waitTicks(2);
		assertMapOpen(context, true);
		confirmName(context);
		Pin named = new Pin(OVERWORLD, x, z, "m 拠点b");
		assertPins(OVERWORLD, first, named);
		assertMapOpen(context, true);
		assertPinFileContains(context, save, "minecraft:overworld " + x + " " + z + " m 拠点b\n");
		view.assertPin(context, "pin-named", named);

		// 右クリックで変更できる
		rightClick(context, view.x(x), view.y(z));
		context.getInput().typeChars("2");
		confirmName(context);
		Pin renamed = new Pin(OVERWORLD, x, z, "m 拠点b2");
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
		Pin clicked = new Pin(OVERWORLD, x, z, "m 拠点b23");
		assertPins(OVERWORLD, first, clicked);

		// 入力中にウィンドウの大きさが変わっても文字は残る
		rightClick(context, view.x(x), view.y(z));
		context.getInput().resizeWindow(1000, 600);
		context.waitTicks(3);
		context.getInput().typeChars("4");
		confirmName(context);
		context.getInput().resizeWindow(854, 480);
		context.waitTicks(3);
		assertPins(OVERWORLD, first, new Pin(OVERWORLD, x, z, "m 拠点b234"));

		// 空白だけの名前は名前なしになる
		rightClick(context, view.x(x), view.y(z));
		context.runOnClient(client -> {
			((EditBox) client.gui.screen().getFocused()).setValue("   ");
		});
		confirmName(context);
		Pin blank = new Pin(OVERWORLD, x, z);
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

	private static void assertPinFileContains(ClientGameTestContext context, TestWorldSave save, String line) {
		// 内蔵サーバーが書く
		context.waitTicks(5);
		try {
			String actual = Files.readString(save.getSaveDirectory().resolve("minimap_pins.txt"));
			if (!actual.contains(line)) {
				throw new AssertionError("pin file should contain " + line + " but was " + actual);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void appendPinFile(TestWorldSave save, String text) {
		Path file = save.getSaveDirectory().resolve("minimap_pins.txt");
		try {
			Files.writeString(file, Files.readString(file) + text);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void testBeams(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		context.runOnClient(client -> client.options.cloudStatus().set(CloudStatus.OFF));
		teleport(context, singleplayer, PLAYER_X, PLAYER_Z);

		// 近くのピン
		Pin near = new Pin(OVERWORLD, PLAYER_X + 20, PLAYER_Z - 15);
		lookAtHorizon(context, near);
		assertBeamVisible(context, "beam-near", near);

		// 読み込まれていない遠くのピンも方角が分かる
		Pin far = new Pin(OVERWORLD, PLAYER_X - 2000, PLAYER_Z + 1000);
		lookAtHorizon(context, far);
		assertBeamVisible(context, "beam-far", far);

		// 違う次元のピンは出ない
		Pin nether = new Pin(NETHER, far.x(), far.z());
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
		Pin named = new Pin(OVERWORLD, near.x(), near.z(), "Base");
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
		assertPins(OVERWORLD, new Pin(OVERWORLD, PLAYER_X, PLAYER_Z));

		// 離れると名前は消えるが、柱は残る。近づくとまた出る
		Pin distant = new Pin(OVERWORLD, PLAYER_X + 36, PLAYER_Z, "Far");
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
		assertPins(OVERWORLD, new Pin(OVERWORLD, PLAYER_X, PLAYER_Z));
	}

	// カメラと同じ高さでピンの方を向く。地平線より上の空を背景に柱を読み取れる
	private static void lookAtHorizon(ClientGameTestContext context, Pin pin) {
		context.getInput().lookAt(new BlockPos(pin.x(), FEET_Y + 1, pin.z()));
		context.waitTicks(3);
	}

	private static void assertBeamVisible(ClientGameTestContext context, String name, Pin pin) {
		int pixels = beamPixels(context, name, pin);
		if (pixels == 0) {
			throw new AssertionError(name + ": beam should be visible at the center of the screen");
		}
	}

	// ピンがあるときとないときで、画面の中央の列で色が変わるピクセルの数。外れた列は変わらない
	private static int beamPixels(ClientGameTestContext context, String name, Pin pin) {
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
		assertPins(NETHER, new Pin(NETHER, 10, 5));
		confirmName(context);
		assertPins(OVERWORLD, new Pin(OVERWORLD, PLAYER_X, PLAYER_Z));
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
		context.runOnClient(client -> MapTests.IrisPack.enable());
		context.waitTicks(20);
		singleplayer.getConnection().waitForChunksRender();
		try {
			Map map = map(context, "shader-pack", 854, 480);
			map.assertBlock(SEAM, SEAM, MapColor.FIRE);
			map.assertPin(new Pin(OVERWORLD, PLAYER_X, PLAYER_Z));

			toggleMap(context);
			moveCursorAway(context);
			ScreenView view = new ScreenView(854, 480, 2, PLAYER_X + 25, PLAYER_Z);
			view.assertBlock(context, "shader-pack-map", SEAM, SEAM, MapColor.FIRE);
			view.assertPin(context, "shader-pack-map-pin", new Pin(OVERWORLD, PLAYER_X, PLAYER_Z));
			toggleMap(context);

			Pin beam = new Pin(OVERWORLD, PLAYER_X + 45, PLAYER_Z - 20);
			lookAtHorizon(context, beam);
			assertBeamVisible(context, "shader-pack-beam", beam);
		} finally {
			context.runOnClient(client -> MapTests.IrisPack.disable());
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
}
