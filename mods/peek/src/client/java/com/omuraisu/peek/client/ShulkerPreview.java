package com.omuraisu.peek.client;

import java.util.List;

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;

// シュルカーボックスの中身を、開いたときと同じ並びで持つ
public record ShulkerPreview(List<ItemStack> slots) implements TooltipComponent {
	public static final int COLUMNS = ShulkerBoxBlockEntity.COLUMNS;

	// 中身が空のときと、ツールチップで中身を隠しているときは出さない
	public static boolean shows(ItemStack stack) {
		return stack.getItem() instanceof BlockItem item && item.getBlock() instanceof ShulkerBoxBlock
				&& stack.getOrDefault(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT).shows(DataComponents.CONTAINER)
				&& stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).nonEmptyItems().iterator().hasNext();
	}

	public static ShulkerPreview of(ItemStack stack) {
		ItemContainerContents contents = stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
		int rows = Math.max(ShulkerBoxBlockEntity.ROWS, Math.ceilDiv(contents.size(), COLUMNS));
		NonNullList<ItemStack> slots = NonNullList.withSize(rows * COLUMNS, ItemStack.EMPTY);
		contents.copyInto(slots);
		return new ShulkerPreview(List.copyOf(slots));
	}

	public int rows() {
		return slots.size() / COLUMNS;
	}
}
