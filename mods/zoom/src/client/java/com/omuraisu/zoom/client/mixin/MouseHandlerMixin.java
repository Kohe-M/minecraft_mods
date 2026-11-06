package com.omuraisu.zoom.client.mixin;

import org.joml.Vector2i;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.omuraisu.zoom.client.ZoomClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.ScrollWheelHandler;

@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@Shadow
	private double accumulatedDX;

	@Shadow
	private double accumulatedDY;

	// 拡大した分だけ視点を遅くして、画面上の動きを素の感度に揃える
	@Inject(method = "turnPlayer", at = @At("HEAD"))
	private void zoom$turnPlayer(double movementTime, CallbackInfo ci) {
		float zoom = ZoomClient.zoom();
		accumulatedDX /= zoom;
		accumulatedDY /= zoom;
	}

	// ズーム中のホイールは倍率に使い、(0, 0) を返してホットバーの切り替えを止める
	@WrapOperation(method = "onScroll", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/ScrollWheelHandler;onMouseScroll(DD)Lorg/joml/Vector2i;"))
	private Vector2i zoom$onScroll(ScrollWheelHandler handler, double x, double y, Operation<Vector2i> original) {
		if (ZoomClient.scroll(minecraft, y)) {
			return new Vector2i();
		}
		return original.call(handler, x, y);
	}
}
