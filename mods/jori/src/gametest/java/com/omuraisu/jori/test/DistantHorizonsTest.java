package com.omuraisu.jori.test;

import com.omuraisu.jori.Feature;
import com.omuraisu.jori.client.JoriClient;
import com.omuraisu.testkit.Asserts;
import com.omuraisu.testkit.Lods;
import com.omuraisu.testkit.Scenario;
import com.omuraisu.testkit.Widgets;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

// Distant Horizons との併用: jori の全 mod を無効にしても、DH の LOD は描かれ続ける
public class DistantHorizonsTest implements Scenario {
	private static final int GROUND_DISTANCE = 200;

	@Override
	public String name() {
		return "distant-horizons";
	}

	@Override
	public void run(ClientGameTestContext context) {
		if (!Lods.isLoaded()) {
			return;
		}
		setAll(context, true);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();
			BlockPos target = buildScene(singleplayer);
			context.waitTicks(10);
			singleplayer.getConnection().waitForChunksRender();
			context.getInput().lookAt(target);
			context.waitTicks(20);
			singleplayer.getConnection().waitForChunksRender();
			Lods.disableClouds(context);
			Lods.waitForRendering(context);
			// LOD の生成が落ち着くまで待つ
			context.waitTicks(40);

			// 一覧で全 mod を無効にしても LOD は描かれる
			toggleAll(context);
			assertAll(false);
			Lods.waitForRendering(context);

			// 戻しても LOD は描かれる
			toggleAll(context);
			assertAll(true);
			Lods.waitForRendering(context);
		} finally {
			setAll(context, true);
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

	// ポーズ画面の Jori ボタンから一覧を開き、すべてのタイルを押して切り替える
	private static void toggleAll(ClientGameTestContext context) {
		context.setScreen(() -> new PauseScreen(true));
		context.waitTicks(3);
		Widgets.click(context, "Jori");
		for (Feature feature : JoriClient.FEATURES) {
			Widgets.click(context, name(feature));
		}
		context.setScreen(() -> null);
		context.waitTicks(5);
	}

	private static void assertAll(boolean active) {
		for (Feature feature : JoriClient.FEATURES) {
			Asserts.assertEquals(feature.id() + " active", active, feature.isActive());
		}
	}

	private static String name(Feature feature) {
		return FabricLoader.getInstance().getModContainer(feature.id()).orElseThrow().getMetadata().getName();
	}

	private static void setAll(ClientGameTestContext context, boolean active) {
		context.runOnClient(client -> {
			for (Feature feature : JoriClient.FEATURES) {
				JoriClient.setActive(feature, active);
			}
		});
	}
}
