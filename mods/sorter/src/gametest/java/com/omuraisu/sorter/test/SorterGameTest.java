package com.omuraisu.sorter.test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

import com.omuraisu.sorter.SortOrder;
import com.omuraisu.sorter.Sorter;

import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

public class SorterGameTest {
	private static final int CONTAINER_ID = 1;
	// threeRows のメニューでのプレイヤーのメインインベントリ先頭
	private static final int MAIN_INVENTORY_SLOT = 27;

	@GameTest
	public void sortsAndMergesChest(GameTestHelper helper) {
		SimpleContainer chest = new SimpleContainer(27);
		chest.setItem(0, new ItemStack(Items.DIRT, 10));
		chest.setItem(5, new ItemStack(Items.DIAMOND, 1));
		chest.setItem(10, new ItemStack(Items.DIRT, 60));
		chest.setItem(20, new ItemStack(Items.STONE, 3));

		sort(helper, chest, 0);

		// 素材と建材で行が分かれ、建材と自然ブロックは同じ行に続く
		assertStack(helper, chest, 0, Items.DIAMOND, 1);
		assertStack(helper, chest, 9, Items.STONE, 3);
		assertStack(helper, chest, 10, Items.DIRT, 64);
		assertStack(helper, chest, 11, Items.DIRT, 6);
		assertUsedSlots(helper, chest, 4);
		helper.succeed();
	}

	@GameTest
	public void sortsMainInventoryOnly(GameTestHelper helper) {
		SimpleContainer chest = new SimpleContainer(27);
		chest.setItem(4, new ItemStack(Items.DIAMOND, 1));
		Inventory inventory = FakePlayer.get(helper.getLevel()).getInventory();
		inventory.setItem(0, new ItemStack(Items.DIRT, 1));
		inventory.setItem(20, new ItemStack(Items.DIRT, 5));
		inventory.setItem(30, new ItemStack(Items.STONE, 2));

		sort(helper, chest, MAIN_INVENTORY_SLOT);

		assertStack(helper, inventory, 0, Items.DIRT, 1);
		assertStack(helper, inventory, 9, Items.STONE, 2);
		assertStack(helper, inventory, 10, Items.DIRT, 5);
		assertStack(helper, chest, 4, Items.DIAMOND, 1);
		inventory.clearContent();
		helper.succeed();
	}

	@GameTest
	public void ordersGearBestFirst(GameTestHelper helper) {
		SimpleContainer chest = fill(Items.WOODEN_SHOVEL, Items.IRON_PICKAXE, Items.IRON_SWORD, Items.DIAMOND_PICKAXE, Items.NETHERITE_AXE, Items.BOW, Items.ARROW, Items.DIAMOND_SWORD,
				Items.IRON_BOOTS, Items.DIAMOND_LEGGINGS, Items.IRON_HELMET, Items.DIAMOND_HELMET);

		sort(helper, chest, 0);

		assertOrder(helper, chest, Items.DIAMOND_PICKAXE, Items.IRON_PICKAXE, Items.NETHERITE_AXE, Items.WOODEN_SHOVEL, Items.DIAMOND_SWORD, Items.IRON_SWORD, Items.BOW, Items.ARROW,
				Items.DIAMOND_HELMET, Items.DIAMOND_LEGGINGS, Items.IRON_HELMET, Items.IRON_BOOTS);
		helper.succeed();
	}

	@GameTest
	public void ordersFoodByValue(GameTestHelper helper) {
		SimpleContainer chest = fill(Items.ROTTEN_FLESH, Items.BREAD, Items.COOKED_BEEF, Items.APPLE, Items.GOLDEN_CARROT);

		sort(helper, chest, 0);

		assertOrder(helper, chest, Items.COOKED_BEEF, Items.GOLDEN_CARROT, Items.BREAD, Items.APPLE, Items.ROTTEN_FLESH);
		helper.succeed();
	}

	@GameTest
	public void treatsHarmfulFoodAsDrops(GameTestHelper helper) {
		SimpleContainer chest = fill(Items.ROTTEN_FLESH, Items.BONE, Items.BREAD, Items.CHICKEN);

		sort(helper, chest, 0);

		assertOrder(helper, chest, Items.BREAD, Items.CHICKEN, Items.BONE, Items.ROTTEN_FLESH);
		helper.succeed();
	}

