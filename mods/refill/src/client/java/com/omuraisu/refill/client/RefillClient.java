package com.omuraisu.refill.client;

import java.util.EnumMap;
import java.util.Map;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class RefillClient implements ClientModInitializer {
	// 手ごとの、最後に使ったときの中身。これが残っている手が空になったら、使い切ったとみなす
	// 骨粉や繁殖の餌のようにサーバーだけが減らすものは、使った少し後に空になるので、消すまで持っておく
	private static final Map<InteractionHand, Used> USED = new EnumMap<>(InteractionHand.class);

	// Jori の一覧で無効にされている間は false。何も補充しない
	private static volatile boolean active = true;

	private record Used(ItemStack stack, int handSlot) {
	}

	@Override
	public void onInitializeClient() {
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> onUse(player, level, hand));
		UseItemCallback.EVENT.register(RefillClient::onUse);
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> onUse(player, level, hand));
		ClientTickEvents.END_CLIENT_TICK.register(RefillClient::tick);
	}

	public static boolean isActive() {
		return active;
	}

	public static void setActive(boolean value) {
		active = value;
	}

	public static void forgetUses() {
		USED.clear();
	}

	private static InteractionResult onUse(Player player, Level level, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		// シングルプレイでは内蔵サーバーのスレッドからも呼ばれる
		if (active && level.isClientSide() && !stack.isEmpty()) {
			USED.put(hand, new Used(stack.copy(), handSlot(player, hand)));
		}
		return InteractionResult.PASS;
	}

	private static void tick(Minecraft client) {
		LocalPlayer player = client.player;
		// 画面を開いている間に手から動かしたものは補充しない
		if (!active || player == null || client.gui.screen() != null) {
			forgetUses();
			return;
		}
		for (InteractionHand hand : InteractionHand.values()) {
			Used used = USED.get(hand);
			if (used == null) {
				continue;
			}
			if (used.handSlot() != handSlot(player, hand)) {
				USED.remove(hand);
			} else if (player.getItemInHand(hand).isEmpty()) {
				USED.remove(hand);
				refill(client, player, used);
			}
		}
	}

	private static void refill(Minecraft client, LocalPlayer player, Used used) {
		Inventory inventory = player.getInventory();
		int source = -1;
		for (int i = Inventory.SELECTION_SIZE; i < Inventory.INVENTORY_SIZE; i++) {
			ItemStack stack = inventory.getItem(i);
			if (ItemStack.isSameItemSameComponents(stack, used.stack()) && (source == -1 || stack.getCount() < inventory.getItem(source).getCount())) {
				source = i;
			}
		}
		if (source != -1) {
			// メインインベントリの番号は InventoryMenu のスロット番号と同じ
			client.gameMode.handleContainerInput(player.inventoryMenu.containerId, source, used.handSlot(), ContainerInput.SWAP, player);
		}
	}

	private static int handSlot(Player player, InteractionHand hand) {
		return hand == InteractionHand.MAIN_HAND ? player.getInventory().getSelectedSlot() : Inventory.SLOT_OFFHAND;
	}
}
