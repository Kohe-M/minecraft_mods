package com.omuraisu.fullbright.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

public class FullbrightClient implements ClientModInitializer {
	private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("fullbright.txt");

	// 明るさ設定の倍率。1 が設定画面の最大（100%）
	public static final int MIN_LEVEL = 2;
	// 暗所が真っ白になる最小の倍率
	public static final int MAX_LEVEL = 10;

	public static KeyMapping key;

	// Jori の一覧で無効にされている間は false。キーも効かず、明るさはバニラのまま
	private static volatile boolean active = true;
	private static boolean enabled = true;
	private static int level = MAX_LEVEL;

	private static boolean held;
	private static boolean scrolled;

	@Override
	public void onInitializeClient() {
		load();

		key = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.fullbright.toggle",
				InputConstants.Type.KEYBOARD,
				InputConstants.KEY_Z,
				KeyMapping.Category.register(Identifier.fromNamespaceAndPath("fullbright", "main"))));

		ClientTickEvents.END_CLIENT_TICK.register(FullbrightClient::tick);
	}

	private static void tick(Minecraft client) {
		// 1 tick より短い単押しは isDown に現れないので consumeClick でも拾う
		boolean clicked = false;
		while (key.consumeClick()) {
			clicked = true;
		}

		if (!active || client.gui.screen() != null) {
			held = false;
			scrolled = false;
			return;
		}

		if (key.isDown()) {
			held = true;
			return;
		}

		// ホイールで調整した後に離しても切り替えない
		if ((held || clicked) && !scrolled) {
			enabled = !enabled;
			save();
			showStatus(client);
		}
		held = false;
		scrolled = false;
	}

	public static boolean isActive() {
		return active;
	}

	public static void setActive(boolean value) {
		active = value;
	}

	public static boolean isEnabled() {
		return active && enabled;
	}

	public static int level() {
		return level;
	}

	// キーを押している間のホイールは調整に使い、ホットバーには回さない
	public static boolean scroll(Minecraft client, int steps) {
		if (!active || !key.isDown() || client.gui.screen() != null) {
			return false;
		}
		scrolled = true;
		if (steps != 0) {
			enabled = true;
			level = Mth.clamp(level + steps, MIN_LEVEL, MAX_LEVEL);
			save();
			showStatus(client);
		}
		return true;
	}

	private static void showStatus(Minecraft client) {
		if (client.player != null) {
			client.player.sendOverlayMessage(enabled
					? Component.translatable("message.fullbright.enabled", level * 100)
					: Component.translatable("message.fullbright.disabled"));
		}
	}

	public static void load() {
		enabled = true;
		level = MAX_LEVEL;

		List<String> lines;
		try {
			lines = Files.readAllLines(CONFIG);
		} catch (IOException e) {
			return;
		}
		for (String line : lines) {
			if (line.strip().equals("enabled=false")) {
				enabled = false;
			} else if (line.startsWith("level=")) {
				try {
					level = Mth.clamp(Integer.parseInt(line.substring(6).strip()), MIN_LEVEL, MAX_LEVEL);
				} catch (NumberFormatException ignored) {
				}
			}
		}
	}

	private static void save() {
		try {
			Files.writeString(CONFIG, "enabled=" + enabled + "\nlevel=" + level + "\n");
		} catch (IOException ignored) {
		}
	}
}