	@GameTest
	public void treatsBedsAsFurniture(GameTestHelper helper) {
		SimpleContainer chest = fill(Items.COBBLESTONE, Items.BED.white(), Items.WOOL.white());

		sort(helper, chest, 0);

		assertOrder(helper, chest, Items.BED.white(), Items.COBBLESTONE, Items.WOOL.white());
		helper.succeed();
	}

	@GameTest
	public void putsEnchantedAndHealthyGearFirst(GameTestHelper helper) {
		ItemStack plain = new ItemStack(Items.DIAMOND_PICKAXE);
		ItemStack damaged = new ItemStack(Items.DIAMOND_PICKAXE);
		damaged.setDamageValue(100);
		ItemStack enchanted = new ItemStack(Items.DIAMOND_PICKAXE);
		enchanted.set(DataComponents.ENCHANTMENTS, enchantments(helper, Enchantments.EFFICIENCY, 3));
		SimpleContainer chest = new SimpleContainer(27);
		chest.setItem(3, damaged);
		chest.setItem(7, plain);
		chest.setItem(11, enchanted);

		sort(helper, chest, 0);

		helper.assertTrue(chest.getItem(0).has(DataComponents.ENCHANTMENTS) && !chest.getItem(0).getEnchantments().isEmpty(), "enchanted first");
		helper.assertValueEqual(chest.getItem(1).getDamageValue(), 0, "healthy second");
		helper.assertValueEqual(chest.getItem(2).getDamageValue(), 100, "damaged last");
		helper.succeed();
	}

	@GameTest
	public void ordersBooksByEnchantmentThenLevel(GameTestHelper helper) {
		SimpleContainer chest = new SimpleContainer(27);
		chest.setItem(0, book(helper, Enchantments.SHARPNESS, 5));
		chest.setItem(4, book(helper, Enchantments.EFFICIENCY, 3));
		chest.setItem(8, book(helper, Enchantments.EFFICIENCY, 5));

		sort(helper, chest, 0);

		assertBook(helper, chest, 0, Enchantments.EFFICIENCY, 5);
		assertBook(helper, chest, 1, Enchantments.EFFICIENCY, 3);
		assertBook(helper, chest, 2, Enchantments.SHARPNESS, 5);
		helper.succeed();
	}

	@GameTest
	public void keepsMaterialFamiliesTogether(GameTestHelper helper) {
		SimpleContainer chest = fill(Items.SPRUCE_PLANKS, Items.OAK_STAIRS, Items.COBBLESTONE, Items.SPRUCE_LOG, Items.OAK_LOG, Items.OAK_PLANKS);

		sort(helper, chest, 0);

		assertOrder(helper, chest, Items.OAK_LOG, Items.OAK_PLANKS, Items.OAK_STAIRS, Items.SPRUCE_LOG, Items.SPRUCE_PLANKS, Items.COBBLESTONE);
		helper.succeed();
	}

	@GameTest
	public void startsGroupsOnNewRowsWhenThereIsRoom(GameTestHelper helper) {
		SimpleContainer chest = fill(Items.COBBLESTONE, Items.IRON_PICKAXE, Items.BREAD);

		sort(helper, chest, 0);

		assertStack(helper, chest, 0, Items.IRON_PICKAXE, 1);
		assertStack(helper, chest, 9, Items.BREAD, 1);
		assertStack(helper, chest, 18, Items.COBBLESTONE, 1);
		helper.succeed();
	}

	// 関連の深いグループでも、続けて置くと 1 行に収まるはずのグループが 2 行に割れるなら次の行頭から置く
	@GameTest
	public void avoidsSplittingGroupAcrossRows(GameTestHelper helper) {
		SimpleContainer chest = fill(Items.COBBLESTONE, Items.STONE_BRICKS, Items.BRICKS,
				Items.DIRT, Items.SAND, Items.GRAVEL, Items.CLAY, Items.SNOW_BLOCK, Items.ICE, Items.MUD, Items.OAK_SAPLING, Items.POPPY);
		SortOrder order = SortOrder.of(helper.getLevel().getServer());

		sort(helper, chest, 0);

		for (int i = 0; i < chest.getContainerSize(); i++) {
			ItemStack stack = chest.getItem(i);
			String expected = i < 3 ? "BUILDING" : i >= 9 && i < 18 ? "NATURAL" : "empty";
			helper.assertValueEqual(stack.isEmpty() ? "empty" : order.group(stack).name(), expected, "slot " + i);
		}
		helper.succeed();
	}

