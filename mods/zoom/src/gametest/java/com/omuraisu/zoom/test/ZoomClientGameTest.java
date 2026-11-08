package com.omuraisu.zoom.test;

import com.omuraisu.zoom.client.ZoomClient;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

public class ZoomClientGameTest implements FabricClientGameTest {
	// 倍率が目標に追いつくまで待つ
	private static final int SETTLE_TICKS = 20;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();

			// 遠くに的を立てる
			BlockPos target = singleplayer.getServer().computeOnServer(server -> {
				ServerLevel level = server.overworld();
				BlockPos center = server.getPlayerList().getPlayers().getFirst().blockPosition().offset(0, 2, -40);
				for (BlockPos pos : BlockPos.betweenClosed(center.offset(-2, -2, 0), center.offset(2, 2, 0))) {
					level.setBlockAndUpdate(pos, Blocks.QUARTZ_BLOCK.defaultBlockState());
				}
				for (BlockPos pos : BlockPos.betweenClosed(center.offset(-1, -1, 0), center.offset(1, 1, 0))) {
					level.setBlockAndUpdate(pos, Blocks.REDSTONE_BLOCK.defaultBlockState());
				}
				level.setBlockAndUpdate(center, Blocks.GOLD_BLOCK.defaultBlockState());
				return center;
			});
			singleplayer.getConnection().waitForChunksRender();
			// 入室直後の最初のカーソル移動は捨てられるので先に済ませておく
			turn(context);
			context.getInput().lookAt(target);
			context.waitTicks(5);

			float base = fov(context);
			context.takeScreenshot("zoom-off");

			context.getInput().holdKey(ZoomClient.key);
			context.waitTicks(SETTLE_TICKS);
			assertFov(context, ZoomClient.apply(base, ZoomClient.DEFAULT_LEVEL));
			context.takeScreenshot("zoom-default");

			// ホイールで倍率が上がり、ホットバーは動かない
			int slot = selectedSlot(context);
			context.getInput().scroll(3);
			context.waitTicks(SETTLE_TICKS);
			float level = ZoomClient.level();
			if (level <= ZoomClient.DEFAULT_LEVEL) {
				throw new AssertionError("scroll should zoom in but level was " + level);
			}
			if (selectedSlot(context) != slot) {
				throw new AssertionError("hotbar slot changed while zooming");
			}
			assertFov(context, ZoomClient.apply(base, level));
			context.takeScreenshot("zoom-scrolled");

			// 視点の回転は倍率で割られる
			float zoomedTurn = turn(context);

			context.getInput().releaseKey(ZoomClient.key);
			context.waitTicks(SETTLE_TICKS);
			assertFov(context, base);
			if (ZoomClient.level() != ZoomClient.DEFAULT_LEVEL) {
				throw new AssertionError("level should reset on release but was " + ZoomClient.level());
			}

			float normalTurn = turn(context);
			if (Math.abs(zoomedTurn * level / normalTurn - 1) > 0.05f) {
				throw new AssertionError("turn while zoomed " + zoomedTurn + " should be " + normalTurn + " / " + level);
			}

			// 離した後のホイールはホットバーに戻る
			context.getInput().scroll(-1);
			context.waitTick();
			if (selectedSlot(context) == slot) {
				throw new AssertionError("hotbar slot should change after release");
			}

			// Jori で無効にされている間は、押してもズームせず、ホイールはホットバーに回る
			context.runOnClient(client -> ZoomClient.setActive(false));
			try {
				context.getInput().holdKey(ZoomClient.key);
				context.waitTicks(SETTLE_TICKS);
				assertFov(context, base);
				int before = selectedSlot(context);
				context.getInput().scroll(1);
				context.waitTick();
				if (selectedSlot(context) == before) {
					throw new AssertionError("scroll should reach the hotbar while inactive");
				}
				context.getInput().releaseKey(ZoomClient.key);
			} finally {
				context.runOnClient(client -> ZoomClient.setActive(true));
			}
			context.getInput().holdKey(ZoomClient.key);
			context.waitTicks(SETTLE_TICKS);
			assertFov(context, ZoomClient.apply(base, ZoomClient.DEFAULT_LEVEL));
			context.getInput().releaseKey(ZoomClient.key);
			context.waitTicks(SETTLE_TICKS);
		}
	}

	private static float fov(ClientGameTestContext context) {
		return context.computeOnClient(client -> client.gameRenderer.mainCamera().getFov());
	}

	private static void assertFov(ClientGameTestContext context, float expected) {
		float actual = fov(context);
		if (Math.abs(actual - expected) > 0.01f) {
			throw new AssertionError("expected fov " + expected + " but was " + actual);
		}
	}

	private static int selectedSlot(ClientGameTestContext context) {
		return context.computeOnClient(client -> client.player.getInventory().getSelectedSlot());
	}

	// カーソルを横に動かしたときの視点の回転量
	private static float turn(ClientGameTestContext context) {
		float before = context.computeOnClient(client -> client.player.getYRot());
		context.getInput().moveCursor(200, 0);
		context.waitTicks(2);
		float after = context.computeOnClient(client -> client.player.getYRot());
		return after - before;
	}
}
