package com.omuraisu.minimap;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// クライアントが色を持っているのに、サーバーにはもうないチャンク（ワールドをバックアップから戻した、など）。クライアントは色を消す
public record TerrainRemoved(Identifier dimension, long[] chunks) implements CustomPacketPayload {
	public static final int MAX_CHUNKS = 2048;
	public static final Type<TerrainRemoved> TYPE = new Type<>(Identifier.fromNamespaceAndPath("minimap", "far_terrain_v5/removed"));
	public static final StreamCodec<FriendlyByteBuf, TerrainRemoved> CODEC = CustomPacketPayload.codec(TerrainRemoved::write, TerrainRemoved::read);

	private static TerrainRemoved read(FriendlyByteBuf buf) {
		Identifier dimension = buf.readIdentifier();
		int count = buf.readVarInt();
		if (count < 0 || count > MAX_CHUNKS) {
			throw new IllegalArgumentException("too many chunks: " + count);
		}
		long[] chunks = new long[count];
		for (int i = 0; i < count; i++) {
			chunks[i] = buf.readLong();
		}
		return new TerrainRemoved(dimension, chunks);
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeIdentifier(dimension);
		buf.writeVarInt(chunks.length);
		for (long chunk : chunks) {
			buf.writeLong(chunk);
		}
	}

	@Override
	public Type<TerrainRemoved> type() {
		return TYPE;
	}
}