	@GameTest
	public void packsFullChestWithoutGaps(GameTestHelper helper) {
		Item[] items = {Items.IRON_PICKAXE, Items.BREAD, Items.COBBLESTONE, Items.WATER_BUCKET, Items.BED.white()};
		SimpleContainer chest = new SimpleContainer(27);
		for (int i = 0; i < chest.getContainerSize(); i++) {
			chest.setItem(i, new ItemStack(items[i % items.length], items[i % items.length].getDefaultMaxStackSize()));
		}
		int before = totalCount(chest);

		sort(helper, chest, 0);

		assertUsedSlots(helper, chest, 27);
		helper.assertValueEqual(totalCount(chest), before, "item count");
		helper.succeed();
	}

	@GameTest
	public void ignoresOriginalArrangement(GameTestHelper helper) {
		SortEvaluation.Scenario scenario = SortEvaluation.scenarios().getFirst();
		Random random = new Random(1);
		List<String> first = null;
		for (int attempt = 0; attempt < 20; attempt++) {
			List<Integer> slots = new ArrayList<>(IntStream.range(0, scenario.capacity()).boxed().toList());
			Collections.shuffle(slots, random);
			SimpleContainer chest = new SimpleContainer(scenario.capacity());
			for (int i = 0; i < scenario.stacks().size(); i++) {
				chest.setItem(slots.get(i), scenario.stacks().get(i).copy());
			}
			sort(helper, chest, 0);
			List<String> result = contents(chest);
			if (first != null) {
				helper.assertValueEqual(result, first, "layout after shuffle");
			}
			first = result;
		}
		helper.succeed();
	}

	@GameTest
	public void secondSortChangesNothing(GameTestHelper helper) {
		for (SortEvaluation.Scenario scenario : SortEvaluation.scenarios()) {
			SimpleContainer chest = new SimpleContainer(scenario.capacity());
			List<ItemStack> stacks = scenario.stacks();
			for (int i = 0; i < stacks.size(); i++) {
				chest.setItem(i, stacks.get(i).copy());
			}
			int before = totalCount(chest);
			sort(helper, chest, 0);
			List<String> once = contents(chest);
			helper.assertValueEqual(totalCount(chest), before, "item count in " + scenario.name());
			sort(helper, chest, 0);
			helper.assertValueEqual(contents(chest), once, "second sort of " + scenario.name());
		}
		helper.succeed();
	}

	// 独自の評価式で、新しい並びがどの場面でも他の並びを上回ること
	@GameTest
	public void beatsOtherOrdersOnEvaluation(GameTestHelper helper) {
		SortOrder order = SortOrder.of(helper.getLevel().getServer());
		SortEvaluation evaluation = new SortEvaluation(order);
		List<SortEvaluation.Scenario> scenarios = SortEvaluation.scenarios();
		List<String> names = evaluation.strategies(27).stream().map(SortEvaluation.Strategy::name).toList();
		double[][][] results = new double[scenarios.size()][names.size()][];

		for (int s = 0; s < scenarios.size(); s++) {
			SortEvaluation.Scenario scenario = scenarios.get(s);
			List<SortEvaluation.Strategy> strategies = evaluation.strategies(scenario.capacity());
			for (int i = 0; i < strategies.size(); i++) {
				results[s][i] = evaluation.evaluate(strategies.get(i), scenario);
				System.out.printf("EVAL %s | %s | %.1f | %s%n", scenario.name(), names.get(i), SortEvaluation.total(results[s][i], SortEvaluation.WEIGHTS),
						Arrays.toString(Arrays.stream(results[s][i]).map(v -> Math.round(v * 100) / 100.0).toArray()));
			}
		}

		// 評価式の重みに結果が左右されていないかも見る
		int ours = names.size() - 1;
		Random random = new Random(1);
		int[] wins = new int[names.size()];
		int trials = 1000;
		for (int t = 0; t < trials; t++) {
			double[] weights = SortEvaluation.randomWeights(random);
			double best = Double.NEGATIVE_INFINITY;
			int winner = -1;
			for (int i = 0; i < names.size(); i++) {
				double sum = 0;
				for (double[][] result : results) {
					sum += SortEvaluation.total(result[i], weights);
				}
				if (sum > best) {
					best = sum;
					winner = i;
				}
			}
			wins[winner]++;
		}
		System.out.printf("EVAL wins with random weights: %s %s of %d%n", names, Arrays.toString(wins), trials);

		for (int s = 0; s < scenarios.size(); s++) {
			double score = SortEvaluation.total(results[s][ours], SortEvaluation.WEIGHTS);
			for (int i = 0; i < ours; i++) {
				double other = SortEvaluation.total(results[s][i], SortEvaluation.WEIGHTS);
				helper.assertTrue(score > other, scenarios.get(s).name() + ": " + score + " <= " + names.get(i) + " " + other);
			}
		}
		helper.succeed();
	}

