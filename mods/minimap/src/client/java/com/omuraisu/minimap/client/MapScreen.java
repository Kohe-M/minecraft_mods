package com.omuraisu.minimap.client;

import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

// 読み込まれている範囲の全画面マップ。ドラッグで移動、ホイールで拡大縮小、
// 刺すキーでカーソルの位置にピンを刺し、Shift + 左クリックで抜き、右クリックで名前を変える。右下にカーソルの位置の座標を出す。
// 位置を確かめようとして刺したり抜いたりしないよう、ただの左クリックには割り当てない。
// ミニマップと同じく GUI スケールに左右されないよう、地図の上の位置は実ピクセルで扱う
public class MapScreen extends Screen {
	private static final int MIN_ZOOM = -2;
	private static final int MAX_ZOOM = 3;
	// これより動かしたらクリックではなくドラッグとして扱う（実ピクセル）
	private static final double DRAG_THRESHOLD = 4;

	private static final int NAME_MAX_LENGTH = 32;
	private static final int NAME_BOX_WIDTH = 120;
	private static final int NAME_BOX_HEIGHT = 18;

	private static final int HINT_MARGIN = 4;

	// 開き直しても倍率は保つ
	private static int zoom = 1;

	private MapTerrain terrain;
	private double centerX;
	private double centerZ;
	private boolean pressed;
	private double dragDistance;
	private boolean removing;

	// 名前を入力している間だけ持つ
	private Pins.Pin editing;
	private EditBox nameBox;
	// 刺したキーの文字が名前に入らないよう、キーを離すまで文字を受け付けない
	private boolean ignoreTextInput;

	public MapScreen() {
		super(Component.translatable("key.minimap.open_map"));
	}

	@Override
	protected void init() {
		if (terrain == null && minecraft.player != null) {
			Vec3 position = minecraft.player.position();
			centerX = position.x;
			centerZ = position.z;
			// クライアントが持ち得るチャンクがすべて入る大きさ。外は遠くの地形（FarTerrain）に任せる
			terrain = new MapTerrain("terrain_large", (minecraft.options.getEffectiveRenderDistance() + 1) * 2 * 16, 0);
			terrain.tick(minecraft);
			MinimapClient.FAR_TERRAIN.open(minecraft, minecraft.level.dimension().identifier(), minecraft.player.chunkPosition());
		}
		// ウィンドウの大きさが変わると作り直されるので、入力中の文字を引き継ぐ
		if (editing != null) {
			addNameBox(nameBox != null ? nameBox.getValue() : editing.name());
		}
	}

	@Override
	public void tick() {
		if (terrain != null) {
			terrain.tick(minecraft);
		}
		if (terrain != null && minecraft.player != null && minecraft.level != null) {
			Window window = minecraft.getWindow();
			MinimapClient.FAR_TERRAIN.tick(minecraft, minecraft.level.dimension().identifier(), minecraft.player.chunkPosition(), scale(),
					Mth.floor(worldX(0)) >> 4, Mth.floor(worldZ(0)) >> 4,
					Mth.floor(worldX(window.getWidth())) >> 4, Mth.floor(worldZ(window.getHeight())) >> 4,
					Mth.floor(centerX) >> 4, Mth.floor(centerZ) >> 4);
		}
	}

	@Override
	public void removed() {
		if (editing != null) {
			finishEditing(true);
		}
		if (terrain != null) {
			terrain.release(minecraft);
			terrain = null;
		}
		MinimapClient.FAR_TERRAIN.close(minecraft);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// 実ピクセル / ブロック
	public double scale() {
		return unit() * Math.pow(2, zoom);
	}

	private int unit() {
		Window window = minecraft.getWindow();
		return MinimapHud.pixelsPerBlock(window.getWidth(), window.getHeight());
	}

	// 入力欄より奥に描くので背景の段で描く
	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, height, MapTerrain.EMPTY_COLOR);
		LocalPlayer player = minecraft.player;
		if (player == null || terrain == null) {
			return;
		}

		int unit = unit();
		int guiScale = minecraft.getWindow().getGuiScale();
		int pinWidth = MapIcons.PIN_WIDTH * unit;
		int pinHeight = MapIcons.PIN_HEIGHT * unit;

		graphics.pose().pushMatrix();
		graphics.pose().scale(1f / guiScale);

		farTerrain(graphics);
		terrain(graphics);

