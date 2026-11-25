package com.omuraisu.digger.test;

import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

// クライアントのゲームテストで使う、正面の石の壁と鉱石の塊。プレイヤーは壁の 4 ブロック手前に立つ。
// 壁の中央の石が範囲採掘の狙い、左の石炭鉱石が鉱石一括の狙い
final class TestScene {
	static final int WALL_DISTANCE = 4;

	final int x;
	final int feetY;
	final int wallZ;
	final BlockPos areaTarget;
	final BlockPos coalTarget;

	private TestScene(int x, int feetY, int wallZ) {
		this.x = x;
		this.feetY = feetY;
		this.wallZ = wallZ;
		this.areaTarget = new BlockPos(x, feetY + 1, wallZ);
		// 真横の 45 度だとブロックの角を通って隣を狙うことがあるので、少しだけずらす
		this.coalTarget = new BlockPos(x - 3, feetY + 1, wallZ);
	}

	static TestScene build(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		singleplayer.getConnection().waitForChunksRender();
		TestScene scene = singleplayer.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			player.setGameMode(GameType.SURVIVAL);
			BlockPos feet = player.blockPosition();
			int x = feet.getX();
			int y = feet.getY();
			int z = feet.getZ();
			int wallZ = z - WALL_DISTANCE;

			fill(level, new BlockPos(x - 8, y - 1, z - 8), new BlockPos(x + 8, y + 8, z + 8), Blocks.AIR);
			fill(level, new BlockPos(x - 8, y - 1, z - 8), new BlockPos(x + 8, y - 1, z + 8), Blocks.STONE);
			fill(level, new BlockPos(x - 6, y, wallZ), new BlockPos(x + 6, y + 6, wallZ), Blocks.STONE);
			// 石炭鉱石を斜めにつなげて、鉱石一括の枠線が 2 つ以上になるようにする
			level.setBlockAndUpdate(new BlockPos(x - 3, y + 1, wallZ), Blocks.COAL_ORE.defaultBlockState());
			level.setBlockAndUpdate(new BlockPos(x - 3, y + 2, wallZ), Blocks.COAL_ORE.defaultBlockState());
			level.setBlockAndUpdate(new BlockPos(x - 2, y + 2, wallZ), Blocks.COAL_ORE.defaultBlockState());

			player.teleportTo(level, x + 0.5, y, z + 0.5, Set.of(), 180, 0, true);
			player.getInventory().setSelectedSlot(0);
			player.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
			return new TestScene(x, y, wallZ);
		});
		context.waitTicks(20);
		singleplayer.getConnection().waitForChunksRender();
		// HUD を隠していると枠線も出ないので、隠れていたら戻す（中央の照準は数えるときに避ける）
		context.runOnClient(client -> {
			if (client.gui.hud.isHidden()) {
				client.gui.hud.toggle();
			}
		});
		context.waitTicks(5);
		return scene;
	}

	private static void fill(ServerLevel level, BlockPos from, BlockPos to, Block block) {
		for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
			level.setBlockAndUpdate(pos, block.defaultBlockState());
		}
	}
}
