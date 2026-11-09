package com.omuraisu.minimap;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// クライアントがピンを刺した・名前を変えた・抜いた。ピンは次元と座標で見分ける
public record PinEdit(Action action, Pin pin) implements CustomPacketPayload {
	public enum Action {
		PLACE, RENAME, REMOVE
	}

	public static final Type<PinEdit> TYPE = new Type<>(Identifier.fromNamespaceAndPath("minimap", "pins_v1/edit"));
	public static final StreamCodec<RegistryFriendlyByteBuf, PinEdit> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT.map(id -> Action.values()[Math.floorMod(id, Action.values().length)], Action::ordinal), PinEdit::action,
			Pin.CODEC, PinEdit::pin,
			PinEdit::new);

	@Override
	public Type<PinEdit> type() {
		return TYPE;
	}
}
