package com.omuraisu.digger.client;

import java.nio.file.Path;
import java.util.Objects;

import com.mojang.blaze3d.platform.InputConstants;
import com.omuraisu.digger.AreaSize;
import com.omuraisu.digger.DigSettings;
import com.omuraisu.digger.Digger;
import com.omuraisu.digger.DiggerConfig;
import com.omuraisu.digger.SettingsPayload;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class DiggerClient implements ClientModInitializer {
	public static final Path CONFIG = DiggerConfig.FILE;

	public static KeyMapping areaKey;
	public static KeyMapping veinKey;

	private static DigSettings settings = DigSettings.DEFAULT;
	// サーバーに最後に伝えた値。Jori で無効にされたら、サーバーにも両方オフと伝える
	private static DigSettings sent;

	// V を押している間。離したときに単押しかどうかを決める
	private static boolean held;
	// V を押しながらホイールを回したら、離しても切り替えない
	private static boolean scrolled;

	@Override
	public void onInitializeClient() {
		load();

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("digger", "main"));
		areaKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.digger.area", InputConstants.Type.KEYBOARD, InputConstants.KEY_V, category));
		veinKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.digger.vein", InputConstants.Type.KEYBOARD, InputConstants.KEY_H, category));

		ClientTickEvents.END_CLIENT_TICK.register(DiggerClient::tick);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> sync());
		// バニラの枠線と同じレンダータイプと経路で出す。Sodium・Iris・シェーダーパック・DH でも同じ扱いになる
		LevelRenderEvents.COLLECT_SUBMITS.register(DigPreview::submit);
	}

	public static DigSettings settings() {
		return settings;
	}

	// config/digger.txt を読み直す。テストからも呼べるように public
	public static void load() {
		settings = DiggerConfig.load(CONFIG);
	}

	private static void tick(Minecraft client) {
		// 1 tick より短い単押しは isDown に現れないので consumeClick でも拾う
		boolean areaClicked = drain(areaKey);
		boolean veinClicked = drain(veinKey);

		if (!Digger.isActive()) {
			// 無効の間に押した分は、戻したときに効かないよう捨てる
			held = false;
			scrolled = false;
			DigPreview.clear();
			syncIfChanged();
			return;
		}

		if (client.gui.screen() != null) {
			held = false;
			scrolled = false;
			DigPreview.clear();
			syncIfChanged();
			return;
		}

		if (veinClicked) {
			toggleVein(client);
		}

		if (areaKey.isDown()) {
			held = true;
		} else {
			// ホイールで調整した後に離しても切り替えない
			if ((held || areaClicked) && !scrolled) {
				toggleArea(client);
			}
			held = false;
			scrolled = false;
		}

		syncIfChanged();
		DigPreview.update(client);
	}

	private static boolean drain(KeyMapping key) {
		boolean clicked = false;
		while (key.consumeClick()) {
			clicked = true;
		}
		return clicked;
	}

	private static void toggleArea(Minecraft client) {
		if (!ClientPlayNetworking.canSend(SettingsPayload.TYPE)) {
			show(client, "message.digger.area.unsupported");
			return;
		}
		settings = settings.withArea(!settings.area());
		DiggerConfig.save(CONFIG, settings);
		syncIfChanged();
		showArea(client);
	}

	private static void toggleVein(Minecraft client) {
		if (!ClientPlayNetworking.canSend(SettingsPayload.TYPE)) {
			show(client, "message.digger.vein.unsupported");
			return;
		}
		settings = settings.withVein(!settings.vein());
		DiggerConfig.save(CONFIG, settings);
		syncIfChanged();
		show(client, settings.vein() ? "message.digger.vein.on" : "message.digger.vein.off");
	}

	// キーを押している間のホイールは大きさと奥行きに使い、ホットバーには回さない
	public static boolean scroll(Minecraft client, int steps) {
		if (!Digger.isActive() || !areaKey.isDown() || client.gui.screen() != null) {
			return false;
		}
		scrolled = true;
		if (!ClientPlayNetworking.canSend(SettingsPayload.TYPE)) {
			show(client, "message.digger.area.unsupported");
			return true;
		}
		if (steps != 0) {
			if (client.hasShiftDown()) {
				settings = settings.withDepth(settings.depth() + steps);
			} else {
				AreaSize size = AreaSize.of(settings.width(), settings.height()).step(steps);
				settings = settings.withSize(size.width(), size.height());
			}
			// ホイールを回したら範囲採掘はオンにする
			settings = settings.withArea(true);
			DiggerConfig.save(CONFIG, settings);
			syncIfChanged();
			showArea(client);
		}
		return true;
	}

	private static boolean wanted() {
		return Digger.isActive();
	}

	private static DigSettings currentWanted() {
		return wanted() ? settings : DigSettings.OFF;
	}

	private static void syncIfChanged() {
		DigSettings want = currentWanted();
		if (!Objects.equals(want, sent)) {
			sync();
		}
	}

	private static void sync() {
		DigSettings want = currentWanted();
		sent = want;
		if (ClientPlayNetworking.canSend(SettingsPayload.TYPE)) {
			ClientPlayNetworking.send(SettingsPayload.of(want));
		}
	}

	private static void showArea(Minecraft client) {
		if (settings.area()) {
			show(client, "message.digger.area", settings.width(), settings.height(), settings.depth());
		} else {
			show(client, "message.digger.area.off");
		}
	}

	private static void show(Minecraft client, String key, Object... args) {
		if (client.player != null) {
			client.player.sendOverlayMessage(Component.translatable(key, args));
		}
	}
}
