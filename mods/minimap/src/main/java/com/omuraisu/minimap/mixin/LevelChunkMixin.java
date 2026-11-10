package com.omuraisu.minimap.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.omuraisu.minimap.TerrainServer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

// ワールドに読み込まれているチャンクのブロックが変わったことを、遠くの地形に知らせる。
// まだ保存していないチャンクの色の版を進め、地図を見ている人に送り直すため（保存した後は、書いた時刻が版になる）。
// ServerChunkCache.blockChanged は tick されているチャンクでしか呼ばれないので、ブロックを置く大元に入れる
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
	@Shadow
	@Final
	private Level level;

	@Inject(method = "setBlockState", at = @At("RETURN"))
	private void minimap$blockChanged(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> info) {
		if (info.getReturnValue() != null && level instanceof ServerLevel serverLevel) {
			TerrainServer.blockChanged(serverLevel, pos);
		}
	}
}
