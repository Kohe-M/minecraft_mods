package com.omuraisu.minimap;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

// サーバー側は任意。入っていればマップ画面に描画距離の外の地形を送る
public class Minimap implements ModInitializer {
	// Jori の一覧で無効にされている間は false。地図を出さず、遠くの地形の依頼にも応えない
	private static volatile boolean active = true;

	public static boolean isActive() {
		return active;
	}

	public static void setActive(boolean value) {
		active = value;
	}

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.serverboundPlay().register(TerrainRequest.TYPE, TerrainRequest.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TerrainTile.TYPE, TerrainTile.CODEC);
		TerrainServer.register();
	}
}
