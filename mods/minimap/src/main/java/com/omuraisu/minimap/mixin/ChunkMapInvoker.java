package com.omuraisu.minimap.mixin;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;

@Mixin(ChunkMap.class)
public interface ChunkMapInvoker {
	// チャンクをワールドに読み込まずにセーブから読む。古い版のデータは読み込むときと同じく新しい形に直る
	@Invoker("readChunk")
	CompletableFuture<Optional<CompoundTag>> minimap$readChunk(ChunkPos pos);

	@Invoker("getVisibleChunkIfPresent")
	ChunkHolder minimap$getVisibleChunkIfPresent(long pos);

	// ワールドから外れ、保存し終えるのを待っているチャンク。この間はセーブの中身が古い
	@Accessor("pendingUnloads")
	Long2ObjectLinkedOpenHashMap<ChunkHolder> minimap$pendingUnloads();
}
