package com.omuraisu.xray.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.omuraisu.xray.client.XrayClient;

import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

// チェストはチャンクのメッシュではなくブロックエンティティとして描かれる。
// バニラも Sodium もここを通るので、両方の描画にこれだけで効く
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {
	// ふつうは 64 ブロックまでしか描かないので、鉱石と同じく描画距離いっぱいまで描く
	@WrapOperation(method = "tryExtractRenderState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;shouldRender(Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/phys/Vec3;)Z"))
	private boolean xray$renderFar(BlockEntityRenderer<BlockEntity, ?> renderer, BlockEntity blockEntity, Vec3 cameraPos, Operation<Boolean> original) {
		return isShown(blockEntity) || original.call(renderer, blockEntity, cameraPos);
	}

	// 明るさは本物のワールドから読まれ、地中では真っ暗なので、鉱石と同じく最大にする
	@Inject(method = "tryExtractRenderState", at = @At("RETURN"))
	private void xray$fullBright(BlockEntity blockEntity, float partialTicks, ModelFeatureRenderer.CrumblingOverlay breakProgress,
			boolean offScreen, CallbackInfoReturnable<BlockEntityRenderState> cir) {
		BlockEntityRenderState state = cir.getReturnValue();
		if (state != null && isShown(blockEntity)) {
			state.lightCoords = LightCoordsUtil.FULL_BRIGHT;
		}
	}

	private static boolean isShown(BlockEntity blockEntity) {
		return XrayClient.isEnabled() && XrayClient.isVisible(blockEntity.getBlockState());
	}
}
