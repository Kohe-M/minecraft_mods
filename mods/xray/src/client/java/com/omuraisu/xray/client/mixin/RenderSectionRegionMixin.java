package com.omuraisu.xray.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.omuraisu.xray.client.XrayClient;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

// チャンク構築が見るワールドのコピーを差し替える。描画器（Indigo など）に依存せず、
// 隠したブロックは空気として扱われるので、面のカリングや洞窟カリングも自然に外れる
@Mixin(RenderSectionRegion.class)
public abstract class RenderSectionRegionMixin implements BlockAndTintGetter {
	@ModifyReturnValue(method = "getBlockState", at = @At("RETURN"))
	private BlockState xray$hide(BlockState state) {
		return XrayClient.isEnabled() && !XrayClient.isVisible(state) ? Blocks.AIR.defaultBlockState() : state;
	}

	// 地中の鉱石は光が届かず真っ暗なので、透過中は常に最大の明るさにする
	@Override
	public int getBrightness(LightLayer layer, BlockPos pos) {
		return XrayClient.isEnabled() ? 15 : getLightEngine().getLayerListener(layer).getLightValue(pos);
	}
}
