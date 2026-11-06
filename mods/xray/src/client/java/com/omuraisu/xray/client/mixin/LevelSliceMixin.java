package com.omuraisu.xray.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.omuraisu.xray.client.XrayClient;

import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

// Sodium はチャンク構築を自前の LevelSlice で行うので、そちらにも同じ差し替えを入れる。
// getBlockState(BlockPos) と getFluidState は getBlockState(III) を経由する
@Pseudo
@Mixin(LevelSlice.class)
public abstract class LevelSliceMixin {
	@ModifyReturnValue(method = "getBlockState(III)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("RETURN"))
	private BlockState xray$hide(BlockState state) {
		return XrayClient.isEnabled() && !XrayClient.isVisible(state) ? Blocks.AIR.defaultBlockState() : state;
	}

	@Inject(method = "getBrightness", at = @At("HEAD"), cancellable = true)
	private void xray$fullBright(LightLayer layer, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
		if (XrayClient.isEnabled()) {
			cir.setReturnValue(15);
		}
	}

	@Inject(method = "getRawBrightness", at = @At("HEAD"), cancellable = true)
	private void xray$fullBrightRaw(BlockPos pos, int darkening, CallbackInfoReturnable<Integer> cir) {
		if (XrayClient.isEnabled()) {
			cir.setReturnValue(15);
		}
	}
}
