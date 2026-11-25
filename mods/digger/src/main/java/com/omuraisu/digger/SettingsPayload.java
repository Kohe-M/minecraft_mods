package com.omuraisu.digger;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// クライアントが自分の設定をサーバーへ送る。サーバーは値を DigSettings で範囲に収めて覚える。
// 形を変えたら ID も変える（同じ ID で形が違う相手とは切断される）
public record SettingsPayload(boolean area, int width, int height, int depth, boolean vein) implements CustomPacketPayload {
	public static final Type<SettingsPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("digger", "settings"));
	public static final StreamCodec<RegistryFriendlyByteBuf, SettingsPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.BOOL, SettingsPayload::area,
			ByteBufCodecs.VAR_INT, SettingsPayload::width,
			ByteBufCodecs.VAR_INT, SettingsPayload::height,
			ByteBufCodecs.VAR_INT, SettingsPayload::depth,
			ByteBufCodecs.BOOL, SettingsPayload::vein,
			SettingsPayload::new);

	public static SettingsPayload of(DigSettings settings) {
		return new SettingsPayload(settings.area(), settings.width(), settings.height(), settings.depth(), settings.vein());
	}

	public DigSettings settings() {
		return new DigSettings(area, width, height, depth, vein);
	}

	@Override
	public Type<SettingsPayload> type() {
		return TYPE;
	}
}
