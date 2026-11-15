package com.omuraisu.minimap;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// クライアントが地形をほしい範囲（チャンクの座標、両端を含む）。届くたびに前の範囲を置き換える。
// 何を送るかはサーバーが決める: TerrainHave で知らされた手持ちの版と今の版を比べ、違うチャンクだけを center に近い順に送り、
// 未生成のチャンクは生成してから送る。
// background はマップ画面を開いていない間の範囲で、開いている人の後に回す。min > max なら取り消し
public record TerrainView(Identifier dimension, int minX, int minZ, int maxX, int maxZ, int centerX, int centerZ, boolean background)
		implements CustomPacketPayload {
	public static final Type<TerrainView> TYPE = new Type<>(Identifier.fromNamespaceAndPath("minimap", "far_terrain_v5/view"));
	public static final StreamCodec<FriendlyByteBuf, TerrainView> CODEC = CustomPacketPayload.codec(TerrainView::write, TerrainView::read);

	public static TerrainView none(Identifier dimension) {
		return new TerrainView(dimension, 0, 0, -1, -1, 0, 0, false);
	}

	public boolean isEmpty() {
		return minX > maxX || minZ > maxZ;
	}

	private static TerrainView read(FriendlyByteBuf buf) {
		return new TerrainView(buf.readIdentifier(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readBoolean());
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeIdentifier(dimension);
		buf.writeInt(minX);
		buf.writeInt(minZ);
		buf.writeInt(maxX);
		buf.writeInt(maxZ);
		buf.writeInt(centerX);
		buf.writeInt(centerZ);
		buf.writeBoolean(background);
	}

	@Override
	public Type<TerrainView> type() {
		return TYPE;
	}
}
