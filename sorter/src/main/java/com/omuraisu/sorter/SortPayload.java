package com.omuraisu.sorter;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// slot はソートする範囲に含まれる任意のスロットの番号
public record SortPayload(int containerId, int slot) implements CustomPacketPayload {
	public static final Type<SortPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("sorter", "sort"));
	public static final StreamCodec<RegistryFriendlyByteBuf, SortPayload> CODEC =
			StreamCodec.composite(ByteBufCodecs.VAR_INT, SortPayload::containerId, ByteBufCodecs.VAR_INT, SortPayload::slot, SortPayload::new);

	@Override
	public Type<SortPayload> type() {
		return TYPE;
	}
}
