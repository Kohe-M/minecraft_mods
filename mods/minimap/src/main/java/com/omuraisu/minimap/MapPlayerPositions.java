package com.omuraisu.minimap;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// マップ画面を見ている範囲にいる相手の位置。届くたびに前の一覧を置き換える
public record MapPlayerPositions(Identifier dimension, Map<UUID, Position> positions) implements CustomPacketPayload {

	public record Position(double x, double z) {
		private static final StreamCodec<RegistryFriendlyByteBuf, Position> CODEC = StreamCodec.composite(
				ByteBufCodecs.DOUBLE, Position::x,
				ByteBufCodecs.DOUBLE, Position::z,
				Position::new);
	}

	public static final Type<MapPlayerPositions> TYPE = new Type<>(Identifier.fromNamespaceAndPath("minimap", "map_player_positions"));
	public static final StreamCodec<RegistryFriendlyByteBuf, MapPlayerPositions> CODEC = StreamCodec.composite(
			Identifier.STREAM_CODEC, MapPlayerPositions::dimension,
			ByteBufCodecs.map(HashMap::new, UUIDUtil.STREAM_CODEC, Position.CODEC), MapPlayerPositions::positions,
			MapPlayerPositions::new);

	@Override
	public Type<MapPlayerPositions> type() {
		return TYPE;
	}
}
