package com.omuraisu.minimap;

import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// サーバーのピンすべて。入ったときと、誰かが変えるたびに全員へ送る。
// acknowledged は、受け取る人から届いた PinEdit のうち、この一覧に反映した数（接続してから数える）
public record PinList(List<Pin> pins, int acknowledged) implements CustomPacketPayload {
	public static final int MAX_PINS = 4096;
	public static final Type<PinList> TYPE = new Type<>(Identifier.fromNamespaceAndPath("minimap", "pins_v1/list"));
	public static final StreamCodec<RegistryFriendlyByteBuf, PinList> CODEC = StreamCodec.composite(
			Pin.CODEC.apply(ByteBufCodecs.list(MAX_PINS)), PinList::pins,
			ByteBufCodecs.VAR_INT, PinList::acknowledged,
			PinList::new);

	@Override
	public Type<PinList> type() {
		return TYPE;
	}
}
