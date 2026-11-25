package com.omuraisu.minimap.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;

import com.omuraisu.minimap.MapPlayerPositions;
import com.omuraisu.minimap.Minimap;
import com.omuraisu.minimap.PinList;
import com.omuraisu.minimap.TerrainRemoved;
import com.omuraisu.minimap.TerrainServer;
import com.omuraisu.minimap.TerrainStamps;
import com.omuraisu.minimap.TerrainTiles;

import net.fabricmc.api.ClientModInitializer;
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
	private static final Logger LOGGER = LogUtils.getLogger();
	// マップ距離（チャンク）。マップ画面を開いていない間も、プレイヤーの周りの一番引いた地図に入る範囲を、プレイヤーからこの距離まで
	// サーバーから受け取っておく。0 なら開いたときだけ受け取る。既定はサーバーが送る距離いっぱいで、範囲はウィンドウの形だけで決まる。
	// 縮めるほど、遊んでいる間に生成するチャンクとセーブの増え方が減る
	public static final int DEFAULT_MAP_DISTANCE = TerrainServer.RADIUS;
	// 前の版の既定。前の版が作ったままのファイル（この値の 1 行だけ）は、新しい既定で作り直す
	private static final String OLD_DEFAULT = "map_distance=32";
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
		LevelRenderEvents.END_MAIN.register(PinBeams::drawAfterFade);

		ClientPlayNetworking.registerGlobalReceiver(TerrainTiles.TYPE, (payload, context) -> FAR_TERRAIN.receive(context.client(), payload));
		ClientPlayNetworking.registerGlobalReceiver(TerrainStamps.TYPE, (payload, context) -> FAR_TERRAIN.stamps(payload));
		ClientPlayNetworking.registerGlobalReceiver(TerrainRemoved.TYPE, (payload, context) -> FAR_TERRAIN.removed(payload));
		ClientPlayNetworking.registerGlobalReceiver(MapPlayerPositions.TYPE, (payload, context) -> {
			if (context.client().gui.screen() instanceof MapScreen map) {
				map.receivePlayerPositions(payload);
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(PinList.TYPE, (payload, context) -> PINS.receive(payload));

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
		// 開いている間はマップ画面が見ている範囲を知らせる
		if (!(client.gui.screen() instanceof MapScreen)) {
			FAR_TERRAIN.tickBackground(client, client.level.dimension().identifier(), client.player.chunkPosition(), mapDistance);
		}
	}

	public static int mapDistance() {
		return mapDistance;
	}

	// config/minimap.txt に 1 行 1 つ「キー=値」。行ごとに読み、キーと値の前後の空白と知らない行は無視する。
	// ファイルがなければ既定の値で作り、書ける項目を見せる。前の版が既定の値で作ったままのファイルも作り直す
	// （ユーザーが書いた値ではないので、新しい既定にする）
	public static void load() {
		mapDistance = DEFAULT_MAP_DISTANCE;
		List<String> lines;
		try {
			lines = Files.readAllLines(CONFIG);
		} catch (NoSuchFileException e) {
			writeDefault();
			return;
		} catch (IOException e) {
			return;
		}
		if (lines.equals(List.of(OLD_DEFAULT))) {
			writeDefault();
			return;
		}
		for (String line : lines) {
			int separator = line.indexOf('=');
			if (separator > 0 && line.substring(0, separator).strip().equals("map_distance")) {
				try {
					mapDistance = Math.clamp(Integer.parseInt(line.substring(separator + 1).strip()), 0, TerrainServer.RADIUS);
				} catch (NumberFormatException ignored) {
				}
			}
		}
	}

	private static void writeDefault() {
		try {
			Files.writeString(CONFIG, "map_distance=" + DEFAULT_MAP_DISTANCE + "\n");
		} catch (IOException failure) {
			// 書けなくても既定の値で動くが、黙って捨てずにログに出す
			LOGGER.warn("Failed to save {}: {}", CONFIG, failure.toString());
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
