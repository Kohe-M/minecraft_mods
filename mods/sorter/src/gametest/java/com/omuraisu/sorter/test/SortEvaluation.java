package com.omuraisu.sorter.test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

import com.omuraisu.sorter.SortOrder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

// 並び方の使いやすさを点数にする。分類はクリエイティブタブを使い、Sorter 自身の分類に有利にならないようにする
final class SortEvaluation {
	static final int WIDTH = 9;
	static final double[] WEIGHTS = {0.30, 0.15, 0.20, 0.20};
	static final int STABILITY = 3;

	private static final Map<SortOrder.Group, Double> USE = Map.of(
			SortOrder.Group.UTILITY, 1.0, SortOrder.Group.WEAPONS, 0.9, SortOrder.Group.POTIONS, 0.9,
			SortOrder.Group.MATERIALS, 0.5, SortOrder.Group.FUNCTIONAL, 0.4, SortOrder.Group.BUILDING, 0.4,
			SortOrder.Group.REDSTONE, 0.3, SortOrder.Group.COLORED, 0.3, SortOrder.Group.NATURAL, 0.3);
	// バニラのクリエイティブ画面でのタブの並び
	private static final List<SortOrder.Group> CREATIVE_TABS = List.of(
			SortOrder.Group.BUILDING, SortOrder.Group.COLORED, SortOrder.Group.NATURAL, SortOrder.Group.FUNCTIONAL, SortOrder.Group.REDSTONE,
			SortOrder.Group.UTILITY, SortOrder.Group.WEAPONS, SortOrder.Group.POTIONS, SortOrder.Group.MATERIALS, SortOrder.Group.SPAWN_EGGS);
	private static final List<Item> ARRIVALS = List.of(Items.IRON_SHOVEL, Items.COOKED_BEEF, Items.COAL, Items.OAK_PLANKS, Items.GRAVEL, Items.REDSTONE);

	record Scenario(String name, int capacity, List<ItemStack> stacks) {
	}

	record Strategy(String name, Function<List<ItemStack>, ItemStack[]> layout) {
	}

	private final SortOrder order;

	SortEvaluation(SortOrder order) {
		this.order = order;
	}

	static Scenario scenario(String name, int capacity, Object... itemsAndCounts) {
		List<ItemStack> stacks = new ArrayList<>();
		for (int i = 0; i < itemsAndCounts.length; i += 2) {
			stacks.add(new ItemStack((Item) itemsAndCounts[i], (int) itemsAndCounts[i + 1]));
		}
		return new Scenario(name, capacity, stacks);
	}

	static List<Scenario> scenarios() {
		return List.of(
				scenario("洞窟帰り", 27,
						Items.COBBLESTONE, 64, Items.COBBLESTONE, 64, Items.COBBLESTONE, 31, Items.DIRT, 40, Items.GRAVEL, 18, Items.TUFF, 52, Items.DEEPSLATE, 64,
						Items.COAL, 37, Items.RAW_IRON, 22, Items.RAW_COPPER, 41, Items.REDSTONE, 28, Items.LAPIS_LAZULI, 16, Items.DIAMOND, 5,
						Items.BONE, 9, Items.ROTTEN_FLESH, 14, Items.STRING, 6, Items.ARROW, 11, Items.TORCH, 23, Items.BREAD, 7, Items.STONE_PICKAXE, 1, Items.WATER_BUCKET, 1),
				scenario("建築の手持ち", 27,
						Items.OAK_LOG, 32, Items.OAK_PLANKS, 64, Items.OAK_STAIRS, 24, Items.OAK_SLAB, 40, Items.STONE_BRICKS, 64, Items.STONE_BRICK_STAIRS, 16,
						Items.GLASS, 48, Items.LANTERN, 12, Items.WOOL.white(), 20, Items.COOKED_BEEF, 16, Items.DIAMOND_AXE, 1, Items.SCAFFOLDING, 32),
				scenario("旅の装備", 27,
						Items.DIAMOND_PICKAXE, 1, Items.IRON_PICKAXE, 1, Items.IRON_AXE, 1, Items.IRON_SHOVEL, 1, Items.DIAMOND_SWORD, 1, Items.BOW, 1, Items.ARROW, 64,
						Items.SHIELD, 1, Items.COOKED_BEEF, 32, Items.BREAD, 20, Items.GOLDEN_CARROT, 12, Items.TORCH, 64, Items.ENDER_PEARL, 8, Items.WATER_BUCKET, 1,
						Items.CRAFTING_TABLE, 1, Items.FURNACE, 1, Items.BED.white(), 1, Items.OAK_BOAT, 1, Items.COBBLESTONE, 64),
				scenario("農作物チェスト", 27,
						Items.WHEAT, 64, Items.WHEAT, 22, Items.WHEAT_SEEDS, 64, Items.CARROT, 48, Items.POTATO, 37, Items.BEETROOT, 19, Items.BEETROOT_SEEDS, 25,
						Items.BREAD, 12, Items.BONE_MEAL, 30, Items.SUGAR_CANE, 44, Items.PUMPKIN, 8, Items.MELON_SLICE, 16, Items.IRON_HOE, 1),
				scenario("建材の大チェスト", 54,
						Items.OAK_LOG, 64, Items.OAK_LOG, 64, Items.OAK_PLANKS, 64, Items.SPRUCE_LOG, 64, Items.SPRUCE_PLANKS, 48, Items.BIRCH_LOG, 25, Items.STONE, 64, Items.STONE, 64,
						Items.COBBLESTONE, 64, Items.COBBLESTONE, 64, Items.COBBLESTONE, 64, Items.STONE_BRICKS, 64, Items.ANDESITE, 40, Items.GRANITE, 33, Items.DIORITE, 21,
						Items.SAND, 64, Items.GRAVEL, 64, Items.DIRT, 64, Items.GLASS, 64, Items.WOOL.white(), 16, Items.WOOL.red(), 9, Items.WOOL.blue(), 12, Items.BRICKS, 30),
				scenario("雑多な共用チェスト", 27,
						Items.ROTTEN_FLESH, 40, Items.BONE, 22, Items.STRING, 17, Items.GUNPOWDER, 9, Items.IRON_INGOT, 31, Items.GOLD_INGOT, 6, Items.IRON_SWORD, 1, Items.LEATHER_BOOTS, 1,
						Items.BOW, 1, Items.COBBLESTONE, 64, Items.DIRT, 64, Items.OAK_SAPLING, 12, Items.APPLE, 5, Items.BOOK, 3, Items.BUCKET, 2, Items.CHEST, 4, Items.REDSTONE, 12),
				scenario("ほぼ満杯の大チェスト", 54, packed()));
	}

