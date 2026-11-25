package com.omuraisu.zoom.test;

import java.nio.file.Path;
import java.util.function.IntPredicate;

import com.omuraisu.testkit.Lods;
import com.omuraisu.testkit.Scenario;
import com.omuraisu.testkit.Screenshots;
import com.omuraisu.zoom.client.ZoomClient;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

// Distant Horizons との併用: LOD の地面を画面の中央に写し、ズームしても中央が地面のまま（空が見えない）ことを確かめる
public class DistantHorizonsTest implements Scenario {
	private static final int SETTLE_TICKS = 20;
	private static final int GROUND_DISTANCE = 100;

	@Override
	public String name() {
		return "distant-horizons";
	}

	@Override
	public void run(ClientGameTestContext context) {
		if (!Lods.isLoaded()) {
			return;
		}
		Lods.disableClouds(context);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();
			BlockPos target = buildScene(singleplayer);
			context.waitTicks(10);
			singleplayer.getConnection().waitForChunksRender();
			// 入ってすぐの最初のカーソル移動は捨てられるので先に済ませておく
			context.getInput().moveCursor(1, 0);
			context.waitTick();
			context.getInput().lookAt(target);
			context.waitTicks(20);
			singleplayer.getConnection().waitForChunksRender();
			Lods.waitForRendering(context);
			// 近くの LOD が生成されて色が付くまで待つ
			context.waitTicks(80);

			double ground = groundRatio(context, "dh-zoom-off");
			if (ground < 0.5) {
				throw new AssertionError("LOD ground should fill the center but only " + Math.round(ground * 100) + "% was");
			}

			// ズームしても中央は同じ地点なので、LOD の地面のまま。LOD も描かれ続ける
			float base = fov(context);
			context.getInput().holdKey(ZoomClient.key);
			context.waitTicks(SETTLE_TICKS);
			assertFov(context, ZoomClient.apply(base, ZoomClient.DEFAULT_LEVEL));
			Lods.waitForRendering(context);
			double zoomed = groundRatio(context, "dh-zoom-in");
			if (zoomed < 0.5) {
				throw new AssertionError("LOD ground should stay at the center while zoomed but only " + Math.round(zoomed * 100) + "% was");
			}
			context.getInput().releaseKey(ZoomClient.key);
			context.waitTicks(SETTLE_TICKS);
			assertFov(context, base);
		} finally {
			Lods.restoreClouds(context);
		}
	}

	private static BlockPos buildScene(TestSingleplayerContext singleplayer) {
		return singleplayer.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			BlockPos feet = player.blockPosition();
			player.setGameMode(GameType.CREATIVE);
			player.getAbilities().flying = true;
			player.onUpdateAbilities();
			player.teleportTo(feet.getX() + 0.5, feet.getY() + 60, feet.getZ() + 0.5);
			// 飛べないときも落ちないよう、足元に足場を置く
			for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-2, 59, -2), feet.offset(2, 59, 2))) {
				level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
			}
			return new BlockPos(feet.getX(), feet.getY() - 1, feet.getZ() + GROUND_DISTANCE);
		});
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

	// 画面の中央の、草の地面の色の割合。空の青（緑より青が強い）とは分かれる
	private static double groundRatio(ClientGameTestContext context, String name) {
		context.runOnClient(client -> client.gui.hud.toggle());
		Path path;
		try {
			path = context.takeScreenshot(name);
		} finally {
			context.runOnClient(client -> client.gui.hud.toggle());
		}
		return Screenshots.read(path, image -> {
			IntPredicate grass = pixel -> ARGB.green(pixel) > ARGB.blue(pixel) + 10
					&& ARGB.green(pixel) > ARGB.red(pixel) + 4
					&& ARGB.green(pixel) > 40;
			int count = 0;
			int total = 0;
			int half = 50;
			for (int y = image.getHeight() / 2 - 60; y < image.getHeight() / 2 - 10; y++) {
				for (int x = image.getWidth() / 2 - half; x < image.getWidth() / 2 + half; x++) {
					if (grass.test(image.getPixel(x, y))) {
						count++;
					}
					total++;
				}
			}
			return (double) count / total;
		});
	}
}
