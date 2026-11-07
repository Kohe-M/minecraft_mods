package com.omuraisu.peek.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

// インベントリと同じスロットの見た目で、9 列に並べて描く
public record ClientShulkerPreview(ShulkerPreview preview) implements ClientTooltipComponent {
	private static final Identifier SLOT_SPRITE = Identifier.withDefaultNamespace("container/slot");
	private static final int SLOT_SIZE = 18;
	// 下に続く行との間
	private static final int MARGIN_BOTTOM = 2;

	@Override
	public int getWidth(Font font) {
		return ShulkerPreview.COLUMNS * SLOT_SIZE;
	}

	@Override
	public int getHeight(Font font) {
		return preview.rows() * SLOT_SIZE + MARGIN_BOTTOM;
	}

	@Override
	public void extractImage(Font font, int x, int y, int width, int height, GuiGraphicsExtractor graphics) {
		for (int i = 0; i < preview.slots().size(); i++) {
			int slotX = x + i % ShulkerPreview.COLUMNS * SLOT_SIZE;
			int slotY = y + i / ShulkerPreview.COLUMNS * SLOT_SIZE;
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_SPRITE, slotX, slotY, SLOT_SIZE, SLOT_SIZE);
			ItemStack stack = preview.slots().get(i);
			if (!stack.isEmpty()) {
				graphics.item(stack, slotX + 1, slotY + 1);
				graphics.itemDecorations(font, stack, slotX + 1, slotY + 1);
			}
		}
	}
}
