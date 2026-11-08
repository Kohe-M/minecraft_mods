package com.omuraisu.sorter;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class Sorter implements ModInitializer {
	// Jori の一覧で無効にされている間は false。ボタンを出さず、届いた依頼も無視する
	private static volatile boolean active = true;

	public static boolean isActive() {
		return active;
	}

	public static void setActive(boolean value) {
		active = value;
	}

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.serverboundPlay().register(SortPayload.TYPE, SortPayload.CODEC);
		// 専用サーバーで無効にしているときは受け口を作らず、クライアントにボタンを出させない。
		// シングルプレイは途中で切り替えられるので、受け口は作っておいて届いた時点で確かめる
		ServerPlayConnectionEvents.INIT.register((handler, server) -> {
			if (active || !server.isDedicatedServer()) {
				ServerPlayNetworking.registerReceiver(handler, SortPayload.TYPE, (payload, context) -> {
					if (active) {
						sort(context.player(), payload.containerId(), payload.slot());
					}
				});
			}
		});
	}

	public static void sort(ServerPlayer player, int containerId, int slotIndex) {
		AbstractContainerMenu menu = player.containerMenu;
		if (menu.containerId != containerId || !menu.isValidSlotIndex(slotIndex) || !menu.stillValid(player)) {
			return;
		}

		List<Slot> region = region(menu, menu.getSlot(slotIndex));
		if (region.stream().anyMatch(slot -> !slot.mayPickup(player) || !slot.allowModification(player))) {
			return;
		}
		sortSlots(region, SortOrder.of(player.level().getServer()));
		menu.broadcastChanges();
	}

	// プレイヤーのインベントリはホットバーと装備を除いたメイン部分、それ以外はコンテナ全体
	private static List<Slot> region(AbstractContainerMenu menu, Slot target) {
		List<Slot> region = new ArrayList<>();
		for (Slot slot : menu.slots) {
			if (slot.container != target.container) {
				continue;
			}
			if (slot.container instanceof Inventory && (slot.getContainerSlot() < Inventory.SELECTION_SIZE || slot.getContainerSlot() >= Inventory.INVENTORY_SIZE)) {
				continue;
			}
			region.add(slot);
		}
		return region;
	}

	public static void sortSlots(List<Slot> slots, SortOrder order) {
		List<ItemStack> stacks = merge(slots);
		stacks.sort(order.comparator());

		for (int i = 0; i < stacks.size(); i++) {
			if (!slots.get(i).mayPlace(stacks.get(i))) {
				return;
			}
		}
		for (int i = 0; i < slots.size(); i++) {
			slots.get(i).set(i < stacks.size() ? stacks.get(i) : ItemStack.EMPTY);
		}
	}

	// 同じアイテムをまとめる。まとめた結果は元のスロット数以内に収まる
	private static List<ItemStack> merge(List<Slot> slots) {
		List<ItemStack> stacks = new ArrayList<>();
		for (Slot slot : slots) {
			ItemStack remaining = slot.getItem().copy();
			for (ItemStack stack : stacks) {
				if (remaining.isEmpty()) {
					break;
				}
				if (ItemStack.isSameItemSameComponents(stack, remaining)) {
					int moved = Math.clamp(slot.getMaxStackSize(stack) - stack.getCount(), 0, remaining.getCount());
					stack.grow(moved);
					remaining.shrink(moved);
				}
			}
			if (!remaining.isEmpty()) {
				stacks.add(remaining);
			}
		}
		return stacks;
	}
}