	private static Object[] packed() {
		List<Item> items = List.of(Items.COBBLESTONE, Items.DIRT, Items.SAND, Items.COAL, Items.IRON_INGOT, Items.BREAD, Items.TORCH, Items.OAK_PLANKS, Items.STONE, Items.BONE, Items.STRING, Items.ARROW);
		List<Object> flat = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			flat.add(items.get(i % items.size()));
			flat.add(64);
		}
		return flat.toArray();
	}

	List<Strategy> strategies(int capacity) {
		return List.of(
				compact("ID順(1.0.0)", Comparator.comparingInt(stack -> BuiltInRegistries.ITEM.getId(stack.getItem())), capacity),
				compact("名前順", Comparator.comparing(stack -> stack.getHoverName().getString()), capacity),
				compact("タブ順", Comparator.<ItemStack>comparingInt(stack -> CREATIVE_TABS.indexOf(tab(stack))).thenComparingInt(order::rank), capacity),
				compact("新順序", order.comparator(), capacity));
	}

	private static Strategy compact(String name, Comparator<ItemStack> comparator, int capacity) {
		return new Strategy(name, stacks -> {
			List<ItemStack> sorted = new ArrayList<>(stacks);
			sorted.sort(comparator);
			ItemStack[] grid = new ItemStack[capacity];
			for (int i = 0; i < sorted.size(); i++) {
				grid[i] = sorted.get(i);
			}
			return grid;
		});
	}

	double[] evaluate(Strategy strategy, Scenario scenario) {
		ItemStack[] grid = strategy.layout().apply(scenario.stacks());
		return new double[] {cohesion(grid), neighbors(grid), reach(grid), stability(strategy, scenario, grid)};
	}

	static double total(double[] metrics, double[] weights) {
		double total = 0;
		for (int i = 0; i < metrics.length; i++) {
			total += metrics[i] * weights[i];
		}
		return total / Arrays.stream(weights).sum() * 100;
	}

	static double[] randomWeights(Random random) {
		double[] weights = random.doubles(WEIGHTS.length).map(value -> -Math.log(1 - value)).toArray();
		double sum = Arrays.stream(weights).sum();
		return Arrays.stream(weights).map(value -> value / sum).toArray();
	}

	private SortOrder.Group tab(ItemStack stack) {
		return order.tabGroup(stack).orElse(SortOrder.Group.SPAWN_EGGS);
	}

	// カテゴリが1つの塊になっているか。塊の数の逆数をスタック数で加重平均する
	private double cohesion(ItemStack[] grid) {
		boolean[] seen = new boolean[grid.length];
		Map<SortOrder.Group, int[]> clusters = new HashMap<>();
		for (int i = 0; i < grid.length; i++) {
			if (grid[i] == null) {
				continue;
			}
			int[] cluster = clusters.computeIfAbsent(tab(grid[i]), group -> new int[2]);
			cluster[1]++;
			if (!seen[i]) {
				cluster[0]++;
				flood(grid, i, tab(grid[i]), seen);
			}
		}
		double score = 0;
		int stacks = 0;
		for (int[] cluster : clusters.values()) {
			score += (double) cluster[1] / cluster[0];
			stacks += cluster[1];
		}
		return stacks == 0 ? 1 : score / stacks;
	}

	private void flood(ItemStack[] grid, int start, SortOrder.Group group, boolean[] seen) {
		if (start < 0 || start >= grid.length || seen[start] || grid[start] == null || tab(grid[start]) != group) {
			return;
		}
		seen[start] = true;
		if (start % WIDTH != 0) {
			flood(grid, start - 1, group, seen);
		}
		if (start % WIDTH != WIDTH - 1) {
			flood(grid, start + 1, group, seen);
		}
		flood(grid, start - WIDTH, group, seen);
		flood(grid, start + WIDTH, group, seen);
	}

	// 隣どうしが関連しているか。同じアイテム 1、同じ素材や種類 0.75、同じカテゴリ 0.5
	private double neighbors(ItemStack[] grid) {
		double score = 0;
		int pairs = 0;
		ItemStack previous = null;
		for (ItemStack stack : grid) {
			if (stack == null) {
				continue;
			}
			if (previous != null) {
				score += related(previous, stack);
				pairs++;
			}
			previous = stack;
		}
		return pairs == 0 ? 1 : score / pairs;
	}

	private double related(ItemStack a, ItemStack b) {
		if (a.is(b.getItem())) {
			return 1;
		}
		String[] pathA = BuiltInRegistries.ITEM.getKey(a.getItem()).getPath().split("_");
		String[] pathB = BuiltInRegistries.ITEM.getKey(b.getItem()).getPath().split("_");
		if (pathA[0].equals(pathB[0]) || pathA[pathA.length - 1].equals(pathB[pathB.length - 1])) {
			return 0.75;
		}
		return tab(a) == tab(b) ? 0.5 : 0;
	}

	// よく使うものが左上の近くにあるか。最良と最悪の並びの間で正規化する
	private double reach(ItemStack[] grid) {
		List<Double> uses = new ArrayList<>();
		double cost = 0;
		for (int i = 0; i < grid.length; i++) {
			if (grid[i] != null) {
				double use = USE.getOrDefault(tab(grid[i]), 0.1);
				uses.add(use);
				cost += use * distance(i);
			}
		}
		uses.sort(Comparator.reverseOrder());
		double best = 0;
		double worst = 0;
		for (int i = 0; i < uses.size(); i++) {
			best += uses.get(i) * distance(i);
			worst += uses.get(uses.size() - 1 - i) * distance(i);
		}
		return worst == best ? 1 : Math.clamp(1 - (cost - best) / (worst - best), 0, 1);
	}

	private static int distance(int slot) {
		return slot / WIDTH + slot % WIDTH;
	}

	// 1種類増減したときに、他のスタックが同じ場所に留まるか
	private double stability(Strategy strategy, Scenario scenario, ItemStack[] grid) {
		double score = 0;
		int cases = 0;
		for (Item item : scenario.stacks().stream().map(ItemStack::getItem).distinct().toList()) {
			List<ItemStack> changed = new ArrayList<>(scenario.stacks());
			changed.removeIf(stack -> stack.is(item));
			score += unmoved(grid, strategy.layout().apply(changed));
			cases++;
		}
		for (Item item : ARRIVALS) {
			if (scenario.stacks().size() < scenario.capacity()) {
				List<ItemStack> changed = new ArrayList<>(scenario.stacks());
				changed.add(new ItemStack(item));
				score += unmoved(grid, strategy.layout().apply(changed));
				cases++;
			}
		}
		return cases == 0 ? 1 : score / cases;
	}

	private static double unmoved(ItemStack[] before, ItemStack[] after) {
		int kept = 0;
		int same = 0;
		Set<ItemStack> present = Collections.newSetFromMap(new IdentityHashMap<>());
		Arrays.stream(before).filter(Objects::nonNull).forEach(present::add);
		for (int i = 0; i < after.length; i++) {
			if (after[i] == null || !present.contains(after[i])) {
				continue;
			}
			kept++;
			if (before[i] == after[i]) {
				same++;
			}
		}
		return kept == 0 ? 1 : (double) same / kept;
	}
}
