package com.omuraisu.minimap;

import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// 地形がほしいチャンク（ChunkPos.pack）を、先に送ってほしいものから並べる。届くたびに前の依頼を置き換える。
// read はセーブにあれば送り、なければ TerrainMissing で答える。generate は未生成と答えたチャンクで、サーバーに余裕があるときだけ生成する。
// background はマップ画面を開いていない間の依頼（マップ距離）で、開いている人の依頼の後に回す。どちらのリストも空なら取り消すだけ
public record TerrainRequest(List<Long> read, List<Long> generate, boolean background) implements CustomPacketPayload {
	// 1 万チャンク/秒ほどまで頼めるよう、0.25 秒ごとに頼み直す（パケットは 32 KiB 未満に収める）
	public static final int MAX_READ = 2048;
	public static final int MAX_GENERATE = 256;
	public static final Type<TerrainRequest> TYPE = new Type<>(Identifier.fromNamespaceAndPath("minimap", "far_terrain_v3/request"));
	public static final StreamCodec<RegistryFriendlyByteBuf, TerrainRequest> CODEC = StreamCodec.composite(
			ByteBufCodecs.LONG.apply(ByteBufCodecs.list(MAX_READ)), TerrainRequest::read,
			ByteBufCodecs.LONG.apply(ByteBufCodecs.list(MAX_GENERATE)), TerrainRequest::generate,
			ByteBufCodecs.BOOL, TerrainRequest::background,
			TerrainRequest::new);

	public static final TerrainRequest CANCEL = new TerrainRequest(List.of(), List.of(), false);

	@Override
	public Type<TerrainRequest> type() {
		return TYPE;
	}
}
