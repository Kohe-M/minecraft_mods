package com.omuraisu.minimap.test;

import static com.omuraisu.minimap.test.MapTests.*;
import static com.omuraisu.testkit.Asserts.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.InputConstants;
import com.omuraisu.minimap.Minimap;
import com.omuraisu.testkit.Scenario;
import com.omuraisu.testkit.Screenshots;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.MapColor;

// ミニマップ（HUD）: 大きさ・座標の文字・地形・隠すとき・ステータス効果・他のプレイヤー
public class HudTest implements Scenario {
	@Override
	public String name() {
		return "hud";
	}

	@Override
	public void run(ClientGameTestContext context) {
		resetConfig(context);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			buildFloor(singleplayer);
			teleport(context, singleplayer, PLAYER_X, PLAYER_Z);

			testScale(context);
			testCoordinates(context, singleplayer);
			testTerrain(context, singleplayer);
			testHidden(context);
			testEffects(context, singleplayer);
			testOtherPlayers(context, singleplayer);
		}
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

	// 地図の下に今いるブロックの座標が出る。文字も地図と一緒に大きくなり、GUI スケールの設定には左右されない
	private static void testCoordinates(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		String here = PLAYER_X + ", " + FEET_Y + ", " + PLAYER_Z;
		coordinates(context, "coordinates-480p", 854, 480, here);
		int[][] fullHd = coordinates(context, "coordinates-1080p", 1920, 1080, here);

		int guiScale = context.computeOnClient(client -> client.options.guiScale().get());
		for (int scale : new int[] {1, 2, 4}) {
			setGuiScale(context, scale);
			int[][] scaled = coordinates(context, "coordinates-1080p-gui" + scale, 1920, 1080, here);
			if (!Arrays.deepEquals(scaled, fullHd)) {
				throw new AssertionError("gui scale " + scale + " changed the coordinates under the map");
			}
		}
		setGuiScale(context, guiScale);

		// 1 ブロック動くと変わる。負の座標はマップ画面で確かめる（遠くへ行くと、遠くの地形のテストが使うチャンクを生成してしまう）
		teleport(context, singleplayer, PLAYER_X + 1, PLAYER_Z);
		int[][] moved = coordinates(context, "coordinates-moved", 1920, 1080, (PLAYER_X + 1) + ", " + FEET_Y + ", " + PLAYER_Z);
		if (Arrays.deepEquals(moved, fullHd)) {
			throw new AssertionError("coordinates under the map should change when the player moves");
		}
		teleport(context, singleplayer, PLAYER_X, PLAYER_Z);

		context.getInput().resizeWindow(854, 480);
		context.waitTicks(3);
	}

	// 地図の下の帯の画素を返す。文字は真っ白で、帯の真ん中にそろう
	private static int[][] coordinates(ClientGameTestContext context, String name, int width, int height, String expected) {
		context.getInput().resizeWindow(width, height);
		context.waitTicks(3);
		int textWidth = context.computeOnClient(client -> client.font.width(expected));
		return Screenshots.read(context.takeScreenshot(name), image -> {
			Frame frame = findFrame(image);
			if (frame == null) {
				throw new AssertionError(name + ": map frame not found");
			}
			int unit = frame.size() / 162;
			int mapLeft = frame.x() + unit;
			int mapBottom = frame.y() + unit + 160 * unit;
			int barBottom = mapBottom + 11 * unit;
			assertEquals(name + ": bar left corner", FRAME_COLOR, image.getPixel(frame.x(), barBottom - 1));
			assertEquals(name + ": bar right corner", FRAME_COLOR, image.getPixel(frame.x() + frame.size() - 1, barBottom - 1));

			int[] text = whiteExtent(image, frame.x(), frame.x() + frame.size(), mapBottom, barBottom);
			if (text == null) {
				throw new AssertionError(name + ": coordinates should be drawn under the map");
			}
			// 最後の字の後の 1 画素の間隔は描かれない
			int visible = (textWidth - 1) * unit;
			assertEquals(name + ": coordinates left", mapLeft + (160 * unit - visible) / 2, text[0]);
			assertEquals(name + ": coordinates right", mapLeft + (160 * unit - visible) / 2 + visible - 1, text[1]);
			if (text[2] < mapBottom + unit || text[3] >= barBottom - unit) {
				throw new AssertionError(name + ": coordinates should be inside the bar but rows were " + text[2] + " to " + text[3]
						+ " for the bar " + mapBottom + " to " + barBottom);
			}

			int[][] pixels = new int[barBottom - mapBottom][frame.size()];
			for (int y = 0; y < pixels.length; y++) {
				for (int x = 0; x < frame.size(); x++) {
					pixels[y][x] = image.getPixel(frame.x() + x, mapBottom + y);
				}
			}
			return pixels;
		});
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
}
