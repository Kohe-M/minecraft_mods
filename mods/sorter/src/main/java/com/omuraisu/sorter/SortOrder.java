package com.omuraisu.sorter;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;
import net.minecraft.world.item.consume_effects.ConsumeEffect;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.Equippable;

// 並び順の仕様は AGENTS.md の「sorter の並び順」を参照
public final class SortOrder {
	// 宣言順がそのまま並び順。band が同じグループは関連が深く、同じ行に並んでもよい
	public enum Group {
		TOOLS(0), WEAPONS(0), ARMOR(0),
		FOOD(1), POTIONS(1), UTILITY(1),
		BOOKS(2), MATERIALS(2),
		FUNCTIONAL(3), REDSTONE(3),
		BUILDING(4), COLORED(4), NATURAL(4),
		SPAWN_EGGS(5);

		public final int band;

		Group(int band) {
			this.band = band;
		}
	}

	// クリエイティブタブの並びをゲーム内の「見慣れた順」として使う。op_blocks は権限で中身が変わるので触らない
	private static final Map<String, Group> VANILLA_TABS = Map.of(
			"functional_blocks", Group.FUNCTIONAL,
			"redstone_blocks", Group.REDSTONE,
			"tools_and_utilities", Group.UTILITY,
			"combat", Group.WEAPONS,
			"food_and_drinks", Group.POTIONS,
			"ingredients", Group.MATERIALS,
			"building_blocks", Group.BUILDING,
			"colored_blocks", Group.COLORED,
			"natural_blocks", Group.NATURAL,
			"spawn_eggs", Group.SPAWN_EGGS);
	// 複数のタブに載るアイテムは先のタブの扱いになる。ベッドは機能ブロック、白い羊毛は（レッドストーンではなく）色付きブロック
	private static final List<String> TAB_PRIORITY = List.of(
			"functional_blocks", "building_blocks", "colored_blocks", "natural_blocks", "redstone_blocks",
			"tools_and_utilities", "combat", "food_and_drinks", "ingredients", "spawn_eggs");

	private static SortOrder current;
	private static WeakReference<MinecraftServer> currentServer = new WeakReference<>(null);

	private final Map<Item, Integer> ranks = new IdentityHashMap<>();
	private final Map<Item, Group> tabGroups = new IdentityHashMap<>();
	private final Map<ResourceKey<EquipmentAsset>, Integer> armorSets = new HashMap<>();
	private final Comparator<ItemStack> comparator = Comparator
			.comparing(this::group)
			.thenComparingInt(this::section)
			.thenComparingInt(this::place)
			.thenComparing(SortOrder::bookKey)
			.thenComparing(Comparator.comparingInt(SortOrder::enchantmentLevels).reversed())
			.thenComparing(Comparator.comparingInt(SortOrder::durability).reversed())
			.thenComparing(stack -> stack.getHoverName().getString())
			.thenComparing(SortOrder::componentKey)
			.thenComparing(Comparator.comparingInt(ItemStack::getCount).reversed());

	public static synchronized SortOrder of(MinecraftServer server) {
		if (currentServer.get() != server) {
			current = new SortOrder(server);
			currentServer = new WeakReference<>(server);
		}
		return current;
	}

	private SortOrder(MinecraftServer server) {
		CreativeModeTab.ItemDisplayParameters parameters = new CreativeModeTab.ItemDisplayParameters(
				server.getWorldData().enabledFeatures(), false, server.registryAccess());
		List<CreativeModeTab> tabs = new ArrayList<>(CreativeModeTabs.allTabs());
		// バニラのタブ→mod のタブの順に番号を振る
		tabs.sort(Comparator.comparingInt(tab -> {
			int priority = TAB_PRIORITY.indexOf(tabPath(tab));
			return priority < 0 ? TAB_PRIORITY.size() : priority;
		}));
		for (CreativeModeTab tab : tabs) {
			if (tab.getType() != CreativeModeTab.Type.CATEGORY || isOperatorTab(tab)) {
				continue;
			}
			// 表示リストの参照を差し替えるだけで、中身はクライアントが作るものと同じ
			tab.buildContents(parameters);
			Group group = VANILLA_TABS.get(tabPath(tab));
			for (ItemStack stack : tab.getDisplayItems()) {
				Item item = stack.getItem();
				if (ranks.putIfAbsent(item, ranks.size()) == null && group != null) {
					tabGroups.put(item, group);
				}
				armorAsset(stack).ifPresent(asset -> armorSets.merge(asset, ranks.get(item), Math::max));
			}
		}
	}

