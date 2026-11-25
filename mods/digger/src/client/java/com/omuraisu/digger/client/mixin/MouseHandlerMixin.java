package com.omuraisu.digger.client.mixin;

import org.joml.Vector2i;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.omuraisu.digger.client.DiggerClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.ScrollWheelHandler;

@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	// 大きさと奥行きに使ったホイールは (0, 0) を返してホットバーの切り替えを止める
	@WrapOperation(method = "onScroll", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/ScrollWheelHandler;onMouseScroll(DD)Lorg/joml/Vector2i;"))
	private Vector2i digger$onScroll(ScrollWheelHandler handler, double x, double y, Operation<Vector2i> original) {
		Vector2i steps = original.call(handler, x, y);
		return DiggerClient.scroll(minecraft, steps.y) ? new Vector2i() : steps;
	}
}
