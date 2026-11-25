package com.omuraisu.minimap.test;

import java.util.Set;

import com.mojang.blaze3d.platform.NativeImage;
import com.omuraisu.minimap.Minimap;
import com.omuraisu.minimap.Pin;
import com.omuraisu.minimap.client.MinimapClient;
import com.omuraisu.testkit.Lods;
import com.omuraisu.testkit.Scenario;
import com.omuraisu.testkit.Screenshots;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

// Distant Horizons との併用: フェードが掛かる距離に壁とピンを置き、柱が LOD の色に上書きされないことを確かめる
public class DistantHorizonsTest implements Scenario {
	// 壁は 50 ブロック先、ピンはその手前の 45 ブロック。フェードが掛かる距離より遠い
	private static final int WALL_Z = 50;
	private static final int PIN_Z = 45;
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
			context.getInput().lookAt(new BlockPos(0, 1, PIN_Z));
			context.waitTicks(10);
			singleplayer.getConnection().waitForChunksRender();
			Lods.waitForRendering(context);
			// フェードを近く（約 27〜35 ブロック）に掛ける。ワールドに入ってからでないと DH が設定を読み直す
			Lods.setOverdrawPreventionRadius(context, 0.05f);
			Lods.disableClouds(context);
			// LOD の生成と色が落ち着くまで待つ
			context.waitTicks(60);

			Pin pin = new Pin(MapTests.OVERWORLD, 0, PIN_Z);
			assertBeam(context, "dh-beam", pin);

			// シェーダーパック使用中はフェードが掛からないので、今までの COLLECT_SUBMITS のまま柱を描く
			if (FabricLoader.getInstance().isModLoaded("iris")) {
				context.runOnClient(client -> MapTests.IrisPack.enable());
				context.waitTicks(20);
				singleplayer.getConnection().waitForChunksRender();
				try {
					assertBeam(context, "dh-beam-shader-pack", pin);
				} finally {
					context.runOnClient(client -> MapTests.IrisPack.disable());
					context.waitTicks(20);
					singleplayer.getConnection().waitForChunksRender();
				}
			}
		} finally {
			Lods.clearOverdrawPreventionRadius(context);
			Lods.restoreClouds(context);
		}
	}

	private static void buildScene(TestSingleplayerContext singleplayer) {
		singleplayer.getServer().runOnServer(server -> {
			ServerLevel level = server.overworld();
			// 視線の先を埋める高い壁。フェードはこの壁の奥行きで掛かる
			for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(-24, -60, WALL_Z), new BlockPos(24, 20, WALL_Z))) {
				level.setBlockAndUpdate(pos, Blocks.QUARTZ_BLOCK.defaultBlockState());
			}
			// 飛べないときも落ちないよう、足元に足場を置く
			for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(-2, (int) PLAYER_Y - 1, -2), new BlockPos(2, (int) PLAYER_Y - 1, 2))) {
				level.setBlockAndUpdate(pos, Blocks.QUARTZ_BLOCK.defaultBlockState());
			}
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			player.setGameMode(GameType.CREATIVE);
			player.getAbilities().flying = true;
			player.onUpdateAbilities();
			player.teleportTo(level, 0.5, PLAYER_Y, 0.5, Set.of(), 0, 0, true);
		});
	}

	// 画面の中央に柱が上から下まで立ち、ピンを消すと消える。
	// LOD は読み込みが続いて撮るたびに変わるので、2 枚を比べずに、1 枚の中で柱の列と横の列を比べる（壁も地面も横に一様）
	private static void assertBeam(ClientGameTestContext context, String name, Pin pin) {
		context.runOnClient(client -> {
			Minimap.setActive(true);
			// DH のチャットの警告が画素に写り込まないように消す
			client.gui.hud.getChat().clearMessages(false);
			MinimapClient.PINS.place(pin);
		});
		context.waitTicks(3);
		double with = Screenshots.read(context.takeScreenshot(name + "-with"), DistantHorizonsTest::beamRatio);
		context.runOnClient(client -> MinimapClient.PINS.remove(pin));
		context.waitTicks(3);
		double without = Screenshots.read(context.takeScreenshot(name + "-without"), DistantHorizonsTest::beamRatio);
		if (with < 0.9 || without > 0.1) {
			throw new AssertionError(name + ": beam should be drawn over the wall in 90% of the rows but was in " + Math.round(with * 100)
					+ "% with the pin and " + Math.round(without * 100) + "% without it");
		}
	}

	// 中央の列の色が、左右の列のどちらとも違う行の割合。照準のある行は除く
	private static double beamRatio(NativeImage image) {
		int centerX = image.getWidth() / 2;
		int centerY = image.getHeight() / 2;
		int beam = 0;
		int rows = 0;
		for (int y = centerY - 100; y < centerY + 100; y++) {
			if (Math.abs(y - centerY) < 15) {
				continue;
			}
			rows++;
			if (difference(image, centerX, centerX - 20, y) > 12 && difference(image, centerX, centerX + 20, y) > 12) {
				beam++;
			}
		}
		return (double) beam / rows;
	}

	// 2 つの列（横 3 画素の平均）の色の差。RGB の差の合計
	private static int difference(NativeImage image, int x1, int x2, int y) {
		int[] sums = new int[3];
		for (int dx = -1; dx <= 1; dx++) {
			int a = image.getPixel(x1 + dx, y);
			int b = image.getPixel(x2 + dx, y);
			sums[0] += ARGB.red(a) - ARGB.red(b);
			sums[1] += ARGB.green(a) - ARGB.green(b);
			sums[2] += ARGB.blue(a) - ARGB.blue(b);
		}
		return (Math.abs(sums[0]) + Math.abs(sums[1]) + Math.abs(sums[2])) / 3;
	}
}
