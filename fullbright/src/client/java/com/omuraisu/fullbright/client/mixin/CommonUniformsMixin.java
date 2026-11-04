package com.omuraisu.fullbright.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.omuraisu.fullbright.client.FullbrightClient;

// シェーダーパックはライトマップを使わず、この 2 つの uniform で暗所の明るさを決める。
// どちらも 0〜1 を想定した値なので範囲内で渡す。Iris の内部を見ているので、Iris の更新で
// 見つからなくなっても起動はできるように require = 0 にして、ゲームテストで検知する
@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.CommonUniforms")
abstract class CommonUniformsMixin {
	@ModifyReturnValue(method = "getNightVision", at = @At("RETURN"), require = 0)
	private static float fullbright$nightVision(float value) {
		return FullbrightClient.isEnabled() ? Math.max(value, (float) FullbrightClient.level() / FullbrightClient.MAX_LEVEL) : value;
	}

	// screenBrightness を読むラムダ
	@ModifyReturnValue(method = "lambda$generalCommonUniforms$1", at = @At("RETURN"), require = 0)
	private static double fullbright$screenBrightness(double value) {
		return FullbrightClient.isEnabled() ? 1 : value;
	}
}
