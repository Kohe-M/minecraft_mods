package com.omuraisu.sorter;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class Sorter implements ModInitializer {
	@Override
	public void onInitialize() {
		PayloadTypeRegistry.serverboundPlay().register(SortPayload.TYPE, SortPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(SortPayload.TYPE, (payload, context) -> sort(context.player(), payload.containerId(), payload.slot()));
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
		int[] positions = positions(stacks, order, rowWidth(slots), slots.size());

		for (int i = 0; i < stacks.size(); i++) {
			if (!slots.get(positions[i]).mayPlace(stacks.get(i))) {
				return;
			}
		}
		slots.forEach(slot -> slot.set(ItemStack.EMPTY));
		for (int i = 0; i < stacks.size(); i++) {
			slots.get(positions[i]).set(stacks.get(i));
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

	// 並べ替え済みのスタックそれぞれの置き場所（slots の添字）を返す
	public static int[] positions(List<ItemStack> sorted, SortOrder order, int width, int capacity) {
		List<SortOrder.Group> groups = new ArrayList<>();
		List<Integer> sizes = new ArrayList<>();
		for (ItemStack stack : sorted) {
			SortOrder.Group group = order.group(stack);
			if (groups.isEmpty() || groups.getLast() != group) {
				groups.add(group);
				sizes.add(0);
			}
			sizes.set(sizes.size() - 1, sizes.getLast() + 1);
		}

		int[] starts = SortLayout.place(sizes.stream().mapToInt(Integer::intValue).toArray(), groups.stream().mapToInt(group -> group.band).toArray(), width, capacity);
		int[] positions = new int[sorted.size()];
		int next = 0;
		for (int group = 0; group < starts.length; group++) {
			for (int i = 0; i < sizes.get(group); i++) {
				positions[next++] = starts[group] + i;
			}
		}
		return positions;
	}

	private static int rowWidth(List<Slot> slots) {
		int y = slots.getFirst().y;
		return (int) slots.stream().filter(slot -> slot.y == y).count();
	}
}
