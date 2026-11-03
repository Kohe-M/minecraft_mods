package com.omuraisu.sorter.test;

import com.omuraisu.sorter.Sorter;

import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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

		assertStack(helper, chest, 0, Items.STONE, 3);
		assertStack(helper, chest, 1, Items.DIRT, 64);
		assertStack(helper, chest, 2, Items.DIRT, 6);
		assertStack(helper, chest, 3, Items.DIAMOND, 1);
		for (int i = 4; i < chest.getContainerSize(); i++) {
			helper.assertTrue(chest.getItem(i).isEmpty(), "slot " + i + " should be empty");
		}
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

	private static void sort(GameTestHelper helper, Container chest, int slot) {
		FakePlayer player = FakePlayer.get(helper.getLevel());
		player.containerMenu = ChestMenu.threeRows(CONTAINER_ID, player.getInventory(), chest);
		Sorter.sort(player, CONTAINER_ID, slot);
		player.containerMenu = player.inventoryMenu;
	}

	private static void assertStack(GameTestHelper helper, Container container, int slot, Item item, int count) {
		ItemStack stack = container.getItem(slot);
		helper.assertTrue(stack.is(item) && stack.getCount() == count,
				"slot " + slot + ": expected " + count + " " + item + " but was " + stack);
	}
}
