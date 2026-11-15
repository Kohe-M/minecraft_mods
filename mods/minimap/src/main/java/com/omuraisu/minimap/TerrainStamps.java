package com.omuraisu.minimap;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// 色は前に送ったものと同じで、版だけが変わったチャンク（メモリにあったチャンクが、同じ中身のまま保存された、など）。
// クライアントは版だけを書き換える。色（1 チャンク 330 バイトほど）を送り直さずに済む
public record TerrainStamps(Identifier dimension, long[] chunks, int[] stamps) implements CustomPacketPayload {
	public static final int MAX_CHUNKS = 2048;
	public static final Type<TerrainStamps> TYPE = new Type<>(Identifier.fromNamespaceAndPath("minimap", "far_terrain_v5/stamps"));
	public static final StreamCodec<FriendlyByteBuf, TerrainStamps> CODEC = CustomPacketPayload.codec(TerrainStamps::write, TerrainStamps::read);

	private static TerrainStamps read(FriendlyByteBuf buf) {
		Identifier dimension = buf.readIdentifier();
		int count = buf.readVarInt();
		if (count < 0 || count > MAX_CHUNKS) {
			throw new IllegalArgumentException("too many chunks: " + count);
		}
		long[] chunks = new long[count];
		int[] stamps = new int[count];
		for (int i = 0; i < count; i++) {
			chunks[i] = buf.readLong();
			stamps[i] = buf.readInt();
		}
		return new TerrainStamps(dimension, chunks, stamps);
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeIdentifier(dimension);
		buf.writeVarInt(chunks.length);
		for (int i = 0; i < chunks.length; i++) {
			buf.writeLong(chunks[i]);
			buf.writeInt(stamps[i]);
		}
	}

	@Override
	public Type<TerrainStamps> type() {
		return TYPE;
	}
}
