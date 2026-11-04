package com.omuraisu.minimap.client;

import com.mojang.blaze3d.platform.Window;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

// GUI スケールの設定に左右されないよう、ウィンドウの実ピクセルを単位に描く
final class MinimapHud {
	private static final Identifier ARROW = Identifier.fromNamespaceAndPath("minimap", "arrow");
	private static final Identifier PIN = Identifier.fromNamespaceAndPath("minimap", "pin");

	private static final int FRAME_COLOR = 0xFF0B0D12;
	private static final int FADED_PIN_ALPHA = 0xA0;

	// 以下は 1 ブロックの大きさを単位にした長さ
	private static final int MARGIN = 8;
	private static final int ARROW_SIZE = 13;
	private static final int PIN_WIDTH = 9;
	private static final int PIN_HEIGHT = 13;

	// 短辺の 1/4 前後になる。地図がぼやけないよう 1 ブロックは整数ピクセルにする
	static int pixelsPerBlock(int windowWidth, int windowHeight) {
		return Math.max(1, Math.round(Math.min(windowWidth, windowHeight) / 540f));
	}

	static void extract(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		// 左上は F3 の表示と重なる
		if (player == null || client.getDebugOverlay().showDebugScreen()) {
			return;
		}

		Window window = client.getWindow();
		int unit = pixelsPerBlock(window.getWidth(), window.getHeight());
		int size = MapTerrain.VIEW * unit;
		float partialTick = delta.getGameTimeDeltaPartialTick(false);
		Vec3 position = player.getPosition(partialTick);
		// 地図の左上のワールド座標（実ピクセル）
		int left = Mth.floor(position.x * unit) - size / 2;
		int top = Mth.floor(position.z * unit) - size / 2;

		graphics.pose().pushMatrix();
		graphics.pose().scale(1f / window.getGuiScale());
		graphics.pose().translate(MARGIN * unit, MARGIN * unit);

		graphics.fill(-unit, -unit, size + unit, size + unit, FRAME_COLOR);
		terrain(graphics, left, top, size, unit);
		for (Pins.Pin pin : MinimapClient.PINS.in(player.level().dimension().identifier())) {
			pin(graphics, pin, left, top, size, unit);
		}
		arrow(graphics, player.getViewYRot(partialTick), size, unit);

		graphics.pose().popMatrix();
	}

	private static void terrain(GuiGraphicsExtractor graphics, int left, int top, int size, int unit) {
		float period = MapTerrain.SIZE * unit;
		float u = Math.floorMod(left, MapTerrain.SIZE * unit) / period;
		float v = Math.floorMod(top, MapTerrain.SIZE * unit) / period;
		float span = size / period;
		graphics.blit(MapTerrain.TEXTURE, 0, 0, size, size, u, u + span, v, v + span);
	}

	private static void pin(GuiGraphicsExtractor graphics, Pins.Pin pin, int left, int top, int size, int unit) {
		int width = PIN_WIDTH * unit;
		int height = PIN_HEIGHT * unit;
		float half = size / 2f;
		float dx = Mth.floor((pin.x() + 0.5) * unit) - left - half;
		float dy = Mth.floor((pin.z() + 0.5) * unit) - top - half;
		boolean inside = Math.abs(dx) <= half && Math.abs(dy) <= half;

		// 枠に収まるところまで中心へ寄せる。遠いピンは方角を保って縁に出る
		float reach = 1;
		if (dx != 0) {
			reach = Math.min(reach, (half - width / 2f) / Math.abs(dx));
		}
		if (dy != 0) {
			reach = Math.min(reach, (dy > 0 ? half : half - height) / Math.abs(dy));
		}
		int tipX = Math.round(half + dx * reach);
		int tipY = Math.round(half + dy * reach);

		int color = inside ? pin.color() : ARGB.color(FADED_PIN_ALPHA, pin.color());
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PIN, tipX - width / 2, tipY - height, width, height, color);
	}

	private static void arrow(GuiGraphicsExtractor graphics, float yaw, int size, int unit) {
		int arrowSize = ARROW_SIZE * unit;
		graphics.pose().pushMatrix();
		graphics.pose().translate(size / 2f, size / 2f);
		// yaw は南が 0 で時計回り、矢印の絵は北向き
		graphics.pose().rotate((yaw - 180) * Mth.DEG_TO_RAD);
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, ARROW, -arrowSize / 2, -arrowSize / 2, arrowSize, arrowSize);
		graphics.pose().popMatrix();
	}

	private MinimapHud() {
	}
}
