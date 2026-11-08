package com.omuraisu.minimap.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.PlayerSkin;

// ミニマップとマップ画面で共通の絵
final class MapIcons {
	// 1 ブロックを単位にした大きさ
	static final int PIN_WIDTH = 9;
	static final int PIN_HEIGHT = 13;
	static final int FACE_SIZE = 8;
	// 文字の高さと背景の余白（GUI 座標）
	static final int LABEL_HEIGHT = 11;

	private static final int ARROW_SIZE = 13;
	private static final int LABEL_BACKGROUND = 0x90000000;
	private static final int LABEL_COLOR = 0xFFFFFFFF;
	private static final int FACE_OUTLINE_COLOR = 0xFF101318;
	static final int FADED_ALPHA = 0xA0;

	private static final Identifier ARROW = Identifier.fromNamespaceAndPath("minimap", "arrow");
	private static final Identifier PIN = Identifier.fromNamespaceAndPath("minimap", "pin");

	// 下端の (tipX, tipY) が刺さる位置。長さは実ピクセル
	static void pin(GuiGraphicsExtractor graphics, int tipX, int tipY, int width, int height, int color) {
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PIN, tipX - width / 2, tipY - height, width, height, color);
	}

	// 他のプレイヤーの顔。範囲の外にいる相手は薄くする。長さは実ピクセル
	static void face(GuiGraphicsExtractor graphics, PlayerSkin skin, int centerX, int centerY, int unit, boolean faded) {
		int size = FACE_SIZE * unit;
		int left = centerX - size / 2;
		int top = centerY - size / 2;
		int outline = faded ? ARGB.color(FADED_ALPHA, FACE_OUTLINE_COLOR) : FACE_OUTLINE_COLOR;
		graphics.fill(left - unit, top - unit, left + size + unit, top + size + unit, outline);
		PlayerFaceExtractor.extractRenderState(graphics, skin, left, top, size, faded ? ARGB.color(FADED_ALPHA, 0xFFFFFF) : 0xFFFFFFFF);
	}

	static void arrow(GuiGraphicsExtractor graphics, float x, float y, float yaw, int unit) {
		int size = ARROW_SIZE * unit;
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		// yaw は南が 0 で時計回り、矢印の絵は北向き
		graphics.pose().rotate((yaw - 180) * Mth.DEG_TO_RAD);
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, ARROW, -size / 2, -size / 2, size, size);
		graphics.pose().popMatrix();
	}

	// 地形の上でも読めるよう背景を敷く。座標は GUI 座標
	static void label(GuiGraphicsExtractor graphics, Font font, String text, int centerX, int top) {
		int width = font.width(text);
		labelAt(graphics, font, text, Mth.clamp(centerX - width / 2, 2, Math.max(2, graphics.guiWidth() - width - 2)), top);
	}

	static void labelAt(GuiGraphicsExtractor graphics, Font font, String text, int left, int top) {
		graphics.fill(left - 2, top, left + font.width(text) + 2, top + LABEL_HEIGHT - 1, LABEL_BACKGROUND);
		graphics.text(font, text, left, top + 1, LABEL_COLOR, false);
	}

	// ブロック座標。数で分かるので軸の名前は付けない
	static String coordinates(int... values) {
		StringBuilder text = new StringBuilder();
		for (int value : values) {
			if (!text.isEmpty()) {
				text.append(", ");
			}
			text.append(value);
		}
		return text.toString();
	}

	private MapIcons() {
	}
}
