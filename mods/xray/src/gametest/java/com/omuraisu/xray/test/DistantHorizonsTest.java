package com.omuraisu.xray.test;

import java.nio.file.Path;
import java.util.Set;
import java.util.function.IntPredicate;

import com.mojang.blaze3d.platform.NativeImage;
import com.omuraisu.testkit.Lods;
import com.omuraisu.testkit.Scenario;
import com.omuraisu.testkit.Screenshots;
import com.omuraisu.xray.client.XrayClient;
import com.omuraisu.xray.client.XrayMode;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

// Distant Horizons との併用: オフでは LOD が描かれ、透視中は LOD が消えて鉱石だけが残る
public class DistantHorizonsTest implements Scenario {
	// 描画距離（5 チャンク = 80 ブロック）の端近く。ふつうの描画では見えているが、チェストの 64 ブロックより遠い
	private static final int WALL_Z = 72;
	private static final int WALL_Y = -18;
	// 地面（スーパーフラットの上端）から 60 ブロックほど上
	private static final double PLAYER_Y = 0;

	@Override
	public String name() {
		return "distant-horizons";
	}

	@Override
	public void run(ClientGameTestContext context) {
		if (!Lods.isLoaded()) {
			return;
		}
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();
			buildScene(singleplayer);
			context.waitTicks(10);
			singleplayer.getConnection().waitForChunksRender();
			context.getInput().lookAt(new BlockPos(0, WALL_Y, WALL_Z));
			waitForRender(context, singleplayer);
			Lods.disableClouds(context);

			// オフでは、地平線のすぐ下に LOD の地面が広がり、72 ブロック先の石の壁が見える
			Lods.waitForRendering(context);
			// 近くの LOD が生成されて色が付くまで待つ
			context.waitTicks(80);
			double lodOff = greenRatio(context, "dh-xray-off", DistantHorizonsTest::groundBox);
			if (lodOff < 0.3) {
				throw new AssertionError("LOD ground should be green when xray is off but only " + Math.round(lodOff * 100) + "% was");
			}

			// 透視をオンにすると LOD が 1 つも描かれず、地面の色が空に変わる
			setMode(context, XrayMode.ALL);
			setEnabled(context, true);
			waitForRender(context, singleplayer);
			Lods.assertNotRendering(context, 20);
			double lodOn = greenRatio(context, "dh-xray-on", DistantHorizonsTest::groundBox);
			if (lodOn > 0.1) {
				throw new AssertionError("LOD ground should disappear while xray is on but " + Math.round(lodOn * 100) + "% was green");
			}

			// オフに戻すと LOD と石の壁が戻り、壁の裏のエメラルドは暗いまま見えない
			setEnabled(context, false);
			waitForRender(context, singleplayer);
			context.waitTicks(40);
			singleplayer.getConnection().waitForChunksRender();
			Lods.waitForRendering(context);
			double wallOff = brightness(context, "dh-xray-wall-off", image -> centerBox(image, 18));
			if (wallOff > 100) {
				throw new AssertionError("ores behind the wall should be dark when xray is off but brightness was " + Math.round(wallOff));
			}

			// オンにすると、壁の裏に隠したエメラルドが最大の明るさで見える
			setEnabled(context, true);
			waitForRender(context, singleplayer);
			context.waitTicks(40);
			singleplayer.getConnection().waitForChunksRender();
			Lods.assertNotRendering(context, 10);
			double wallOn = brightness(context, "dh-xray-wall-on", image -> centerBox(image, 18));
			if (wallOn < 100 || wallOn < wallOff + 40) {
				throw new AssertionError("ores behind the wall should be bright when xray is on but was " + Math.round(wallOn)
						+ " against " + Math.round(wallOff));
			}

			// オンのまま Jori で無効にすると LOD が戻り、戻すとまた消える
			context.runOnClient(client -> XrayClient.setActive(false));
			try {
				Lods.waitForRendering(context);
				context.runOnClient(client -> XrayClient.setActive(true));
				context.waitTicks(5);
				Lods.assertNotRendering(context, 20);
			} finally {
				context.runOnClient(client -> XrayClient.setActive(true));
			}

			// 終わったらオフに戻す
			setEnabled(context, false);
			waitForRender(context, singleplayer);
			Lods.waitForRendering(context);
		} finally {
			Lods.restoreClouds(context);
		}
	}

	private static void buildScene(TestSingleplayerContext singleplayer) {
		singleplayer.getServer().runOnServer(server -> {
			ServerLevel level = server.overworld();
			for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(-12, WALL_Y - 12, WALL_Z), new BlockPos(12, WALL_Y + 12, WALL_Z))) {
				level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
			}
			// 壁の裏。透視のときだけ、石が消えて見える
			for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(-12, WALL_Y - 12, WALL_Z + 1), new BlockPos(12, WALL_Y + 12, WALL_Z + 1))) {
				level.setBlockAndUpdate(pos, Blocks.EMERALD_ORE.defaultBlockState());
			}
			// 飛べないときも落ちないよう、足元に足場を置く
			for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(-2, (int) PLAYER_Y - 1, -2), new BlockPos(2, (int) PLAYER_Y - 1, 2))) {
				level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
			}
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			player.setGameMode(GameType.CREATIVE);
			player.getAbilities().flying = true;
			player.onUpdateAbilities();
			player.teleportTo(level, 0.5, PLAYER_Y, 0.5, Set.of(), 0, 0, true);
		});
	}

	private static void waitForRender(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		context.waitTicks(10);
		singleplayer.getConnection().waitForChunksRender();
		context.waitTicks(5);
	}

	private static void setMode(ClientGameTestContext context, XrayMode mode) {
		context.runOnClient(client -> {
			XrayClient.setActive(true);
			XrayClient.select(client, mode);
		});
		context.waitTicks(3);
	}

	private static void tap(ClientGameTestContext context) {
		context.getInput().pressKey(XrayClient.toggle);
		context.waitTicks(3);
	}

	// キーの取りこぼしがあっても、確かめる状態にしてから進む
	private static void setEnabled(ClientGameTestContext context, boolean enabled) {
		for (int i = 0; i < 8 && XrayClient.isEnabled() != enabled; i++) {
			tap(context);
		}
		assertState(context, enabled);
		context.waitTicks(3);
	}

	private static void assertState(ClientGameTestContext context, boolean enabled) {
		if (XrayClient.isEnabled() != enabled) {
			throw new AssertionError("xray should be " + (enabled ? "on" : "off") + " but was " + XrayClient.isEnabled());
		}
	}

	private static int[] centerBox(NativeImage image, int half) {
		return new int[] {image.getWidth() / 2 - half, image.getWidth() / 2 + half,
				image.getHeight() / 2 - half, image.getHeight() / 2 + half};
	}

	// 地平線のすぐ下の LOD の地面。画面の左寄りで、中央の壁にも足場にも重ならない
	private static int[] groundBox(NativeImage image) {
		return new int[] {image.getWidth() / 5, image.getWidth() * 2 / 5,
				image.getHeight() / 2 + 30, image.getHeight() / 2 + 90};
	}

	// HUD を隠して撮り、指定した範囲で条件に合う画素の割合を返す
	private static double greenRatio(ClientGameTestContext context, String name, Box box) {
		return Screenshots.read(takeHiddenHud(context, name), image -> ratio(image, box.box(image), green()));
	}

	// 指定した範囲の平均の明るさ（0〜255）
	private static double brightness(ClientGameTestContext context, String name, Box box) {
		return Screenshots.read(takeHiddenHud(context, name), image -> {
			int[] area = box.box(image);
			long sum = 0;
			int count = 0;
			for (int y = area[2]; y < area[3]; y++) {
				for (int x = area[0]; x < area[1]; x++) {
					int pixel = image.getPixel(x, y);
					sum += ARGB.red(pixel) + ARGB.green(pixel) + ARGB.blue(pixel);
					count += 3;
				}
			}
			return (double) sum / count;
		});
	}

	private static Path takeHiddenHud(ClientGameTestContext context, String name) {
		context.runOnClient(client -> client.gui.hud.toggle());
		try {
			return context.takeScreenshot(name);
		} finally {
			context.runOnClient(client -> client.gui.hud.toggle());
		}
	}

	private static IntPredicate green() {
		// エメラルドと草の緑。空の青（緑より青が強い）や石の灰色とは分かれる
		return pixel -> ARGB.green(pixel) > ARGB.blue(pixel) + 12
				&& ARGB.green(pixel) > ARGB.red(pixel) + 4
				&& ARGB.green(pixel) > 40;
	}

	private static double ratio(NativeImage image, int[] area, IntPredicate color) {
		int count = 0;
		int total = 0;
		for (int y = area[2]; y < area[3]; y++) {
			for (int x = area[0]; x < area[1]; x++) {
				if (color.test(image.getPixel(x, y))) {
					count++;
				}
				total++;
			}
		}
		return (double) count / total;
	}

	private interface Box {
		int[] box(NativeImage image);
	}
}
