package com.omuraisu.minimap;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

// サーバー側は任意。入っていればマップ画面に描画距離の外の地形を送り、ピンをサーバーにいる人で共有する
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
		PayloadTypeRegistry.serverboundPlay().register(TerrainView.TYPE, TerrainView.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(TerrainHave.TYPE, TerrainHave.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TerrainTiles.TYPE, TerrainTiles.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TerrainStamps.TYPE, TerrainStamps.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TerrainRemoved.TYPE, TerrainRemoved.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TerrainPreview.TYPE, TerrainPreview.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(MapPlayerPositions.TYPE, MapPlayerPositions.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(PinEdit.TYPE, PinEdit.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PinList.TYPE, PinList.CODEC);
		TerrainServer.register();
		PinServer.register();
	}
}
