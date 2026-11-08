package com.omuraisu.placer.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.mojang.blaze3d.platform.InputConstants;

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

	public static KeyMapping toggle;

	// Jori の一覧で無効にされている間は false。キーも効かず、設置はバニラのまま
	private static volatile boolean active = true;
	private static boolean enabled = true;

	@Override
	public void onInitializeClient() {
		enabled = load();

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

	private static boolean load() {
		try {
			return !Files.readString(CONFIG).strip().equals("enabled=false");
		} catch (IOException e) {
			return true;
		}
	}

	private static void save() {
		try {
			Files.writeString(CONFIG, "enabled=" + enabled + "\n");
		} catch (IOException ignored) {
		}
	}
}
