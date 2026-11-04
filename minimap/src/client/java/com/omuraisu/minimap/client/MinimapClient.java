package com.omuraisu.minimap.client;

import java.nio.file.Path;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.LevelResource;

public class MinimapClient implements ClientModInitializer {
	public static final Pins PINS = new Pins();

	public static KeyMapping placeKey;
	public static KeyMapping removeKey;

	private static final MapTerrain TERRAIN = new MapTerrain();

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("minimap", "main"));
		placeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.minimap.place_pin", InputConstants.Type.KEYBOARD, InputConstants.KEY_B, category));
		removeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.minimap.remove_pin", InputConstants.Type.KEYBOARD, InputConstants.KEY_N, category));

		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath("minimap", "map"), MinimapHud::extract);

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> PINS.open(pinFile(client)));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> PINS.close());
		ClientTickEvents.END_CLIENT_TICK.register(MinimapClient::tick);
	}

	private static void tick(Minecraft client) {
		TERRAIN.tick(client);

		LocalPlayer player = client.player;
		if (player == null) {
			return;
		}
		while (placeKey.consumeClick()) {
			PINS.place(Pins.Pin.at(player));
			player.sendOverlayMessage(Component.translatable("message.minimap.pin_placed"));
		}
		while (removeKey.consumeClick()) {
			player.sendOverlayMessage(Component.translatable(PINS.removeNearest(Pins.Pin.at(player))
					? "message.minimap.pin_removed"
					: "message.minimap.no_pins"));
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
