package com.omuraisu.minimap.mixin;

import java.util.Set;
import java.util.function.Function;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.MaterialSystem;

// 地表のブロックを決める規則（material rule）を、チャンクを作らずに 1 列ずつ動かす（TerrainPreviewer）。
// MaterialSystem.topMaterial は呼ぶたびに規則を組み立て直すので、組み立てた規則を使い回せるよう中身を直接動かす
@Mixin(MaterialRuleContext.class)
public interface MaterialRuleContextInvoker {
	@Invoker("<init>")
	static MaterialRuleContext minimap$create(MaterialSystem system, RandomState randomState, DensityVolume expectedVolume, DensitySamplerSet densitySamplers,
			Function<BlockPos, Holder<Biome>> biomeGetter, WorldGenerationContext context, Set<Holder<Biome>> possibleBiomes) {
		throw new AssertionError();
	}

	@Invoker("updateXZ")
	void minimap$updateXZ(int blockX, int blockZ, int surfaceGradientX, int surfaceGradientZ);

	@Invoker("updateY")
	void minimap$updateY(int stoneDepthAbove, int stoneDepthBelow, int waterHeight, int blockY);
}