	private static ItemEnchantments enchantments(GameTestHelper helper, ResourceKey<Enchantment> enchantment, int level) {
		ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
		enchantments.set(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(enchantment), level);
		return enchantments.toImmutable();
	}

	private static ItemStack book(GameTestHelper helper, ResourceKey<Enchantment> enchantment, int level) {
		ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
		book.set(DataComponents.STORED_ENCHANTMENTS, enchantments(helper, enchantment, level));
		return book;
	}

	private static void assertBook(GameTestHelper helper, Container chest, int slot, ResourceKey<Enchantment> enchantment, int level) {
		ItemEnchantments stored = chest.getItem(slot).get(DataComponents.STORED_ENCHANTMENTS);
		helper.assertValueEqual(stored, enchantments(helper, enchantment, level), "book in slot " + slot);
	}

	private static SimpleContainer fill(Item... items) {
		SimpleContainer chest = new SimpleContainer(27);
		for (int i = 0; i < items.length; i++) {
			chest.setItem(i * 2 % 27, new ItemStack(items[i]));
		}
		return chest;
	}

	private static void assertOrder(GameTestHelper helper, Container chest, Item... expected) {
		List<Item> actual = new ArrayList<>();
		for (int i = 0; i < chest.getContainerSize(); i++) {
			if (!chest.getItem(i).isEmpty()) {
				actual.add(chest.getItem(i).getItem());
			}
		}
		helper.assertValueEqual(actual, List.of(expected), "order");
	}

	private static void assertUsedSlots(GameTestHelper helper, Container chest, int expected) {
		int used = 0;
		for (int i = 0; i < chest.getContainerSize(); i++) {
			used += chest.getItem(i).isEmpty() ? 0 : 1;
		}
		helper.assertValueEqual(used, expected, "used slots");
	}

	private static List<String> contents(Container chest) {
		List<String> contents = new ArrayList<>();
		for (int i = 0; i < chest.getContainerSize(); i++) {
			contents.add(chest.getItem(i).toString());
		}
		return contents;
	}

	private static int totalCount(Container chest) {
		int total = 0;
		for (int i = 0; i < chest.getContainerSize(); i++) {
			total += chest.getItem(i).getCount();
		}
		return total;
	}

	private static void sort(GameTestHelper helper, Container chest, int slot) {
		FakePlayer player = FakePlayer.get(helper.getLevel());
		player.containerMenu = chest.getContainerSize() == 54 ? ChestMenu.sixRows(CONTAINER_ID, player.getInventory(), chest) : ChestMenu.threeRows(CONTAINER_ID, player.getInventory(), chest);
		Sorter.sort(player, CONTAINER_ID, slot);
		player.containerMenu = player.inventoryMenu;
	}

	private static void assertStack(GameTestHelper helper, Container container, int slot, Item item, int count) {
		ItemStack stack = container.getItem(slot);
		helper.assertTrue(stack.is(item) && stack.getCount() == count,
				"slot " + slot + ": expected " + count + " " + item + " but was " + stack);
	}
}
