package com.omuraisu.xray.test;

import java.nio.file.Path;

import com.mojang.blaze3d.platform.InputConstants;
import com.omuraisu.testkit.Asserts;
import com.omuraisu.testkit.Screenshots;
import com.omuraisu.xray.client.XrayMode;
import com.omuraisu.xray.client.ModeWheelScreen;
import com.omuraisu.xray.client.XrayClient;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

public class XrayClientGameTest implements FabricClientGameTest {
	// ModeWheelScreen と同じ外周の半径
	private static final int RADIUS = 54;
	private static final int FAR = 72;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();

			BlockPos[] positions = singleplayer.getServer().computeOnServer(server -> {
				ServerLevel level = server.overworld();
				BlockPos feet = server.getPlayerList().getPlayers().getFirst().blockPosition();
				BlockPos origin = feet.offset(4, -2, -6);
				for (BlockPos pos : BlockPos.betweenClosed(origin, origin.offset(10, 8, 12))) {
					level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
				}
				place(level, origin.offset(3, 3, 6), Blocks.DIAMOND_ORE);
				place(level, origin.offset(6, 1, 1), Blocks.DEEPSLATE_DIAMOND_ORE);
				place(level, origin.offset(5, 5, 3), Blocks.GOLD_ORE);
				place(level, origin.offset(4, 2, 9), Blocks.IRON_ORE);
				place(level, origin.offset(6, 6, 8), Blocks.COAL_ORE);
				place(level, origin.offset(2, 1, 4), Blocks.REDSTONE_ORE);
				place(level, origin.offset(5, 4, 10), Blocks.EMERALD_ORE);
				place(level, origin.offset(7, 2, 2), Blocks.DEEPSLATE_LAPIS_ORE);
				place(level, origin.offset(3, 5, 7), Blocks.WATER);
				// 石に埋めて光が届かないようにする。手前に鉱石がない位置
				BlockPos chest = origin.offset(5, 2, 4);
				place(level, chest, Blocks.CHEST);
				// ブロックエンティティをふつうに描く 64 ブロックより遠く、描画距離（5 チャンク）より近く
				BlockPos farChest = feet.offset(FAR, 1, 0);
				place(level, farChest, Blocks.CHEST);
				return new BlockPos[] {origin.offset(4, 3, 6), chest, farChest};
			});
			BlockPos center = positions[0];
			BlockPos chest = positions[1];
			BlockPos farChest = positions[2];
			singleplayer.getConnection().waitForChunksRender();
			context.getInput().lookAt(center);
			context.waitTicks(5);
			context.takeScreenshot("xray-off");

			// 長押しでホイール、動かさずに離すと中央の全鉱石
			openWheel(context);
			moveCursor(context, 0, 0);
			context.takeScreenshot("wheel-all");
			context.getInput().releaseKey(XrayClient.toggle);
			context.waitTick();
			assertState(context, true, XrayMode.ALL);
			waitForRender(context, singleplayer);
			context.takeScreenshot("mode-all");

			tap(context);
			assertState(context, false, XrayMode.ALL);

			// ホイールが開くより短い長押しは単押しと同じ
			context.getInput().holdKey(XrayClient.toggle);
			context.waitTicks(XrayClient.HOLD_TICKS - 2);
			if (context.computeOnClient(client -> client.gui.screen()) != null) {
				throw new AssertionError("wheel should not open before " + XrayClient.HOLD_TICKS + " ticks");
			}
			context.getInput().releaseKey(XrayClient.toggle);
			context.waitTicks(2);
			assertState(context, true, XrayMode.ALL);
			tap(context);
			assertState(context, false, XrayMode.ALL);

			// 真上のダイヤを選んで離す
			openWheel(context);
			moveCursor(context, 0, -RADIUS);
			context.takeScreenshot("wheel-diamond");
			context.getInput().releaseKey(XrayClient.toggle);
			context.waitTick();
			assertState(context, true, XrayMode.DIAMOND);
			waitForRender(context, singleplayer);
			context.takeScreenshot("mode-diamond");

			// 単押しは選んだモードのままオン/オフ
			tap(context);
			assertState(context, false, XrayMode.DIAMOND);
			tap(context);
			assertState(context, true, XrayMode.DIAMOND);

			// クリックでも確定する。その後キーを離しても切り替わらない
			int goldIndex = XrayMode.RING.indexOf(XrayMode.GOLD);
			double angle = -Math.PI / 2 + goldIndex * 2 * Math.PI / XrayMode.RING.size();
			openWheel(context);
			moveCursor(context, (int) Math.round(Math.cos(angle) * RADIUS), (int) Math.round(Math.sin(angle) * RADIUS));
			context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTick();
			assertState(context, true, XrayMode.GOLD);
			context.getInput().releaseKey(XrayClient.toggle);
			context.waitTicks(2);
			assertState(context, true, XrayMode.GOLD);
			waitForRender(context, singleplayer);
			context.takeScreenshot("mode-gold");

			tap(context);
			assertState(context, false, XrayMode.GOLD);
			waitForRender(context, singleplayer);
			context.takeScreenshot("xray-off-again");