		Pins.Pin hovered = nameBox != null ? null : pinAt(mouseX * guiScale, mouseY * guiScale);
		for (Pins.Pin pin : pins()) {
			if (pin.equals(hovered) || pin.equals(editing)) {
				MapIcons.pin(graphics, tipX(pin), tipY(pin), pinWidth * 5 / 4, pinHeight * 5 / 4, pin.color());
			} else {
				MapIcons.pin(graphics, tipX(pin), tipY(pin), pinWidth, pinHeight, pin.color());
			}
		}

		List<PlayerMarkers.Marker> markers = PlayerMarkers.collect(minecraft, delta);
		int[][] faces = new int[markers.size()][];
		for (int i = 0; i < faces.length; i++) {
			faces[i] = face(graphics, markers.get(i), unit);
		}

		Vec3 position = player.getPosition(delta);
		MapIcons.arrow(graphics, (float) screenX(position.x), (float) screenY(position.z), player.getViewYRot(delta), unit);

		graphics.pose().popMatrix();

		// 文字はプレイヤーの GUI スケールに合わせる
		for (Pins.Pin pin : pins()) {
			if (!pin.name().isEmpty() && !pin.equals(editing)) {
				MapIcons.label(graphics, font, pin.name(), tipX(pin) / guiScale, (tipY(pin) - pinHeight) / guiScale - MapIcons.LABEL_HEIGHT);
			}
		}
		for (int i = 0; i < faces.length; i++) {
			int faceBottom = faces[i][1] + (MapIcons.FACE_SIZE + 2) * unit / 2;
			// 画面の下の縁にいるときは顔の上に出す
			int labelTop = faceBottom / guiScale + MapIcons.LABEL_HEIGHT <= height
					? faceBottom / guiScale
					: (faces[i][1] - (MapIcons.FACE_SIZE + 2) * unit / 2) / guiScale - MapIcons.LABEL_HEIGHT;
			MapIcons.label(graphics, font, markers.get(i).name(), faces[i][0] / guiScale, labelTop);
		}

