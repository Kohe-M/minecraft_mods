package com.omuraisu.peek.client.mixin;

import java.util.Optional;
import java.util.function.Consumer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.omuraisu.peek.client.ShulkerPreview;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

@Mixin(ItemStack.class)
abstract class ItemStackMixin {
	@Inject(method = "getTooltipImage", at = @At("HEAD"), cancellable = true)
	private void peek$showContents(CallbackInfoReturnable<Optional<TooltipComponent>> cir) {
		ItemStack self = (ItemStack) (Object) this;
		if (ShulkerPreview.shows(self)) {
			cir.setReturnValue(Optional.of(ShulkerPreview.of(self)));
		}
	}

	// アイコンで出すので、バニラの「アイテム名 x個」の行は出さない
	@Inject(method = "addToTooltip(Lnet/minecraft/core/component/DataComponentType;Lnet/minecraft/world/item/Item$TooltipContext;Lnet/minecraft/world/item/component/TooltipDisplay;Ljava/util/function/Consumer;Lnet/minecraft/world/item/TooltipFlag;)V", at = @At("HEAD"), cancellable = true)
	private void peek$hideContentLines(DataComponentType<?> type, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> consumer, TooltipFlag flag, CallbackInfo ci) {
		if (type == DataComponents.CONTAINER && ShulkerPreview.shows((ItemStack) (Object) this)) {
			ci.cancel();
		}
	}
}
