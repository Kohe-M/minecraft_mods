package com.omuraisu.minimap.client;

import java.nio.file.Path;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.LevelResource;

public class MinimapClient implements ClientModInitializer {
	public static final Pins PINS = new Pins();

	public static KeyMapping mapKey;

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("minimap", "main"));
		mapKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.minimap.open_map", InputConstants.Type.KEYBOARD, InputConstants.KEY_M, category));

		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath("minimap", "map"), MinimapHud::extract);
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath("minimap", "pin_labels"), PinBeams::extractLabels);
		LevelRenderEvents.COLLECT_SUBMITS.register(PinBeams::submit);

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> PINS.open(pinFile(client)));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> PINS.close());
		ClientTickEvents.END_CLIENT_TICK.register(MinimapClient::tick);
	}

	private static void tick(Minecraft client) {
		MinimapHud.TERRAIN.tick(client);

		if (client.player == null) {
			return;
		}
		while (mapKey.consumeClick()) {
			client.gui.setScreen(new MapScreen());
		}
	}

	// シングルプレイはセーブの中に置き、ワールドと一緒に移動・削除されるようにする
	private static Path pinFile(Minecraft client) {
		if (client.getSingleplayerServer() != null) {
			return client.getSingleplayerServer().getWorldPath(LevelResource.ROOT).resolve("minimap_pins.txt");
		}
		return client.getCurrentServer() != null ? Pins.serverFile(client.getCurrentServer().ip) : null;
	}
}
