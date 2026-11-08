package com.omuraisu.minimap;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

// サーバー側は任意。入っていればマップ画面に描画距離の外の地形を送る
public class Minimap implements ModInitializer {
	@Override
	public void onInitialize() {
		PayloadTypeRegistry.serverboundPlay().register(TerrainRequest.TYPE, TerrainRequest.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TerrainTile.TYPE, TerrainTile.CODEC);
		TerrainServer.register();
	}
}