	public Comparator<ItemStack> comparator() {
		return comparator;
	}

	public Group group(ItemStack stack) {
		if (stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.AXES) || stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.HOES)) {
			return Group.TOOLS;
		}
		if (isArmor(stack)) {
			return Group.ARMOR;
		}
		if (isHarmfulFood(stack)) {
			return Group.MATERIALS;
		}
		if (stack.has(DataComponents.FOOD)) {
			return Group.FOOD;
		}
		if (stack.is(ItemTags.ARROWS)) {
			return Group.WEAPONS;
		}
		if (stack.has(DataComponents.POTION_CONTENTS)) {
			return Group.POTIONS;
		}
		if (isBook(stack)) {
			return Group.BOOKS;
		}
		Group tab = tabGroups.get(stack.getItem());
		if (tab == Group.UTILITY && stack.isDamageableItem()) {
			return Group.TOOLS;
		}
		if (isTorch(stack)) {
			return Group.UTILITY;
		}
		if (tab != null) {
			return tab;
		}
		return stack.getItem() instanceof BlockItem ? Group.BUILDING : Group.MATERIALS;
	}

	// グループ内の大きな区切り
	private int section(ItemStack stack) {
		return switch (group(stack)) {
			case TOOLS -> toolType(stack);
			case WEAPONS -> weaponType(stack);
			// 新しい素材のセットほど前。セット内は place で頭から足へ
			case ARMOR -> armorAsset(stack).map(asset -> -armorSets.getOrDefault(asset, 0)).orElse(0);
			case FOOD -> -foodValue(stack);
			case UTILITY -> isTorch(stack) ? 0 : 1;
			case MATERIALS -> isHarmfulFood(stack) ? 1 : 0;
			default -> 0;
		};
	}

	// 区切りの中での位置
	private int place(ItemStack stack) {
		Group group = group(stack);
		if (group == Group.ARMOR) {
			return armorSlot(stack);
		}
		// 道具と近接武器はいちばん良い素材を前に
		boolean bestFirst = group == Group.TOOLS || group == Group.WEAPONS && weaponType(stack) < 2;
		return bestFirst ? -rank(stack) : rank(stack);
	}

	public int rank(ItemStack stack) {
		Integer rank = ranks.get(stack.getItem());
		return rank != null ? rank : ranks.size() + BuiltInRegistries.ITEM.getId(stack.getItem());
	}

	// クリエイティブタブの分類そのもの（評価用）
	public Optional<Group> tabGroup(ItemStack stack) {
		return Optional.ofNullable(tabGroups.get(stack.getItem()));
	}

	private static int toolType(ItemStack stack) {
		if (stack.is(ItemTags.PICKAXES)) {
			return 0;
		}
		if (stack.is(ItemTags.AXES)) {
			return 1;
		}
		if (stack.is(ItemTags.SHOVELS)) {
			return 2;
		}
		return stack.is(ItemTags.HOES) ? 3 : 4;
	}

	private static int weaponType(ItemStack stack) {
		if (stack.is(ItemTags.SWORDS)) {
			return 0;
		}
		if (stack.is(ItemTags.SPEARS)) {
			return 1;
		}
		if (stack.is(ItemTags.MACE_ENCHANTABLE) || stack.is(ItemTags.TRIDENT_ENCHANTABLE)) {
			return 2;
		}
		if (stack.is(ItemTags.BOW_ENCHANTABLE) || stack.is(ItemTags.CROSSBOW_ENCHANTABLE)) {
			return 3;
		}
		if (stack.is(ItemTags.ARROWS)) {
			return 4;
		}
		return stack.has(DataComponents.BLOCKS_ATTACKS) ? 5 : 6;
	}

	private static boolean isArmor(ItemStack stack) {
		if (stack.is(ItemTags.HEAD_ARMOR) || stack.is(ItemTags.CHEST_ARMOR) || stack.is(ItemTags.LEG_ARMOR) || stack.is(ItemTags.FOOT_ARMOR)) {
			return true;
		}
		// カボチャや頭、カーペットも装備できるが、防具としては扱わない
		Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
		return equippable != null && equippable.slot().isArmor() && !(stack.getItem() instanceof BlockItem);
	}

	private static Optional<ResourceKey<EquipmentAsset>> armorAsset(ItemStack stack) {
		Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
		return equippable != null && isArmor(stack) ? equippable.assetId() : Optional.empty();
	}

	private static int armorSlot(ItemStack stack) {
		Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
		if (equippable == null) {
			return EquipmentSlot.values().length;
		}
		return switch (equippable.slot()) {
			case HEAD -> 0;
			case CHEST -> 1;
			case LEGS -> 2;
			case FEET -> 3;
			default -> 4;
		};
	}

	// 満腹度と隠し満腹度の合計。高いほど前
	private static int foodValue(ItemStack stack) {
		FoodProperties food = stack.get(DataComponents.FOOD);
		return food == null ? 0 : Math.round((food.nutrition() + food.saturation()) * 100);
	}

	// 腐った肉のように、食べるより素材として持っているもの。たまに空腹になる程度の生の鶏肉は食料のまま
	private static boolean isHarmfulFood(ItemStack stack) {
		Consumable consumable = stack.get(DataComponents.CONSUMABLE);
		if (consumable == null || !stack.has(DataComponents.FOOD)) {
			return false;
		}
		for (ConsumeEffect effect : consumable.onConsumeEffects()) {
			if (effect instanceof ApplyStatusEffectsConsumeEffect status && status.probability() >= 0.5F
					&& status.effects().stream().anyMatch(instance -> instance.getEffect().value().getCategory() == MobEffectCategory.HARMFUL)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isTorch(ItemStack stack) {
		return stack.is(Items.TORCH) || stack.is(Items.SOUL_TORCH) || stack.is(Items.COPPER_TORCH);
	}

	private static boolean isBook(ItemStack stack) {
		ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
		return stored != null && !stored.isEmpty();
	}

	// エンチャントの本は名前がどれも同じなので、エンチャント名→高いレベルの順に並べる
	private static String bookKey(ItemStack stack) {
		ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
		if (stored == null) {
			return "";
		}
		return stored.entrySet().stream().map(SortOrder::enchantmentKey).sorted().collect(Collectors.joining(","));
	}

	private static String enchantmentKey(Object2IntMap.Entry<Holder<Enchantment>> entry) {
		String id = entry.getKey().unwrapKey().map(key -> key.identifier().toString()).orElse("");
		return id + "#" + String.format("%03d", 255 - entry.getIntValue());
	}

	private static int enchantmentLevels(ItemStack stack) {
		return stack.getEnchantments().entrySet().stream().mapToInt(Object2IntMap.Entry::getIntValue).sum();
	}

	private static int durability(ItemStack stack) {
		return stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : 0;
	}

	// ここまで同じならコンポーネントの違いだけ。入れた順に左右されないよう文字列にして比べる
	private static String componentKey(ItemStack stack) {
		return stack.getComponentsPatch().split().added().stream()
				.map(Object::toString)
				.sorted()
				.collect(Collectors.joining(","));
	}

	private static String tabPath(CreativeModeTab tab) {
		Identifier id = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
		return id != null && id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE) ? id.getPath() : "";
	}

	private static boolean isOperatorTab(CreativeModeTab tab) {
		return tabPath(tab).equals("op_blocks");
	}
}
