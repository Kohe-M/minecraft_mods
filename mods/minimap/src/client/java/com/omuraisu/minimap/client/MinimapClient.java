package com.omuraisu.minimap.client;

import java.nio.file.Path;

import com.mojang.blaze3d.platform.InputConstants;

import com.omuraisu.minimap.Minimap;
import com.omuraisu.minimap.TerrainMissing;
import com.omuraisu.minimap.TerrainTile;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.LevelResource;

public class MinimapClient implements ClientModInitializer {
	public static final Pins PINS = new Pins();
	public static final FarTerrain FAR_TERRAIN = new FarTerrain();

	public static KeyMapping mapKey;
	// マップ画面の中でだけ効く
	public static KeyMapping placeKey;

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("minimap", "main"));
		mapKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.minimap.open_map", InputConstants.Type.KEYBOARD, InputConstants.KEY_M, category));
		placeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.minimap.place_pin", InputConstants.Type.KEYBOARD, InputConstants.KEY_B, category));

		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath("minimap", "map"), MinimapHud::extract);
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath("minimap", "pin_labels"), PinBeams::extractLabels);
		LevelRenderEvents.COLLECT_SUBMITS.register(PinBeams::submit);

		ClientPlayNetworking.registerGlobalReceiver(TerrainTile.TYPE, (payload, context) -> FAR_TERRAIN.receive(context.client(), payload));
		ClientPlayNetworking.registerGlobalReceiver(TerrainMissing.TYPE, (payload, context) -> FAR_TERRAIN.missing(payload));
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> FAR_TERRAIN.forget(level.dimension().identifier(), chunk.getPos()));

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			PINS.open(pinFile(client));
			FAR_TERRAIN.clear();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			PINS.close();
			FAR_TERRAIN.clear();
		});
		ClientTickEvents.END_CLIENT_TICK.register(MinimapClient::tick);
	}

	private static void tick(Minecraft client) {
		if (!Minimap.isActive()) {
			while (mapKey.consumeClick()) {
				// 無効の間に押した分は、戻したときに開かないよう捨てる
			}
			return;
		}
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
