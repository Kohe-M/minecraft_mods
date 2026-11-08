package com.omuraisu.xray.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.omuraisu.xray.client.XrayClient;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;

// エンティティを見せるモードの間、エンティティを明るく遠くまで描く。
// バニラも Sodium もエンティティはここを通るので、両方の描画にこれだけで効く
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
	// 明るさは本物のワールドから読まれ、洞窟や地中のモブは真っ暗なので、チェストと同じく最大にする
	@Inject(method = "getPackedLightCoords", at = @At("HEAD"), cancellable = true)
	private void xray$fullBright(Entity entity, float partialTicks, CallbackInfoReturnable<Integer> cir) {
		if (XrayClient.showsEntities()) {
			cir.setReturnValue(LightCoordsUtil.FULL_BRIGHT);
		}
	}

	// ふつうは大きさに比例した距離（鶏で 32 ブロック）までしか描かないので、クライアントが知っているものはすべて描く。
	// 視錐台の判定はそのまま残る
	@WrapOperation(method = "shouldRender", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;shouldRender(DDD)Z"))
	private boolean xray$renderFar(Entity entity, double camX, double camY, double camZ, Operation<Boolean> original) {
		return XrayClient.showsEntities() || original.call(entity, camX, camY, camZ);
	}
}
