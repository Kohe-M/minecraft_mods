package com.omuraisu.xray.client;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

// ALL 以外はホイールの外周に、この順で上から時計回りに並ぶ
public enum XrayMode {
	ALL("all", Items.DIAMOND_ORE, ConventionalBlockTags.ORES),
	DIAMOND("diamond", Items.DIAMOND_ORE, ConventionalBlockTags.DIAMOND_ORES, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE),
	EMERALD("emerald", Items.EMERALD_ORE, ConventionalBlockTags.EMERALD_ORES, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE),
	GOLD("gold", Items.GOLD_ORE, ConventionalBlockTags.GOLD_ORES, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE),
	IRON("iron", Items.IRON_ORE, ConventionalBlockTags.IRON_ORES, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE),
	COPPER("copper", Items.COPPER_ORE, ConventionalBlockTags.COPPER_ORES, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE),
	COAL("coal", Items.COAL_ORE, ConventionalBlockTags.COAL_ORES, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE),
	REDSTONE("redstone", Items.REDSTONE_ORE, ConventionalBlockTags.REDSTONE_ORES, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE),
	LAPIS("lapis", Items.LAPIS_ORE, ConventionalBlockTags.LAPIS_ORES, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE),
	QUARTZ("quartz", Items.NETHER_QUARTZ_ORE, ConventionalBlockTags.QUARTZ_ORES, Blocks.NETHER_QUARTZ_ORE),
	DEBRIS("debris", Items.ANCIENT_DEBRIS, ConventionalBlockTags.NETHERITE_SCRAP_ORES, Blocks.ANCIENT_DEBRIS);

	public static final List<XrayMode> RING = List.of(values()).subList(1, values().length);

	static {
		// バニラサーバーは c:ores を送ってこないので、ALL もバニラの鉱石を直接持つ
		for (XrayMode mode : RING) {
			ALL.blocks.addAll(mode.blocks);
		}
	}

	public final String id;
	public final Item icon;
	private final TagKey<Block> tag;
	private final Set<Block> blocks;

	XrayMode(String id, Item icon, TagKey<Block> tag, Block... blocks) {
		this.id = id;
		this.icon = icon;
		this.tag = tag;
		this.blocks = new HashSet<>(List.of(blocks));
	}

	public boolean matches(BlockState state) {
		return blocks.contains(state.getBlock()) || state.is(tag);
	}

	public Component displayName() {
		return Component.translatable("xray.mode." + id);
	}

	public static XrayMode byId(String id) {
		for (XrayMode mode : values()) {
			if (mode.id.equals(id)) {
				return mode;
			}
		}
		return ALL;
	}
}
