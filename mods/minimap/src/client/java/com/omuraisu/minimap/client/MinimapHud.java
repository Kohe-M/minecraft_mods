package com.omuraisu.minimap.client;

import com.mojang.blaze3d.platform.Window;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.phys.Vec3;

// GUI スケールの設定に左右されないよう、ウィンドウの実ピクセルを単位に描く
final class MinimapHud {
	private static final int VIEW = 160;
	// 表示は直前の tick から補間するので、速く動いても見えている範囲を描き終えているよう広めに持つ
	private static final int TERRAIN_SIZE = VIEW + 16;

	static final MapTerrain TERRAIN = new MapTerrain("terrain", TERRAIN_SIZE, MapTerrain.EMPTY_COLOR);

	private static final int FRAME_COLOR = 0xFF0B0D12;
	private static final int BENEFICIAL_EFFECTS_HEIGHT = 25;
	private static final int HARMFUL_EFFECTS_HEIGHT = 51;
	private static final int DEMO_EFFECTS_OFFSET = 15;
	// 1 ブロックを単位にした余白
	private static final int MARGIN = 8;

	// ミニマップが短辺の 3 割前後になる。地図がぼやけないよう 1 ブロックは整数ピクセルにする
	static int pixelsPerBlock(int windowWidth, int windowHeight) {
		return Math.max(1, Math.round(Math.min(windowWidth, windowHeight) / 540f));
	}

	static void extract(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		// 右上は F3 の表示と重なる
		if (player == null || client.getDebugOverlay().showDebugScreen()) {
			return;
		}

		Window window = client.getWindow();
		int unit = pixelsPerBlock(window.getWidth(), window.getHeight());
		int size = VIEW * unit;
		float partialTick = delta.getGameTimeDeltaPartialTick(false);
		Vec3 position = player.getPosition(partialTick);
		// 地図の左上のワールド座標（実ピクセル）
		int left = Mth.floor(position.x * unit) - size / 2;
		int top = Mth.floor(position.z * unit) - size / 2;

		graphics.pose().pushMatrix();
		graphics.pose().scale(1f / window.getGuiScale());
		graphics.pose().translate(window.getWidth() - size - MARGIN * unit,
				MARGIN * unit + effectsHeight(client, player) * window.getGuiScale());

		graphics.fill(-unit, -unit, size + unit, size + unit, FRAME_COLOR);
		terrain(graphics, left, top, size, unit);
		for (Pins.Pin pin : MinimapClient.PINS.in(player.level().dimension().identifier())) {
			pin(graphics, pin, left, top, size, unit);
		}
		for (PlayerMarkers.Marker marker : PlayerMarkers.collect(client, partialTick)) {
			face(graphics, marker, left, top, size, unit);
		}
		MapIcons.arrow(graphics, size / 2f, size / 2f, player.getViewYRot(partialTick), unit);

		graphics.pose().popMatrix();
	}

	// ステータス効果のアイコンが右上に並ぶ高さ（GUI 座標）。その下に地図を置く
	private static int effectsHeight(Minecraft client, LocalPlayer player) {
		int height = 0;
		for (MobEffectInstance effect : player.getActiveEffects()) {
			if (effect.showIcon()) {
				height = Math.max(height, effect.getEffect().value().isBeneficial() ? BENEFICIAL_EFFECTS_HEIGHT : HARMFUL_EFFECTS_HEIGHT);
			}
		}
		return height > 0 && client.isDemo() ? height + DEMO_EFFECTS_OFFSET : height;
	}

	private static void terrain(GuiGraphicsExtractor graphics, int left, int top, int size, int unit) {
		float period = TERRAIN_SIZE * unit;
		float u = Math.floorMod(left, TERRAIN_SIZE * unit) / period;
		float v = Math.floorMod(top, TERRAIN_SIZE * unit) / period;
		float span = size / period;
		graphics.blit(TERRAIN.texture, 0, 0, size, size, u, u + span, v, v + span);
	}

	private static void pin(GuiGraphicsExtractor graphics, Pins.Pin pin, int left, int top, int size, int unit) {
		int width = MapIcons.PIN_WIDTH * unit;
		int height = MapIcons.PIN_HEIGHT * unit;
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

		int color = inside ? pin.color() : ARGB.color(MapIcons.FADED_ALPHA, pin.color());
		MapIcons.pin(graphics, tipX, tipY, width, height, color);
	}

	private static void face(GuiGraphicsExtractor graphics, PlayerMarkers.Marker marker, int left, int top, int size, int unit) {
		float half = size / 2f;
		float dx = Mth.floor(marker.x() * unit) - left - half;
		float dy = Mth.floor(marker.z() * unit) - top - half;
		boolean inside = Math.abs(dx) <= half && Math.abs(dy) <= half;

		// ピンと同じく、遠い相手は方角を保って縁に出す
		float limit = half - (MapIcons.FACE_SIZE + 2) * unit / 2f;
		float reach = Math.min(1, limit / Math.max(Math.abs(dx), Math.abs(dy)));
		MapIcons.face(graphics, marker.skin(), Math.round(half + dx * reach), Math.round(half + dy * reach), unit, !inside);
	}

	private MinimapHud() {
	}
}
