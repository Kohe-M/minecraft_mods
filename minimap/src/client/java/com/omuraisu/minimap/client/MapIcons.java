package com.omuraisu.minimap.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

// ミニマップとマップ画面で共通の絵。長さは実ピクセル
final class MapIcons {
	// 1 ブロックを単位にした大きさ
	static final int PIN_WIDTH = 9;
	static final int PIN_HEIGHT = 13;

	private static final int ARROW_SIZE = 13;

	private static final Identifier ARROW = Identifier.fromNamespaceAndPath("minimap", "arrow");
	private static final Identifier PIN = Identifier.fromNamespaceAndPath("minimap", "pin");

	// 下端の (tipX, tipY) が刺さる位置
	static void pin(GuiGraphicsExtractor graphics, int tipX, int tipY, int width, int height, int color) {
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PIN, tipX - width / 2, tipY - height, width, height, color);
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

	private MapIcons() {
	}
}
