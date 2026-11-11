package com.omuraisu.placer.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class PlacerClient implements ClientModInitializer {
	public static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("placer.txt");

	private static final Logger LOGGER = LogUtils.getLogger();

	public static KeyMapping toggle;

	// Jori の一覧で無効にされている間は false。キーも効かず、設置はバニラのまま
	private static volatile boolean active = true;
	private static boolean enabled = true;

	@Override
	public void onInitializeClient() {
		load();

		toggle = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.placer.toggle",
				InputConstants.Type.KEYBOARD,
				InputConstants.KEY_J,
				KeyMapping.Category.register(Identifier.fromNamespaceAndPath("placer", "main"))));

		ClientTickEvents.END_CLIENT_TICK.register(PlacerClient::tick);
	}

	private static void tick(Minecraft client) {
		while (toggle.consumeClick()) {
			if (!active) {
				continue;
			}
			enabled = !enabled;
			save();
			if (client.player != null) {
				client.player.sendOverlayMessage(Component.translatable(enabled ? "message.placer.enabled" : "message.placer.disabled"));
			}
		}
		Placement.tick(client);
	}

	public static boolean isActive() {
		return active;
	}

	public static void setActive(boolean value) {
		active = value;
	}

	public static boolean enabled() {
		return active && enabled;
	}

	// config/placer.txt に 1 行 1 つ「キー=値」。いまの項目は enabled だけ。知らない行は無視するので、項目を足しても前の値を読める。
	// 読めないとき・書いていないときは有効
	public static void load() {
		enabled = true;
		List<String> lines;
		try {
			lines = Files.readAllLines(CONFIG);
		} catch (IOException e) {
			return;
		}
		for (String line : lines) {
			int separator = line.indexOf('=');
			if (separator > 0 && line.substring(0, separator).strip().equals("enabled")) {
				enabled = !line.substring(separator + 1).strip().equals("false");
			}
		}
	}

	// 書けなくても遊ぶのには困らないが、黙って捨てずにログに出す
	private static void save() {
		try {
			Files.writeString(CONFIG, "enabled=" + enabled + "\n");
		} catch (IOException e) {
			LOGGER.warn("Failed to save {}: {}", CONFIG, e.toString());
		}
	}
}
