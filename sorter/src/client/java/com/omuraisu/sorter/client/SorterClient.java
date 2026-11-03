package com.omuraisu.sorter.client;

import java.util.function.IntSupplier;

import com.omuraisu.sorter.SortPayload;
import com.omuraisu.sorter.client.mixin.AbstractContainerScreenAccessor;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

public class SorterClient implements ClientModInitializer {
	private static final int SIZE = 9;
	// 画面の縁からスロット欄の右端まで
	private static final int MARGIN = 7;

	private static final WidgetSprites SPRITES = new WidgetSprites(
			Identifier.fromNamespaceAndPath("sorter", "sort"),
			Identifier.fromNamespaceAndPath("sorter", "sort_highlighted"));

	@Override
	public void onInitializeClient() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!ClientPlayNetworking.canSend(SortPayload.TYPE)) {
				return;
			}
			if (screen instanceof ContainerScreen || screen instanceof ShulkerBoxScreen) {
				AbstractContainerScreen<?> container = (AbstractContainerScreen<?>) screen;
				AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
				addButton(container, accessor::getTitleLabelY, 0);
				addButton(container, accessor::getInventoryLabelY, mainInventorySlot(container.getMenu()));
			} else if (screen instanceof InventoryScreen inventory) {
				AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
				addButton(inventory, accessor::getInventoryLabelY, mainInventorySlot(inventory.getMenu()));
			}
		});
	}

	// labelY の行の右端、スロット欄の真上に置く
	private static void addButton(AbstractContainerScreen<?> screen, IntSupplier labelY, int slot) {
		if (slot < 0) {
			return;
		}
		AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
		ImageButton button = new ImageButton(0, 0, SIZE, SIZE, SPRITES,
				pressed -> ClientPlayNetworking.send(new SortPayload(screen.getMenu().containerId, slot)),
				Component.translatable("button.sorter.sort"));
		button.setTooltip(Tooltip.create(Component.translatable("button.sorter.sort")));
		Screens.getWidgets(screen).add(button);

		// レシピ本の開閉で画面の位置が変わるため、描画のたびに合わせる
		ScreenEvents.beforeExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
			button.setX(accessor.getLeftPos() + accessor.getImageWidth() - MARGIN - SIZE);
			button.setY(accessor.getTopPos() + labelY.getAsInt() - 1);
		});
	}

	private static int mainInventorySlot(AbstractContainerMenu menu) {
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot slot = menu.slots.get(i);
			if (slot.container instanceof Inventory && slot.getContainerSlot() == Inventory.SELECTION_SIZE) {
				return i;
			}
		}
		return -1;
	}
}
