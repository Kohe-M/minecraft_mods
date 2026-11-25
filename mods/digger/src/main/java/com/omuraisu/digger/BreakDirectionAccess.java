package com.omuraisu.digger;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.Direction;

// ServerPlayerGameMode に覚えさせた、最後に壊し始めた面。バニラの destroyDirection は一瞬で壊れたときに
// 更新されないので、handleBlockBreakAction の引数を mixin で覚えてこちらから読む
public interface BreakDirectionAccess {
	@Nullable
	Direction digger$getBreakDirection();

	void digger$setBreakDirection(@Nullable Direction direction);
}
