package com.omuraisu.minimap;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// クライアントがリージョンの各チャンクについて持っている色の版（TerrainTile.stamp。0 は持っていない）。
// サーバーはこれを覚え、今の版と違うチャンクだけを送る。知らされていないリージョンは送らない。
// クライアントはリージョンを覚え始めたら（ファイルから読み終えたら）一度送り、捨てたら stamps を null にして送る。
// 前と違う次元のものが届いたら、サーバーは前の次元の分をすべて忘れる（クライアントもそのとき全部知らせ直す）
public record TerrainHave(Identifier dimension, int regionX, int regionZ, int[] stamps) implements CustomPacketPayload {
	public static final Type<TerrainHave> TYPE = new Type<>(Identifier.fromNamespaceAndPath("minimap", "far_terrain_v5/have"));
	public static final StreamCodec<FriendlyByteBuf, TerrainHave> CODEC = CustomPacketPayload.codec(TerrainHave::write, TerrainHave::read);

	public boolean forgotten() {
		return stamps == null;
	}

	// 持っているチャンクだけを、番号と版のひとつ前との差で並べる（近くのチャンクは近い時刻に保存されているので小さくなる）
	private static TerrainHave read(FriendlyByteBuf buf) {
		Identifier dimension = buf.readIdentifier();
		int regionX = buf.readVarInt();
		int regionZ = buf.readVarInt();
		if (!buf.readBoolean()) {
			return new TerrainHave(dimension, regionX, regionZ, null);
		}
		int count = buf.readVarInt();
		if (count < 0 || count > TerrainRegions.COUNT) {
			throw new IllegalArgumentException("too many chunks: " + count);
		}
		int[] stamps = new int[TerrainRegions.COUNT];
		int index = -1;
		int stamp = 0;
		for (int i = 0; i < count; i++) {
			index += buf.readVarInt() + 1;
			if (index < 0 || index >= stamps.length) {
				throw new IllegalArgumentException("chunk out of the region: " + index);
			}
			int delta = buf.readVarInt();
			stamp += delta >>> 1 ^ -(delta & 1);
			stamps[index] = stamp;
		}
		return new TerrainHave(dimension, regionX, regionZ, stamps);
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeIdentifier(dimension);
		buf.writeVarInt(regionX);
		buf.writeVarInt(regionZ);
		buf.writeBoolean(stamps != null);
		if (stamps == null) {
			return;
		}
		int count = 0;
		for (int stamp : stamps) {
			if (stamp != 0) {
				count++;
			}
		}
		buf.writeVarInt(count);
		int previousIndex = -1;
		int previous = 0;
		for (int index = 0; index < stamps.length; index++) {
			if (stamps[index] != 0) {
				buf.writeVarInt(index - previousIndex - 1);
				int delta = stamps[index] - previous;
				buf.writeVarInt(delta << 1 ^ delta >> 31);
				previousIndex = index;
				previous = stamps[index];
			}
		}
	}

	@Override
	public Type<TerrainHave> type() {
		return TYPE;
	}
}
