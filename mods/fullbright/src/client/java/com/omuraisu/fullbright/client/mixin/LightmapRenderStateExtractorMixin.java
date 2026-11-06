package com.omuraisu.fullbright.client.mixin;

import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.omuraisu.fullbright.client.FullbrightClient;

import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.util.ARGB;

@Mixin(LightmapRenderStateExtractor.class)
abstract class LightmapRenderStateExtractorMixin {
	// オーバーワールドの ambient_light_color と同じ
	@Unique
	private static final Vector3fc MIN_AMBIENT = ARGB.vector3fFromRGB24(0x0A0A0A);

	// options.txt は 0〜1 しか読めないので、設定値は変えずに読み出した値を差し替える
	@ModifyExpressionValue(method = "extract", at = @At(value = "INVOKE", target = "Ljava/lang/Double;floatValue()F", ordinal = 0))
	private float fullbright$gamma(float gamma) {
		return FullbrightClient.isEnabled() ? FullbrightClient.level() : gamma;
	}

	// ライトマップのシェーダーは元の色に比例して明るくするので、
	// ambient_light_color が未指定（黒）の次元では暗所が黒いままになる
	@Inject(method = "extract", at = @At("TAIL"))
	private void fullbright$ambient(LightmapRenderState state, float partialTick, CallbackInfo ci) {
		if (FullbrightClient.isEnabled()) {
			state.ambientColor = state.ambientColor.max(MIN_AMBIENT, new Vector3f());
		}
	}
}
