package com.omuraisu.minimap.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.server.level.ChunkTaskDispatcher;
import net.minecraft.server.level.ChunkTaskPriorityQueue;

@Mixin(ChunkTaskDispatcher.class)
public interface ChunkTaskDispatcherAccessor {
	@Accessor("queue")
	ChunkTaskPriorityQueue minimap$queue();
}
