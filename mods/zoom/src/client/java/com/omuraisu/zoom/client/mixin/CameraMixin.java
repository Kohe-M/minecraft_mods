package com.omuraisu.zoom.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.omuraisu.zoom.client.ZoomClient;

import net.minecraft.client.Camera;

// 手の描画は calculateHudFov なのでズームしない
@Mixin(Camera.class)
abstract class CameraMixin {
	@Shadow
	private boolean isPanoramicMode;

	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void zoom$calculateFov(float partialTicks, CallbackInfoReturnable<Float> cir) {
		float zoom = ZoomClient.zoom(partialTicks);
		if (zoom != 1 && !isPanoramicMode) {
			cir.setReturnValue(ZoomClient.apply(cir.getReturnValueF(), zoom));
		}
	}
}
