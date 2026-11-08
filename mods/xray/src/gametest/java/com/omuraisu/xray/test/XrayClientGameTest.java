package com.omuraisu.xray.test;

import com.mojang.blaze3d.platform.InputConstants;
import com.omuraisu.xray.client.OreMode;
import com.omuraisu.xray.client.OreWheelScreen;
import com.omuraisu.xray.client.XrayClient;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public class XrayClientGameTest implements FabricClientGameTest {
	// OreWheelScreen と同じ外周の半径
	private static final int RADIUS = 54;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();

			BlockPos center = singleplayer.getServer().computeOnServer(server -> {
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
				return origin.offset(4, 3, 6);
			});
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
			assertState(context, true, OreMode.ALL);
			waitForRender(context, singleplayer);
			context.takeScreenshot("mode-all");

			tap(context);
			assertState(context, false, OreMode.ALL);

			// 真上のダイヤを選んで離す
			openWheel(context);
			moveCursor(context, 0, -RADIUS);
			context.takeScreenshot("wheel-diamond");
			context.getInput().releaseKey(XrayClient.toggle);
			context.waitTick();
			assertState(context, true, OreMode.DIAMOND);
			waitForRender(context, singleplayer);
			context.takeScreenshot("mode-diamond");

			// 単押しは選んだモードのままオン/オフ
			tap(context);
			assertState(context, false, OreMode.DIAMOND);
			tap(context);
			assertState(context, true, OreMode.DIAMOND);

			// クリックでも確定する。その後キーを離しても切り替わらない
			int goldIndex = OreMode.RING.indexOf(OreMode.GOLD);
			double angle = -Math.PI / 2 + goldIndex * 2 * Math.PI / OreMode.RING.size();
			openWheel(context);
			moveCursor(context, (int) Math.round(Math.cos(angle) * RADIUS), (int) Math.round(Math.sin(angle) * RADIUS));
			context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTick();
			assertState(context, true, OreMode.GOLD);
			context.getInput().releaseKey(XrayClient.toggle);
			context.waitTicks(2);
			assertState(context, true, OreMode.GOLD);
			waitForRender(context, singleplayer);
			context.takeScreenshot("mode-gold");

			tap(context);
			assertState(context, false, OreMode.GOLD);
			waitForRender(context, singleplayer);
			context.takeScreenshot("xray-off-again");

			// Jori で無効にされている間は透過せず、キーも効かない。戻すと透過し直す
			tap(context);
			assertState(context, true, OreMode.GOLD);
			context.runOnClient(client -> XrayClient.setActive(false));
			try {
				assertState(context, false, OreMode.GOLD);
				waitForRender(context, singleplayer);
				context.takeScreenshot("xray-inactive");
				tap(context);
				assertState(context, false, OreMode.GOLD);
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
			assertState(context, true, OreMode.GOLD);
			waitForRender(context, singleplayer);
			context.takeScreenshot("xray-active-again");
			tap(context);
			assertState(context, false, OreMode.GOLD);
		}
	}

	private static void openWheel(ClientGameTestContext context) {
		context.getInput().holdKey(XrayClient.toggle);
		context.waitForScreen(OreWheelScreen.class);
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

	private static void assertState(ClientGameTestContext context, boolean enabled, OreMode mode) {
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
