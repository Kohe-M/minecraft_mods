package com.omuraisu.xray.client;

import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

// キーを押している間だけ開くホイール。中央が全鉱石、外周が鉱石ごとと、エンティティとチェスト。
// マウスの方向で選び、キーを離すかクリックで確定する
public class ModeWheelScreen extends Screen {
	private static final int RADIUS = 54;
	private static final int SLOT = 24;
	private static final int CENTER_SLOT = 30;
	private static final int DEADZONE = 20;

	private static final int SLOT_COLOR = 0xB0101318;
	private static final int HOVER_COLOR = 0xE0384050;
	private static final int BORDER_COLOR = 0x60FFFFFF;
	private static final int HOVER_BORDER_COLOR = 0xFFFFFFFF;
	private static final int CURRENT_BORDER_COLOR = 0xFF4FD8E0;

	private XrayMode hovered = XrayMode.ALL;

	public ModeWheelScreen() {
		super(Component.translatable("key.xray.toggle"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, height, 0x40000000);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		int cx = width / 2;
		int cy = height / 2;
		hovered = pick(mouseX - cx, mouseY - cy);

		slot(graphics, XrayMode.ALL, cx, cy, CENTER_SLOT);
		// 全鉱石は外周の先頭 4 種を小さく並べて表す
		List<XrayMode> preview = XrayMode.RING.subList(0, 4);
		for (int i = 0; i < preview.size(); i++) {
			icon(graphics, preview.get(i), cx - 6 + i % 2 * 12, cy - 6 + i / 2 * 12, 0.625F);
		}

		List<XrayMode> ring = XrayMode.RING;
		for (int i = 0; i < ring.size(); i++) {
			double angle = -Math.PI / 2 + i * 2 * Math.PI / ring.size();
			int x = cx + (int) Math.round(Math.cos(angle) * RADIUS);
			int y = cy + (int) Math.round(Math.sin(angle) * RADIUS);
			XrayMode mode = ring.get(i);
			slot(graphics, mode, x, y, SLOT);
			icon(graphics, mode, x, y, mode == hovered ? 1.25F : 1.0F);
		}

		// 下はホットバーや体力と重なりやすいので上に出す
		graphics.centeredText(font, hovered.displayName(), cx, cy - RADIUS - SLOT / 2 - 14, 0xFFFFFFFF);
	}

	private void slot(GuiGraphicsExtractor graphics, XrayMode mode, int x, int y, int size) {
		int left = x - size / 2;
		int top = y - size / 2;
		graphics.fill(left, top, left + size, top + size, mode == hovered ? HOVER_COLOR : SLOT_COLOR);
		int border = mode == hovered ? HOVER_BORDER_COLOR : mode == XrayClient.mode() ? CURRENT_BORDER_COLOR : BORDER_COLOR;
		graphics.outline(left, top, size, size, border);
		if (mode == XrayClient.mode() && mode != hovered) {
			graphics.outline(left + 1, top + 1, size - 2, size - 2, CURRENT_BORDER_COLOR);
		}
	}

	private static void icon(GuiGraphicsExtractor graphics, XrayMode mode, int x, int y, float scale) {
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().scale(scale, scale);
		graphics.item(new ItemStack(mode.icon), -8, -8);
		graphics.pose().popMatrix();
	}

	private static XrayMode pick(int dx, int dy) {
		if (dx * dx + dy * dy < DEADZONE * DEADZONE) {
			return XrayMode.ALL;
		}
		List<XrayMode> ring = XrayMode.RING;
		double step = 2 * Math.PI / ring.size();
		// 真上を 0 として時計回り（画面の y は下向き）
		double angle = Math.atan2(dy, dx) + Math.PI / 2;
		int index = (int) Math.round(angle / step);
		return ring.get(Math.floorMod(index, ring.size()));
	}

	private void confirm() {
		onClose();
		XrayClient.select(minecraft, hovered);
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		if (XrayClient.toggle.matches(event)) {
			confirm();
			return true;
		}
		return super.keyReleased(event);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (XrayClient.toggle.matchesMouse(event)) {
			confirm();
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			confirm();
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}
}
