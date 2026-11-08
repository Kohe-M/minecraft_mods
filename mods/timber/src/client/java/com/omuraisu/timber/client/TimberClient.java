package com.omuraisu.timber.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.mojang.blaze3d.platform.InputConstants;
import com.omuraisu.timber.Timber;
import com.omuraisu.timber.TogglePayload;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class TimberClient implements ClientModInitializer {
	private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("timber.txt");

	private static boolean enabled = true;
	// サーバーに最後に伝えた値。Jori で無効にされたら、サーバーにも OFF と伝える
	private static boolean sent;

	@Override
	public void onInitializeClient() {
		enabled = load();

		KeyMapping toggle = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.timber.toggle",
				InputConstants.Type.KEYBOARD,
				InputConstants.KEY_G,
				KeyMapping.Category.register(Identifier.fromNamespaceAndPath("timber", "main"))));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (toggle.consumeClick()) {
				if (!Timber.isActive()) {
					continue;
				}
				enabled = !enabled;
				save();
				sync();
				if (client.player != null) {
					client.player.sendOverlayMessage(Component.translatable(enabled ? "message.timber.enabled" : "message.timber.disabled"));
				}
			}
			if (wanted() != sent) {
				sync();
			}
		});

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> sync());
	}

	private static boolean wanted() {
		return enabled && Timber.isActive();
	}

	private static void sync() {
		sent = wanted();
		if (ClientPlayNetworking.canSend(TogglePayload.TYPE)) {
			ClientPlayNetworking.send(new TogglePayload(sent));
		}
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
