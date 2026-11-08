package com.omuraisu.minimap.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;

@Mixin(SimpleRegionStorage.class)
public interface SimpleRegionStorageAccessor {
	@Accessor("worker")
	IOWorker minimap$worker();
}
