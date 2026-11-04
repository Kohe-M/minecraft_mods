package com.omuraisu.minimap.client;

import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

// 読み込まれている範囲の全画面マップ。ドラッグで移動、ホイールで拡大縮小、クリックでピンを抜き差しする。
// ミニマップと同じく GUI スケールに左右されないよう、位置は実ピクセルで扱う
public class MapScreen extends Screen {
	private static final int MIN_ZOOM = -2;
	private static final int MAX_ZOOM = 3;
	// これより動かしたらクリックではなくドラッグとして扱う（実ピクセル）
	private static final double DRAG_THRESHOLD = 4;

	// 開き直しても倍率は保つ
	private static int zoom = 1;

	private MapTerrain terrain;
	private double centerX;
	private double centerZ;
	private boolean pressed;
	private double dragDistance;

	public MapScreen() {
		super(Component.translatable("key.minimap.open_map"));
	}

	@Override
	protected void init() {
		if (terrain != null || minecraft.player == null) {
			return;
		}
		Vec3 position = minecraft.player.position();
		centerX = position.x;
		centerZ = position.z;
		// クライアントが持ち得るチャンクがすべて入る大きさ
		terrain = new MapTerrain("terrain_large", (minecraft.options.getEffectiveRenderDistance() + 1) * 2 * 16);
		terrain.tick(minecraft);
	}

	@Override
	public void tick() {
		if (terrain != null) {
			terrain.tick(minecraft);
		}
	}

	@Override
	public void removed() {
		if (terrain != null) {
			terrain.release(minecraft);
			terrain = null;
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// 実ピクセル / ブロック
	public double scale() {
		Window window = minecraft.getWindow();
		return MinimapHud.pixelsPerBlock(window.getWidth(), window.getHeight()) * Math.pow(2, zoom);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, height, MapTerrain.EMPTY_COLOR);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		LocalPlayer player = minecraft.player;
		if (player == null || terrain == null) {
			return;
		}

		Window window = minecraft.getWindow();
		int unit = MinimapHud.pixelsPerBlock(window.getWidth(), window.getHeight());
		int guiScale = window.getGuiScale();

		graphics.pose().pushMatrix();
		graphics.pose().scale(1f / guiScale);

		terrain(graphics);

		Pins.Pin hovered = pinAt(mouseX * guiScale, mouseY * guiScale);
		for (Pins.Pin pin : pins()) {
			int width = MapIcons.PIN_WIDTH * unit;
			int height = MapIcons.PIN_HEIGHT * unit;
			if (pin.equals(hovered)) {
				width = width * 5 / 4;
				height = height * 5 / 4;
			}
			MapIcons.pin(graphics, tipX(pin), tipY(pin), width, height, pin.color());
		}

		Vec3 position = player.getPosition(delta);
		MapIcons.arrow(graphics, (float) screenX(position.x), (float) screenY(position.z), player.getViewYRot(delta), unit);

		graphics.pose().popMatrix();
	}

	// 地形があるところだけに重ね、外は背景の色のままにする
	private void terrain(GuiGraphicsExtractor graphics) {
		Window window = minecraft.getWindow();
		int left = Math.max(0, Mth.ceil(screenX(terrain.minX())));
		int top = Math.max(0, Mth.ceil(screenY(terrain.minZ())));
		int right = Math.min(window.getWidth(), Mth.floor(screenX(terrain.minX() + terrain.size)));
		int bottom = Math.min(window.getHeight(), Mth.floor(screenY(terrain.minZ() + terrain.size)));
		if (left >= right || top >= bottom) {
			return;
		}
		float u = textureCoordinate(worldX(left));
		float v = textureCoordinate(worldZ(top));
		float uSpan = (float) ((right - left) / scale() / terrain.size);
		float vSpan = (float) ((bottom - top) / scale() / terrain.size);
		graphics.blit(terrain.texture, left, top, right, bottom, u, u + uSpan, v, v + vSpan);
	}

	private float textureCoordinate(double world) {
		// 大きい座標でも float の精度が落ちないよう先に周期で割る
		return (float) ((world - Math.floor(world / terrain.size) * terrain.size) / terrain.size);
	}

	private List<Pins.Pin> pins() {
		return MinimapClient.PINS.in(minecraft.level.dimension().identifier());
	}

	private double screenX(double worldX) {
		return minecraft.getWindow().getWidth() / 2.0 + (worldX - centerX) * scale();
	}

	private double screenY(double worldZ) {
		return minecraft.getWindow().getHeight() / 2.0 + (worldZ - centerZ) * scale();
	}

	private double worldX(double screenX) {
		return centerX + (screenX - minecraft.getWindow().getWidth() / 2.0) / scale();
	}

	private double worldZ(double screenY) {
		return centerZ + (screenY - minecraft.getWindow().getHeight() / 2.0) / scale();
	}

	private int tipX(Pins.Pin pin) {
		return (int) Math.round(screenX(pin.x() + 0.5));
	}

	private int tipY(Pins.Pin pin) {
		return (int) Math.round(screenY(pin.z() + 0.5));
	}

	// 重なっているときは手前（後に描く方）を選ぶ
	private Pins.Pin pinAt(double x, double y) {
		Window window = minecraft.getWindow();
		int unit = MinimapHud.pixelsPerBlock(window.getWidth(), window.getHeight());
		List<Pins.Pin> pins = pins();
		for (int i = pins.size() - 1; i >= 0; i--) {
			Pins.Pin pin = pins.get(i);
			if (Math.abs(x - tipX(pin)) <= MapIcons.PIN_WIDTH * unit / 2.0
					&& y <= tipY(pin) && y >= tipY(pin) - MapIcons.PIN_HEIGHT * unit) {
				return pin;
			}
		}
		return null;
	}

	private void togglePin(double x, double y) {
		Pins.Pin pin = pinAt(x, y);
		if (pin != null) {
			MinimapClient.PINS.remove(pin);
			return;
		}
		Identifier dimension = minecraft.level.dimension().identifier();
		MinimapClient.PINS.place(new Pins.Pin(dimension, Mth.floor(worldX(x)), Mth.floor(worldZ(y))));
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (MinimapClient.mapKey.matches(event)) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (MinimapClient.mapKey.matchesMouse(event)) {
			onClose();
			return true;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			pressed = true;
			dragDistance = 0;
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (!pressed || event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return super.mouseDragged(event, dx, dy);
		}
		double guiScale = minecraft.getWindow().getGuiScale();
		dragDistance += (Math.abs(dx) + Math.abs(dy)) * guiScale;
		if (dragDistance >= DRAG_THRESHOLD) {
			centerX -= dx * guiScale / scale();
			centerZ -= dy * guiScale / scale();
		}
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (!pressed || event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return super.mouseReleased(event);
		}
		pressed = false;
		if (dragDistance < DRAG_THRESHOLD) {
			double guiScale = minecraft.getWindow().getGuiScale();
			togglePin(event.x() * guiScale, event.y() * guiScale);
		}
		return true;
	}

	// カーソルの下の地点を動かさずに拡大縮小する
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		double guiScale = minecraft.getWindow().getGuiScale();
		double x = mouseX * guiScale;
		double y = mouseY * guiScale;
		double anchorX = worldX(x);
		double anchorZ = worldZ(y);
		zoom = Mth.clamp(zoom + (int) Math.signum(scrollY), MIN_ZOOM, MAX_ZOOM);
		centerX += anchorX - worldX(x);
		centerZ += anchorZ - worldZ(y);
		return true;
	}
}
