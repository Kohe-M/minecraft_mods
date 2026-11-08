package com.omuraisu.minimap.mixin;

import java.nio.file.Path;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.chunk.storage.RegionFileStorage;

@Mixin(RegionFileStorage.class)
public interface RegionFileStorageAccessor {
	// リージョンファイルを置くフォルダ（次元ごとの region）
	@Accessor("folder")
	Path minimap$folder();
}