			// Jori で無効にされている間は透過せず、キーも効かない。戻すと透過し直す
			tap(context);
			assertState(context, true, XrayMode.GOLD);
			context.runOnClient(client -> XrayClient.setActive(false));
			try {
				assertState(context, false, XrayMode.GOLD);
				waitForRender(context, singleplayer);
				context.takeScreenshot("xray-inactive");
				tap(context);
				assertState(context, false, XrayMode.GOLD);
				context.getInput().holdKey(XrayClient.toggle);
				context.waitTicks(10);
				if (context.computeOnClient(client -> client.gui.screen()) != null) {
					throw new AssertionError("wheel should not open while inactive");
				}
				context.getInput().releaseKey(XrayClient.toggle);
				context.waitTick();
			} finally {
				context.runOnClient(client -> XrayClient.setActive(true));
			}
			assertState(context, true, XrayMode.GOLD);
			waitForRender(context, singleplayer);
			context.takeScreenshot("xray-active-again");
			tap(context);
			assertState(context, false, XrayMode.GOLD);

			// 普通のチェストは、どの鉱石を選んでいても最大の明るさで見せる。64 ブロックより遠くても描く
			context.getInput().lookAt(chest);
			waitForRender(context, singleplayer);
			assertChestShown(context, "chest-off", false);
			assertChestLight(context, chest, false);
			assertChestLight(context, farChest, false);
			tap(context);
			assertState(context, true, XrayMode.GOLD);
			waitForRender(context, singleplayer);
			assertChestShown(context, "chest-gold", true);
			assertChestLight(context, chest, true);
			assertChestLight(context, farChest, true);
			tap(context);
			assertState(context, false, XrayMode.GOLD);
		}
	}

	// 画面の中央（チェストを見ている）に、明るい木の色が映っているか
	private static void assertChestShown(ClientGameTestContext context, String name, boolean shown) {
		// 十字の照準が重ならないよう、F1 と同じく HUD を隠して撮る
		context.runOnClient(client -> client.gui.hud.toggle());
		Path path;
		try {
			path = context.takeScreenshot(name);
		} finally {
			context.runOnClient(client -> client.gui.hud.toggle());
		}
		// チェストは 9 ブロック先で 30 画素ほど。黒い縁と継ぎ目を除けば、ほとんどが木の色
		double wood = Screenshots.read(path, image -> {
			int count = 0;
			int total = 0;
			for (int y = image.getHeight() / 2 - 8; y <= image.getHeight() / 2 + 8; y++) {
				for (int x = image.getWidth() / 2 - 8; x <= image.getWidth() / 2 + 8; x++) {
					int pixel = image.getPixel(x, y);
					// 石は灰色、空は青か橙で白っぽく、光の届かないチェストは黒に近い
					if (ARGB.red(pixel) > 55 && ARGB.red(pixel) > ARGB.blue(pixel) * 5 / 2) {
						count++;
					}
					total++;
				}
			}
			return (double) count / total;
		});
		if ((wood > 0.5) != shown) {
			throw new AssertionError(name + ": chest should be " + (shown ? "shown" : "hidden") + " but " + Math.round(wood * 100) + "% of the center was wood");
		}
	}

	// 透過中だけ、チェストを描画距離いっぱいまで最大の明るさで描く
	private static void assertChestLight(ClientGameTestContext context, BlockPos pos, boolean enabled) {
		Integer light = context.computeOnClient(client -> {
			BlockEntity blockEntity = client.level.getBlockEntity(pos);
			if (blockEntity == null) {
				throw new AssertionError("no chest at " + pos + " on the client");
			}
			BlockEntityRenderState state = client.getBlockEntityRenderDispatcher().tryExtractRenderState(blockEntity, 0, null, false);
			return state == null ? null : state.lightCoords;
		});
		boolean far = !pos.closerThan(context.computeOnClient(client -> client.player.blockPosition()), 64);
		if (enabled) {
			Asserts.assertEquals("light of the chest at " + pos, LightCoordsUtil.FULL_BRIGHT, light);
		} else if (far) {
			Asserts.assertEquals("render state of the far chest at " + pos, null, light);
		} else if (light == null || light == LightCoordsUtil.FULL_BRIGHT) {
			throw new AssertionError("buried chest at " + pos + " should be dark but was " + light);
		}
	}

	private static void openWheel(ClientGameTestContext context) {
		context.getInput().holdKey(XrayClient.toggle);
		context.waitForScreen(ModeWheelScreen.class);
		context.waitTick();
	}

	private static void tap(ClientGameTestContext context) {
		context.getInput().pressKey(XrayClient.toggle);
		context.waitTicks(2);
	}

	// ホイールの中心からのずれ（GUI 座標）にカーソルを置く
	private static void moveCursor(ClientGameTestContext context, int dx, int dy) {
		double[] pos = context.computeOnClient(client -> {
			int scale = client.getWindow().getGuiScale();
			return new double[] {(client.gui.screen().width / 2 + dx) * scale, (client.gui.screen().height / 2 + dy) * scale};
		});
		context.getInput().setCursorPos(pos[0], pos[1]);
		context.waitTicks(2);
	}

	private static void waitForRender(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		context.waitTick();
		singleplayer.getConnection().waitForChunksRender();
		context.waitTicks(5);
	}

	private static void assertState(ClientGameTestContext context, boolean enabled, XrayMode mode) {
		if (XrayClient.isEnabled() != enabled || XrayClient.mode() != mode) {
			throw new AssertionError("expected " + enabled + "/" + mode + " but was " + XrayClient.isEnabled() + "/" + XrayClient.mode());
		}
		if (context.computeOnClient(client -> client.gui.screen()) != null) {
			throw new AssertionError("wheel should be closed");
		}
	}

	private static void place(ServerLevel level, BlockPos pos, Block block) {
		level.setBlockAndUpdate(pos, block.defaultBlockState());
	}
}
