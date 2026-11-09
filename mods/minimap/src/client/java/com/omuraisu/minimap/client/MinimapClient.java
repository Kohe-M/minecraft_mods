package com.omuraisu.minimap.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;

import com.omuraisu.minimap.Minimap;
import com.omuraisu.minimap.PinList;
import com.omuraisu.minimap.TerrainMissing;
import com.omuraisu.minimap.TerrainServer;
import com.omuraisu.minimap.TerrainTiles;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.LevelResource;

public class MinimapClient implements ClientModInitializer {
	public static final Pins PINS = new Pins();
	public static final FarTerrain FAR_TERRAIN = new FarTerrain();

	public static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("minimap.txt");
	// マップ距離（チャンク）。マップ画面を開いていない間も、この範囲の地形をサーバーに頼んでおく。0 なら開いたときだけ頼む。
	// 既定は、1080p で開いた地図を 1 段引いた（1 ブロック 2 画素）画面が収まる広さ。広げるほど、遊んでいる間に生成してセーブが増える
	public static final int DEFAULT_MAP_DISTANCE = 32;
	private static int mapDistance = DEFAULT_MAP_DISTANCE;

	public static KeyMapping mapKey;
	// マップ画面の中でだけ効く
	public static KeyMapping placeKey;

	@Override
	public void onInitializeClient() {
		load();

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("minimap", "main"));
		mapKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.minimap.open_map", InputConstants.Type.KEYBOARD, InputConstants.KEY_M, category));
		placeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.minimap.place_pin", InputConstants.Type.KEYBOARD, InputConstants.KEY_B, category));

		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath("minimap", "map"), MinimapHud::extract);
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath("minimap", "pin_labels"), PinBeams::extractLabels);
		LevelRenderEvents.COLLECT_SUBMITS.register(PinBeams::submit);

		ClientPlayNetworking.registerGlobalReceiver(TerrainTiles.TYPE, (payload, context) -> FAR_TERRAIN.receive(context.client(), payload));
		ClientPlayNetworking.registerGlobalReceiver(TerrainMissing.TYPE, (payload, context) -> FAR_TERRAIN.missing(payload));
		ClientPlayNetworking.registerGlobalReceiver(PinList.TYPE, (payload, context) -> PINS.receive(payload));
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> FAR_TERRAIN.forget(level.dimension().identifier(), chunk.getPos()));

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			PINS.open(pinFile(client));
			FAR_TERRAIN.clear();
			FAR_TERRAIN.openCache(farTerrainFolder(client));
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

		if (client.player == null || client.level == null) {
			return;
		}
		while (mapKey.consumeClick()) {
			client.gui.setScreen(new MapScreen());
		}
		// 開いている間はマップ画面が頼む
		if (!(client.gui.screen() instanceof MapScreen)) {
			FAR_TERRAIN.tickBackground(client, client.level.dimension().identifier(), client.player.chunkPosition(), mapDistance);
		}
	}

	public static int mapDistance() {
		return mapDistance;
	}

	// config/minimap.txt に 1 行 1 つ「キー=値」。ファイルがなければ既定の値で作り、書ける項目を見せる
	public static void load() {
		mapDistance = DEFAULT_MAP_DISTANCE;
		List<String> lines;
		try {
			lines = Files.readAllLines(CONFIG);
		} catch (NoSuchFileException e) {
			try {
				Files.writeString(CONFIG, "map_distance=" + DEFAULT_MAP_DISTANCE + "\n");
			} catch (IOException ignored) {
			}
			return;
		} catch (IOException e) {
			return;
		}
		for (String line : lines) {
			if (line.startsWith("map_distance=")) {
				try {
					mapDistance = Math.clamp(Integer.parseInt(line.substring("map_distance=".length()).strip()), 0, TerrainServer.RADIUS);
				} catch (NumberFormatException ignored) {
				}
			}
		}
	}

	// 遠くの地形を残す場所。シングルプレイはセーブの中、マルチプレイはサーバーのアドレスごと
	private static Path farTerrainFolder(Minecraft client) {
		if (client.getSingleplayerServer() != null) {
			return client.getSingleplayerServer().getWorldPath(LevelResource.ROOT).resolve("minimap_far");
		}
		return client.getCurrentServer() != null
				? FabricLoader.getInstance().getGameDir().resolve("minimap_far").resolve(Pins.encodeAddress(client.getCurrentServer().ip))
				: null;
	}

	// サーバーに mod がないときにピンを保存するファイル。シングルプレイは内蔵サーバーがセーブの中に保存するので要らない
	private static Path pinFile(Minecraft client) {
		if (client.getSingleplayerServer() != null) {
			return null;
		}
		return client.getCurrentServer() != null ? Pins.serverFile(client.getCurrentServer().ip) : null;
	}
}
