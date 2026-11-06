package com.omuraisu.refill.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.omuraisu.refill.client.RefillClient;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;

// 捨てる（Q）と持ち替え（F）で手が空になっても、補充しない
@Mixin(ClientCommonPacketListenerImpl.class)
abstract class ClientCommonPacketListenerImplMixin {
	@Inject(method = "send", at = @At("HEAD"))
	private void refill$send(Packet<?> packet, CallbackInfo ci) {
		if (packet instanceof ServerboundPlayerActionPacket action) {
			switch (action.getAction()) {
				case DROP_ITEM, DROP_ALL_ITEMS, SWAP_ITEM_WITH_OFFHAND -> RefillClient.forgetUses();
				default -> {
				}
			}
		}
	}
}
