package com.omuraisu.timber;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

public class Timber implements ModInitializer {
	private static final int MAX_LOGS = 256;

	private static final Set<UUID> DISABLED = new HashSet<>();

	// Jori の一覧で無効にされている間は false。誰が壊しても 1 つずつ
	private static volatile boolean active = true;

	private static boolean felling;

	public static boolean isActive() {
		return active;
	}

	public static void setActive(boolean value) {
		active = value;
	}

	public static void setEnabled(Player player, boolean enabled) {
		if (enabled) {
			DISABLED.remove(player.getUUID());
		} else {
			DISABLED.add(player.getUUID());
		}
	}

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.serverboundPlay().register(TogglePayload.TYPE, TogglePayload.CODEC);
		// 専用サーバーで無効にしているときは受け口を作らず、クライアントに使えないことを伝える。
		// シングルプレイは途中で切り替えられるので、受け口は作っておく
		ServerPlayConnectionEvents.INIT.register((handler, server) -> {
			if (active || !server.isDedicatedServer()) {
				ServerPlayNetworking.registerReceiver(handler, TogglePayload.TYPE, (payload, context) -> setEnabled(context.player(), payload.enabled()));
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> DISABLED.remove(handler.player.getUUID()));

		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (!active || felling || !(player instanceof ServerPlayer serverPlayer)) {
				return;
			}
			if (DISABLED.contains(player.getUUID()) || !state.is(BlockTags.LOGS) || !player.getMainHandItem().is(ItemTags.AXES)) {
				return;
			}

			List<BlockPos> logs = findTree(level, pos, state.getBlock());
			if (logs.isEmpty()) {
				return;
			}

			felling = true;
			try {
				for (BlockPos log : logs) {
					ItemStack axe = serverPlayer.getMainHandItem();
					// 最初の原木の耐久消費はこのイベントの後に行われるため、その分と最後の1を残す
					if (!axe.is(ItemTags.AXES) || axe.isDamageableItem() && axe.getMaxDamage() - axe.getDamageValue() <= 2) {
						break;
					}
					serverPlayer.gameMode.destroyBlock(log);
				}
			} finally {
				felling = false;
			}
		});
	}

	// 壊した原木とつながる同種の原木を近い順に返す。葉が付いていない（建築物の）場合は空。
	private static List<BlockPos> findTree(Level level, BlockPos origin, Block log) {
		List<BlockPos> found = new ArrayList<>();
		Set<BlockPos> visited = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		boolean natural = false;

		visited.add(origin);
		queue.add(origin);

		while (!queue.isEmpty() && found.size() < MAX_LOGS) {
			BlockPos current = queue.poll();
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						BlockPos next = current.offset(dx, dy, dz);
						if (!visited.add(next)) {
							continue;
						}
						BlockState nextState = level.getBlockState(next);
						if (nextState.is(log)) {
							found.add(next);
							queue.add(next);
						} else if (!natural && isNaturalCanopy(nextState)) {
							natural = true;
						}
					}
				}
			}
		}

		return natural ? found : List.of();
	}

	private static boolean isNaturalCanopy(BlockState state) {
		if (state.is(BlockTags.LEAVES)) {
			return !state.hasProperty(LeavesBlock.PERSISTENT) || !state.getValue(LeavesBlock.PERSISTENT);
		}
		return state.is(BlockTags.WART_BLOCKS);
	}
}
