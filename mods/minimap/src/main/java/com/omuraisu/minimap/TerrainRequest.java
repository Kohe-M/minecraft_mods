package com.omuraisu.minimap;

import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// 地形がほしいチャンク（ChunkPos.pack）を、先に送ってほしいものから並べる。届くたびに前の依頼を置き換える。
// read はセーブにあれば送り、なければ TerrainMissing で答える。generate は未生成と答えたチャンクで、サーバーに余裕があるときだけ生成する。
// どちらも空なら取り消すだけ
public record TerrainRequest(List<Long> read, List<Long> generate) implements CustomPacketPayload {
	// 1 万チャンク/秒ほどまで頼めるよう、0.25 秒ごとに頼み直す（パケットは 32 KiB 未満に収める）
	public static final int MAX_READ = 2048;
	public static final int MAX_GENERATE = 256;
	public static final Type<TerrainRequest> TYPE = new Type<>(Identifier.fromNamespaceAndPath("minimap", "far_terrain_v2/request"));
	public static final StreamCodec<RegistryFriendlyByteBuf, TerrainRequest> CODEC = StreamCodec.composite(
			ByteBufCodecs.LONG.apply(ByteBufCodecs.list(MAX_READ)), TerrainRequest::read,
			ByteBufCodecs.LONG.apply(ByteBufCodecs.list(MAX_GENERATE)), TerrainRequest::generate,
			TerrainRequest::new);

	public static final TerrainRequest CANCEL = new TerrainRequest(List.of(), List.of());

	@Override
	public Type<TerrainRequest> type() {
		return TYPE;
	}
}
