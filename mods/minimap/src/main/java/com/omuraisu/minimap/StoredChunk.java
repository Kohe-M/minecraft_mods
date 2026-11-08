package com.omuraisu.minimap;

import com.mojang.serialization.Codec;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;

// セーブから読んだチャンク。ワールドには読み込まず、色を出すのに要るセクションのブロックだけを、初めて見るときに読む。
// SerializableChunkData.parse は全セクション・バイオーム・光まで読むので使わない（読み方はそれに合わせる）
final class StoredChunk implements TerrainColors.Blocks {
	private static final BlockState AIR = Blocks.AIR.defaultBlockState();

	private final Codec<PalettedContainer<BlockState>> codec;
	private final int minSectionY;
	private final int minY;
	private final int maxY;
	private final CompoundTag[] tags;
	private final PalettedContainer<BlockState>[] sections;
	private final boolean[] read;
	private final boolean[] empty;

	@SuppressWarnings("unchecked")
	private StoredChunk(ServerLevel level, ListTag sectionTags) {
		this.codec = level.palettedContainerFactory().blockStatesContainerCodec();
		this.minSectionY = level.getMinSectionY();
		this.minY = level.getMinY();
		this.maxY = level.getMaxY();
		int count = level.getSectionsCount();
		this.tags = new CompoundTag[count];
		this.sections = new PalettedContainer[count];
		this.read = new boolean[count];
		this.empty = new boolean[count];
		for (int i = 0; i < sectionTags.size(); i++) {
			CompoundTag section = sectionTags.getCompoundOrEmpty(i);
			int index = section.getByteOr("Y", (byte) 0) - minSectionY;
			if (index >= 0 && index < count) {
				tags[index] = section.getCompound("block_states").orElse(null);
			}
		}
	}

	// 地物まで置き終えていなければ（生成の途中なら）null
	static StoredChunk read(ServerLevel level, CompoundTag tag) {
		if (!SerializableChunkData.getChunkStatusFromTag(tag).isOrAfter(ChunkStatus.FEATURES)) {
			return null;
		}
		return new StoredChunk(level, tag.getListOrEmpty(SerializableChunkData.SECTIONS_TAG));
	}

	@Override
	public BlockState getBlockState(int x, int y, int z) {
		if (y < minY || y > maxY) {
			return AIR;
		}
		PalettedContainer<BlockState> section = section((y >> 4) - minSectionY);
		return section == null ? AIR : section.get(x & 15, y & 15, z & 15);
	}

	@Override
	public int minY() {
		return minY;
	}

	@Override
	public int maxY() {
		return maxY;
	}

	// 空気だけのセクションはまとめて飛ばす
	@Override
	public int surfaceY(int x, int z) {
		for (int index = sections.length - 1; index >= 0; index--) {
			PalettedContainer<BlockState> section = section(index);
			if (section == null) {
				continue;
			}
			for (int dy = 15; dy >= 0; dy--) {
				if (!section.get(x & 15, dy, z & 15).isAir()) {
					return (index + minSectionY) * 16 + dy;
				}
			}
		}
		return minY - 1;
	}

	// 空気だけなら null
	private PalettedContainer<BlockState> section(int index) {
		if (!read[index]) {
			read[index] = true;
			CompoundTag tag = tags[index];
			if (tag != null) {
				PalettedContainer<BlockState> container = codec.parse(NbtOps.INSTANCE, tag).promotePartial(error -> {
				}).getOrThrow();
				sections[index] = container;
				empty[index] = !container.maybeHas(state -> !state.isAir());
			} else {
				empty[index] = true;
			}
			tags[index] = null;
		}
		return empty[index] ? null : sections[index];
	}
}
