package com.omuraisu.fullbright.test;

import java.nio.file.Path;

import com.omuraisu.fullbright.client.FullbrightClient;
import com.omuraisu.testkit.Lods;
import com.omuraisu.testkit.Scenario;
import com.omuraisu.testkit.Screenshots;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ARGB;
import net.minecraft.world.clock.ClockTimeMarkers;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

// Distant Horizons との併用: 夜の LOD もライトマップを通るので、オンにすると明るくなる
public class DistantHorizonsTest implements Scenario {
	// LOD の地面を画面の中央に写す。描画距離（5 チャンク = 80 ブロック）のすぐ外なので、霧が濃すぎない
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
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();
			BlockPos target = buildScene(singleplayer);
			context.waitTicks(10);
			singleplayer.getConnection().waitForChunksRender();
			context.getInput().lookAt(target);
			context.waitTicks(20);
			singleplayer.getConnection().waitForChunksRender();
			Lods.waitForRendering(context);
			Lods.disableClouds(context);
			// 近くの LOD が生成されて色が付くまで待つ
			context.waitTicks(80);

			// 夜にして、オフの LOD の明るさを測る
			context.runOnClient(client -> FullbrightClient.setActive(true));
			if (FullbrightClient.isEnabled()) {
				tap(context);
			}
			assertState(false);
			double off = brightness(context, "dh-fullbright-off");

			// 上限まで上げてオンにする
			adjust(context, 100);
			assertState(true);
			if (FullbrightClient.level() != FullbrightClient.MAX_LEVEL) {
				throw new AssertionError("fullbright should be at the max level but was " + FullbrightClient.level());
			}
			double on = brightness(context, "dh-fullbright-on");
			if (on < off * 2 || on - off < 20) {
				throw new AssertionError("LODs should be at least twice as bright but off was " + off + " and on was " + on);
			}

			tap(context);
			assertState(false);
		} finally {
			Lods.restoreClouds(context);
		}
	}

	// 地面から 60 ブロックほど上の足場から、遠くの地面を見る。夜にする
	private static BlockPos buildScene(TestSingleplayerContext singleplayer) {
		return singleplayer.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			Holder<WorldClock> clock = server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(WorldClocks.OVERWORLD);
			server.clockManager().moveToTimeMarker(clock, ClockTimeMarkers.MIDNIGHT);
			server.setWeatherParameters(12000, 0, false, false);
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

	private static void tap(ClientGameTestContext context) {
		context.getInput().pressKey(FullbrightClient.key);
		context.waitTicks(3);
	}

	private static void adjust(ClientGameTestContext context, int steps) {
		context.getInput().holdKey(FullbrightClient.key);
		context.waitTick();
		context.getInput().scroll(steps);
		context.waitTick();
		context.getInput().releaseKey(FullbrightClient.key);
		context.waitTicks(3);
	}

	private static void assertState(boolean enabled) {
		if (FullbrightClient.isEnabled() != enabled) {
			throw new AssertionError("fullbright should be " + (enabled ? "on" : "off") + " but was " + FullbrightClient.isEnabled());
		}
	}

	// 画面の中央の平均の明るさ（0〜255）。足場に重ならない、LOD の地面だけが入る範囲
	private static double brightness(ClientGameTestContext context, String name) {
		context.waitTicks(5);
		context.runOnClient(client -> client.gui.hud.toggle());
		Path path;
		try {
			path = context.takeScreenshot(name);
		} finally {
			context.runOnClient(client -> client.gui.hud.toggle());
		}
		return Screenshots.read(path, image -> {
			long sum = 0;
			int count = 0;
			for (int y = image.getHeight() / 2 - 70; y < image.getHeight() / 2 - 10; y++) {
				for (int x = image.getWidth() / 2 - 100; x < image.getWidth() / 2 + 100; x++) {
					int pixel = image.getPixel(x, y);
					sum += ARGB.red(pixel) + ARGB.green(pixel) + ARGB.blue(pixel);
					count += 3;
				}
			}
			return (double) sum / count;
		});
	}
}