		// 入力中のクリックは確定にしかならないので、操作の説明は出さない
		if (nameBox == null) {
			hints(graphics);
		}
		cursorCoordinates(graphics);
	}

	// 刺すキーで刺さる位置と同じブロックを出すので、刺すときと同じくカーソルの位置は丸めずに読む
	private void cursorCoordinates(GuiGraphicsExtractor graphics) {
		Window window = minecraft.getWindow();
		int guiScale = window.getGuiScale();
		String text = MapIcons.coordinates(Mth.floor(worldX(minecraft.mouseHandler.getScaledXPos(window) * guiScale)),
				Mth.floor(worldZ(minecraft.mouseHandler.getScaledYPos(window) * guiScale)));
		MapIcons.labelAt(graphics, font, text, width - HINT_MARGIN - font.width(text), height - HINT_MARGIN - MapIcons.LABEL_HEIGHT);
	}

	// 刺すキーは割り当てを変えられるので、毎回いまのキーで作る
	private void hints(GuiGraphicsExtractor graphics) {
		List<Component> hints = List.of(
				Component.translatable("gui.minimap.hint_place", MinimapClient.placeKey.getTranslatedKeyMessage()),
				Component.translatable("gui.minimap.hint_remove"));
		int top = height - HINT_MARGIN - hints.size() * MapIcons.LABEL_HEIGHT;
		for (Component hint : hints) {
			MapIcons.labelAt(graphics, font, hint.getString(), HINT_MARGIN, top);
			top += MapIcons.LABEL_HEIGHT;
		}
	}

	// 画面の外の相手は方角を保って縁に寄せる。描いた中心の位置を返す
	private int[] face(GuiGraphicsExtractor graphics, PlayerMarkers.Marker marker, int unit) {
		Window window = minecraft.getWindow();
		double halfWidth = window.getWidth() / 2.0;
		double halfHeight = window.getHeight() / 2.0;
		double dx = screenX(marker.x()) - halfWidth;
		double dy = screenY(marker.z()) - halfHeight;
		boolean inside = Math.abs(dx) <= halfWidth && Math.abs(dy) <= halfHeight;

		double margin = (MapIcons.FACE_SIZE + 2) * unit / 2.0;
		double reach = Math.min(1, Math.min((halfWidth - margin) / Math.abs(dx), (halfHeight - margin) / Math.abs(dy)));
		int x = (int) Math.round(halfWidth + dx * reach);
		int y = (int) Math.round(halfHeight + dy * reach);
		MapIcons.face(graphics, marker.skin(), x, y, unit, !inside);
		return new int[] {x, y};
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (nameBox != null) {
			positionNameBox();
		}
		super.extractRenderState(graphics, mouseX, mouseY, delta);
	}

	// 隣どうしで端の位置を同じ式で丸めて、すき間が出ないようにする
	private void farTerrain(GuiGraphicsExtractor graphics) {
		Window window = minecraft.getWindow();
		MinimapClient.FAR_TERRAIN.forEachRegion((texture, minX, minZ, size) -> {
			int left = (int) Math.round(screenX(minX));
			int top = (int) Math.round(screenY(minZ));
			int right = (int) Math.round(screenX(minX + size));
			int bottom = (int) Math.round(screenY(minZ + size));
			if (left < right && top < bottom && right > 0 && bottom > 0 && left < window.getWidth() && top < window.getHeight()) {
				graphics.blit(texture, left, top, right, bottom, 0, 1, 0, 1);
			}
		});
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
		int unit = unit();
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

	// 既にあるピンの上では何もしない
	private void place(double x, double y) {
		if (pinAt(x, y) != null) {
			return;
		}
		Identifier dimension = minecraft.level.dimension().identifier();
		Pins.Pin placed = new Pins.Pin(dimension, Mth.floor(worldX(x)), Mth.floor(worldZ(y)));
		MinimapClient.PINS.place(placed);
		startEditing(placed);
	}

	private void startEditing(Pins.Pin pin) {
		editing = pin;
		addNameBox(pin.name());
	}

	private void addNameBox(String value) {
		nameBox = new EditBox(font, 0, 0, NAME_BOX_WIDTH, NAME_BOX_HEIGHT, Component.translatable("gui.minimap.pin_name"));
		nameBox.setMaxLength(NAME_MAX_LENGTH);
		nameBox.setValue(value);
		positionNameBox();
		addRenderableWidget(nameBox);
		setInitialFocus(nameBox);
	}

	// ピンの右に出し、画面からはみ出すときは内側に寄せる
	private void positionNameBox() {
		int guiScale = minecraft.getWindow().getGuiScale();
		int unit = unit();
		int x = (tipX(editing) + MapIcons.PIN_WIDTH * unit) / guiScale;
		int y = (tipY(editing) - MapIcons.PIN_HEIGHT * unit) / guiScale;
		nameBox.setX(Mth.clamp(x, 0, Math.max(0, width - NAME_BOX_WIDTH)));
		nameBox.setY(Mth.clamp(y, 0, Math.max(0, height - NAME_BOX_HEIGHT)));
	}

	private void finishEditing(boolean keep) {
		if (keep) {
			MinimapClient.PINS.rename(editing, nameBox.getValue());
		}
		removeWidget(nameBox);
		setFocused(null);
		nameBox = null;
		editing = null;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (nameBox != null) {
			if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
				finishEditing(true);
			} else if (event.key() == InputConstants.KEY_ESCAPE) {
				finishEditing(false);
			} else {
				// 入力中は M などの操作キーも文字として扱う
				super.keyPressed(event);
			}
			return true;
		}
		if (MinimapClient.mapKey.matches(event)) {
			onClose();
			return true;
		}
		if (MinimapClient.placeKey.matches(event)) {
			Window window = minecraft.getWindow();
			int guiScale = window.getGuiScale();
			ignoreTextInput = true;
			place(minecraft.mouseHandler.getScaledXPos(window) * guiScale, minecraft.mouseHandler.getScaledYPos(window) * guiScale);
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		ignoreTextInput = false;
		return super.keyReleased(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		return ignoreTextInput || super.charTyped(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (nameBox != null) {
			if (nameBox.isMouseOver(event.x(), event.y())) {
				return super.mouseClicked(event, doubleClick);
			}
			// 外を押したら確定するだけで、地図の操作にはしない
			finishEditing(true);
			return true;
		}
		if (MinimapClient.mapKey.matchesMouse(event)) {
			onClose();
			return true;
		}
		if (MinimapClient.placeKey.matchesMouse(event)) {
			double guiScale = minecraft.getWindow().getGuiScale();
			place(event.x() * guiScale, event.y() * guiScale);
			return true;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			pressed = true;
			dragDistance = 0;
			removing = minecraft.hasShiftDown();
			return true;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
			double guiScale = minecraft.getWindow().getGuiScale();
			Pins.Pin pin = pinAt(event.x() * guiScale, event.y() * guiScale);
			if (pin != null) {
				startEditing(pin);
			}
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
		// ピンの位置を確かめようとして抜かないよう、抜くのは Shift を押しているときだけにする
		if (removing && dragDistance < DRAG_THRESHOLD) {
			double guiScale = minecraft.getWindow().getGuiScale();
			Pins.Pin pin = pinAt(event.x() * guiScale, event.y() * guiScale);
			if (pin != null) {
				MinimapClient.PINS.remove(pin);
			}
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
