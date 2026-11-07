package com.omuraisu.peek.test;

import java.util.List;

import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;

public final class RenderedTooltip {
	// 描画スレッドでだけ読み書きする
	public static List<ClientTooltipComponent> last;

	private RenderedTooltip() {
	}
}
