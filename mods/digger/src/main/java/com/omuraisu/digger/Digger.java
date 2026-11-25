package com.omuraisu.digger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class Digger implements ModInitializer {
	// サーバーで覚えているプレイヤーごとの設定。送ってこない人は入れない（両方オフとして扱う）
	private static final Map<UUID, DigSettings> SETTINGS = new HashMap<>();

	// Jori の一覧で無効にされている間は false。誰が壊しても 1 つずつ
	private static volatile boolean active = true;

	// まとめて壊している間に起きた破壊のイベントで、もう一度始めない
	private static boolean breaking;

	// まとめて壊している間（breaking が true の間）に増えたドロップと経験値を集める。サーバースレッドだけで触る
	private static Collecting collecting;

	public static boolean isActive() {
		return active;
	}

	public static void setActive(boolean value) {
		active = value;
	}

	public static DigSettings getSettings(Player player) {
		return SETTINGS.getOrDefault(player.getUUID(), DigSettings.OFF);
	}

	public static void setSettings(Player player, DigSettings settings) {
		SETTINGS.put(player.getUUID(), settings);
	}

	public static void forget(Player player) {
		SETTINGS.remove(player.getUUID());
	}

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.serverboundPlay().register(SettingsPayload.TYPE, SettingsPayload.CODEC);
		// 専用サーバーで無効にしているときは受け口を作らず、クライアントに使えないことを伝える。
		// シングルプレイは途中で切り替えられるので、受け口は作っておく
		ServerPlayConnectionEvents.INIT.register((handler, server) -> {
			if (active || !server.isDedicatedServer()) {
				ServerPlayNetworking.registerReceiver(handler, SettingsPayload.TYPE,
						(payload, context) -> setSettings(context.player(), payload.settings()));
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> forget(handler.player));

		// まとめて壊している間に増えたアイテムと経験値だけを集める。同じ tick でも、ほかのプレイヤーが捨てた物や
		// 別のディメンションの物は、この間（breaking が true）ではないので拾わない
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			Collecting current = collecting;
			if (!breaking || current == null || level != current.level) {
				return;
			}
			if (entity instanceof ItemEntity item) {
				current.items.add(item);
			} else if (entity instanceof ExperienceOrb orb) {
				current.orbs.add(orb);
			}
		});

		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (!active || breaking || !(player instanceof ServerPlayer serverPlayer)) {
				return;
			}
			DigSettings settings = getSettings(serverPlayer);
			if (!settings.area() && !settings.vein()) {
				return;
			}

			Direction face = ((BreakDirectionAccess) serverPlayer.gameMode).digger$getBreakDirection();
			if (face == null) {
				// バニラの destroyDirection が更新されない一瞬の破壊など、面を覚えていないとき
				face = Direction.getApproximateNearest(serverPlayer.getLookAngle()).getOpposite();
			}

			List<DigPlan.Break> plan = DigPlan.compute(serverPlayer, level, pos, state, face, settings);
			if (plan.isEmpty()) {
				return;
			}

			startCollecting(pos, level);
			breaking = true;
			try {
				for (DigPlan.Break entry : plan) {
					BlockPos next = entry.pos();
					BlockState nextState = level.getBlockState(next);
					if (nextState.isAir()) {
						continue;
					}
					ItemStack tool = serverPlayer.getInventory().getItem(entry.slot());
					if (!DigPlan.canMine(tool, nextState) || !DigPlan.hasDurability(serverPlayer, tool)) {
						continue;
					}
					int previous = serverPlayer.getInventory().getSelectedSlot();
					try {
						if (entry.slot() != previous) {
							serverPlayer.getInventory().setSelectedSlot(entry.slot());
						}
						serverPlayer.gameMode.destroyBlock(next);
					} finally {
						// 選んだスロットはクライアントに伝えず、必ず元へ戻す
						serverPlayer.getInventory().setSelectedSlot(previous);
					}
				}
			} finally {
				breaking = false;
				flushCollecting();
			}
		});
	}

	private static void startCollecting(BlockPos target, Level level) {
		if (level instanceof ServerLevel serverLevel) {
			collecting = new Collecting(target, serverLevel);
		}
	}

	private static void flushCollecting() {
		Collecting current = collecting;
		if (current == null) {
			return;
		}
		collecting = null;

		// 経験値は狙ったブロックの位置へ。アイテムは同じものを 1 つのスタックにまとめる
		Vec3 center = Vec3.atCenterOf(current.target);
		for (ExperienceOrb orb : current.orbs) {
			if (!orb.isRemoved()) {
				orb.setPos(center.x, center.y, center.z);
				orb.setDeltaMovement(Vec3.ZERO);
			}
		}

		List<ItemEntity> kept = new ArrayList<>();
		for (ItemEntity item : current.items) {
			if (item.isRemoved() || item.getItem().isEmpty()) {
				continue;
			}
			item.setPos(center.x, center.y, center.z);
			item.setDeltaMovement(Vec3.ZERO);
			boolean merged = false;
			for (ItemEntity other : kept) {
				if (other.isRemoved()) {
					continue;
				}
				ItemStack to = other.getItem();
				ItemStack from = item.getItem();
				if (ItemEntity.areMergable(to, from)) {
					other.setItem(ItemEntity.merge(to, from, 64));
					if (from.isEmpty()) {
						item.discard();
						merged = true;
						break;
					}
				}
			}
			if (!merged) {
				kept.add(item);
			}
		}
	}

	private static final class Collecting {
		private final BlockPos target;
		private final ServerLevel level;
		private final List<ItemEntity> items = new ArrayList<>();
		private final List<ExperienceOrb> orbs = new ArrayList<>();

		private Collecting(BlockPos target, ServerLevel level) {
			this.target = target;
			this.level = level;
		}
	}
}
