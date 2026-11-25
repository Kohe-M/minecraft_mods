package com.omuraisu.digger.mixin;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.omuraisu.digger.BreakDirectionAccess;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayerGameMode;

@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin implements BreakDirectionAccess {
	@Unique
	private @Nullable Direction digger$breakDirection;

	// バニラの destroyDirection は一瞬で壊れたときに更新されないので、壊し始めの引数を覚えておく
	@Inject(method = "handleBlockBreakAction", at = @At("HEAD"))
	private void digger$rememberBreakDirection(
			BlockPos pos,
			ServerboundPlayerActionPacket.Action action,
			Direction direction,
			int maxY,
			int sequence,
			CallbackInfo info) {
		// バニラのクライアントは ABORT_DESTROY_BLOCK をいつも DOWN で送る。掘り始めの面を上書きしない
		if (action == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK) {
			return;
		}
		this.digger$breakDirection = direction;
	}

	@Override
	public @Nullable Direction digger$getBreakDirection() {
		return this.digger$breakDirection;
	}

	@Override
	public void digger$setBreakDirection(@Nullable Direction direction) {
		this.digger$breakDirection = direction;
	}
}
