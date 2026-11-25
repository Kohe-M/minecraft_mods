package com.omuraisu.digger.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.omuraisu.digger.BreakDirectionAccess;
import com.omuraisu.digger.DigSettings;
import com.omuraisu.digger.Digger;
import com.omuraisu.digger.DiggerConfig;

import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class DiggerServerGameTest {
	private static final Direction SIDE = Direction.SOUTH;

	@GameTest
	public void sideFacePlacesBoxAroundTarget(GameTestHelper helper) {
		withPlayer(helper, player -> {
			prepare(player, GameType.CREATIVE, new ItemStack(Items.IRON_PICKAXE));
			stand(helper, player, 3.5, 1.0, 6.5, 180, 0);
			// 奥行き 3 は面の奥（北）へ。高さは足元の段 1 から 5 段
			fill(helper, new BlockPos(0, 0, 0), new BlockPos(7, 0, 7), Blocks.STONE);
			fill(helper, new BlockPos(0, 1, 0), new BlockPos(6, 6, 4), Blocks.STONE);
			Digger.setSettings(player, new DigSettings(true, 5, 5, 3, false));

			breakAt(helper, player, new BlockPos(3, 2, 3), SIDE);

			assertAll(helper, new BlockPos(1, 1, 1), new BlockPos(5, 5, 3), Blocks.AIR);
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(0, 2, 2));
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(6, 2, 2));
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(3, 6, 2));
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(3, 2, 0));
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(3, 2, 4));
		});
		helper.succeed();
	}

	// 狙った段が足元から数えた高さに入らないときは、入るところまで箱を動かす
	@GameTest
	public void movesBoxUpToIncludeTarget(GameTestHelper helper) {
		withPlayer(helper, player -> {
			prepare(player, GameType.CREATIVE, new ItemStack(Items.IRON_PICKAXE));
			stand(helper, player, 3.5, 1.0, 5.5, 180, 0);
			fill(helper, new BlockPos(0, 0, 0), new BlockPos(7, 0, 7), Blocks.STONE);
			fill(helper, new BlockPos(1, 1, 3), new BlockPos(5, 5, 3), Blocks.STONE);
			Digger.setSettings(player, new DigSettings(true, 3, 3, 1, false));

			breakAt(helper, player, new BlockPos(3, 4, 3), SIDE);

			// 足元 1 から 3 段なら 1〜3 だが、狙いの 4 が入らないので 2〜4 に動く
			assertAll(helper, new BlockPos(2, 2, 3), new BlockPos(4, 4, 3), Blocks.AIR);
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(3, 1, 3));
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(3, 5, 3));
		});
		helper.succeed();
	}

	// 足元の段は floor(y + 0.2)。農地のように少し低い床でも、床の 1 つ上の段になる
	@GameTest
	public void feetStepAllowsSlightlyLowerFloor(GameTestHelper helper) {
		withPlayer(helper, player -> {
			prepare(player, GameType.CREATIVE, new ItemStack(Items.IRON_PICKAXE));
			fill(helper, new BlockPos(0, 0, 0), new BlockPos(7, 0, 7), Blocks.STONE);
			helper.setBlock(new BlockPos(3, 0, 4), Blocks.FARMLAND);
			fill(helper, new BlockPos(1, 1, 3), new BlockPos(5, 5, 3), Blocks.STONE);
			stand(helper, player, 3.5, 0.9375, 4.5, 180, 0);
			Digger.setSettings(player, new DigSettings(true, 5, 5, 1, false));

			breakAt(helper, player, new BlockPos(3, 3, 3), SIDE);

			// 足元の段が 1 なら 1〜5。floor(y) の 0 なら 0〜4 になってしまう
			assertAll(helper, new BlockPos(1, 1, 3), new BlockPos(5, 5, 3), Blocks.AIR);
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(3, 0, 3));
		});
		helper.succeed();
	}

	// 上の面では狙った段を幅 × 高さの水平な 1 枚で取り、奥行きは下へ
	@GameTest
	public void topFaceTakesHorizontalPlate(GameTestHelper helper) {
		withPlayer(helper, player -> {
			prepare(player, GameType.CREATIVE, new ItemStack(Items.IRON_PICKAXE));
			stand(helper, player, 3.5, 6.0, 3.5, 180, 90);
			fill(helper, new BlockPos(0, 1, 0), new BlockPos(6, 3, 6), Blocks.STONE);
			Digger.setSettings(player, new DigSettings(true, 5, 5, 2, false));

			breakAt(helper, player, new BlockPos(3, 2, 3), Direction.UP);

			// 北（-Z）を向いているので高さは Z へ。中央が 3 で 1〜5
			assertAll(helper, new BlockPos(1, 1, 1), new BlockPos(5, 2, 5), Blocks.AIR);
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(3, 3, 3));
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(0, 2, 3));
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(3, 2, 0));
		});
		helper.succeed();
	}

	// 1×2 は狙ったブロックと、向いている方角の 1 つ向こう
	@GameTest
	public void oneByTwoExtendsForward(GameTestHelper helper) {
		withPlayer(helper, player -> {
			prepare(player, GameType.CREATIVE, new ItemStack(Items.IRON_PICKAXE));
			stand(helper, player, 3.5, 6.0, 3.5, 180, 90);
			fill(helper, new BlockPos(2, 1, 1), new BlockPos(4, 3, 5), Blocks.STONE);
			Digger.setSettings(player, new DigSettings(true, 1, 2, 1, false));

			breakAt(helper, player, new BlockPos(3, 2, 3), Direction.UP);

			assertAll(helper, new BlockPos(3, 2, 2), new BlockPos(3, 2, 3), Blocks.AIR);
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(3, 2, 4));
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(2, 2, 3));
		});
		helper.succeed();
	}

	// 下の面では奥行きが上へ
	@GameTest
	public void bottomFaceExtendsUp(GameTestHelper helper) {
		withPlayer(helper, player -> {
			prepare(player, GameType.CREATIVE, new ItemStack(Items.IRON_PICKAXE));
			stand(helper, player, 3.5, 1.0, 3.5, 0, -90);
			fill(helper, new BlockPos(0, 1, 0), new BlockPos(6, 4, 6), Blocks.STONE);
			Digger.setSettings(player, new DigSettings(true, 3, 3, 2, false));

			breakAt(helper, player, new BlockPos(3, 2, 3), Direction.DOWN);

			// 南（+Z）を向いているので高さは Z へ 2〜4。奥行きは上へ 2〜3
			assertAll(helper, new BlockPos(2, 2, 2), new BlockPos(4, 3, 4), Blocks.AIR);
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(3, 1, 3));
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(3, 4, 3));
		});
		helper.succeed();
	}

	@GameTest
	public void keepsBlockEntities(GameTestHelper helper) {
		withPlayer(helper, player -> {
			prepare(player, GameType.CREATIVE, new ItemStack(Items.IRON_PICKAXE));
			stand(helper, player, 3.5, 1.0, 6.5, 180, 0);
			fill(helper, new BlockPos(1, 1, 3), new BlockPos(5, 3, 3), Blocks.STONE);
			helper.setBlock(new BlockPos(2, 2, 3), Blocks.CHEST);
			Digger.setSettings(player, new DigSettings(true, 3, 3, 1, false));

			breakAt(helper, player, new BlockPos(3, 2, 3), SIDE);

			helper.assertBlockPresent(Blocks.CHEST, new BlockPos(2, 2, 3));
			assertAll(helper, new BlockPos(3, 1, 3), new BlockPos(4, 3, 3), Blocks.AIR);
		});
		helper.succeed();
	}

	@GameTest
	public void keepsRails(GameTestHelper helper) {
		withPlayer(helper, player -> {
			prepare(player, GameType.CREATIVE, new ItemStack(Items.IRON_PICKAXE));
			stand(helper, player, 3.5, 1.0, 6.5, 180, 0);
			fill(helper, new BlockPos(0, 0, 0), new BlockPos(7, 0, 7), Blocks.STONE);
			fill(helper, new BlockPos(1, 1, 3), new BlockPos(5, 3, 3), Blocks.STONE);
			helper.setBlock(new BlockPos(2, 1, 3), Blocks.RAIL);
			Digger.setSettings(player, new DigSettings(true, 3, 3, 1, false));

			breakAt(helper, player, new BlockPos(3, 2, 3), SIDE);

			helper.assertBlockPresent(Blocks.RAIL, new BlockPos(2, 1, 3));
			helper.assertBlockNotPresent(Blocks.STONE, new BlockPos(3, 1, 3));
		});
		helper.succeed();
	}

	@GameTest
	public void keepsBlocksHarderThanLimit(GameTestHelper helper) {
		withPlayer(helper, player -> {
			prepare(player, GameType.CREATIVE, new ItemStack(Items.IRON_PICKAXE));
			stand(helper, player, 3.5, 1.0, 6.5, 180, 0);
			fill(helper, new BlockPos(1, 1, 3), new BlockPos(5, 3, 3), Blocks.STONE);
			helper.setBlock(new BlockPos(2, 2, 3), Blocks.OBSIDIAN);
			Digger.setSettings(player, new DigSettings(true, 3, 3, 1, false));

			breakAt(helper, player, new BlockPos(3, 2, 3), SIDE);

			helper.assertBlockPresent(Blocks.OBSIDIAN, new BlockPos(2, 2, 3));
			helper.assertBlockNotPresent(Blocks.STONE, new BlockPos(3, 1, 3));
		});
		helper.succeed();
	}

	@GameTest
	public void keepsBuddingAmethyst(GameTestHelper helper) {
		withPlayer(helper, player -> {
			prepare(player, GameType.CREATIVE, new ItemStack(Items.IRON_PICKAXE));
			stand(helper, player, 3.5, 1.0, 6.5, 180, 0);
			fill(helper, new BlockPos(1, 1, 3), new BlockPos(5, 3, 3), Blocks.STONE);
			helper.setBlock(new BlockPos(2, 2, 3), Blocks.BUDDING_AMETHYST);
			Digger.setSettings(player, new DigSettings(true, 3, 3, 1, false));

			breakAt(helper, player, new BlockPos(3, 2, 3), SIDE);

			helper.assertBlockPresent(Blocks.BUDDING_AMETHYST, new BlockPos(2, 2, 3));
			helper.assertBlockNotPresent(Blocks.STONE, new BlockPos(3, 1, 3));
		});
		helper.succeed();
	}

	@GameTest
	public void keepsBlocksTouchingLava(GameTestHelper helper) {
		withPlayer(helper, player -> {
			prepare(player, GameType.CREATIVE, new ItemStack(Items.IRON_PICKAXE));
			stand(helper, player, 3.5, 1.0, 6.5, 180, 0);
			fill(helper, new BlockPos(1, 1, 3), new BlockPos(5, 3, 3), Blocks.STONE);
			// 溶岩は流れ出す前に同じ tick で確かめる。触れている石だけを壁として残す
			helper.setBlock(new BlockPos(2, 1, 2), Blocks.LAVA);
			Digger.setSettings(player, new DigSettings(true, 3, 3, 1, false));

			breakAt(helper, player, new BlockPos(3, 2, 3), SIDE);

			helper.assertBlockPresent(Blocks.STONE, new BlockPos(2, 1, 3));
			helper.assertBlockNotPresent(Blocks.STONE, new BlockPos(2, 3, 3));
			helper.assertBlockNotPresent(Blocks.STONE, new BlockPos(3, 1, 3));
		});
		helper.succeed();
	}

	@GameTest
	public void keepsBlocksOutsideWorldBorder(GameTestHelper helper) {
		withPlayer(helper, player -> {
			prepare(player, GameType.CREATIVE, new ItemStack(Items.IRON_PICKAXE));
			stand(helper, player, 3.5, 1.0, 6.5, 180, 0);
			fill(helper, new BlockPos(1, 1, 3), new BlockPos(5, 3, 3), Blocks.STONE);
			Digger.setSettings(player, new DigSettings(true, 3, 3, 1, false));

			WorldBorder border = helper.getLevel().getWorldBorder();
			double centerX = border.getCenterX();
			double centerZ = border.getCenterZ();
			double size = border.getSize();
			BlockPos target = helper.absolutePos(new BlockPos(3, 2, 3));
			border.setCenter(target.getX() + 0.5, target.getZ() + 0.5);
			border.setSize(2.0);
			try {
				breakAt(helper, player, new BlockPos(3, 2, 3), SIDE);
			} finally {
				// ボーダーはワールド全体に効くので、同じ tick のうちに必ず戻す
				border.setCenter(centerX, centerZ);
				border.setSize(size);
			}

			helper.assertBlockNotPresent(Blocks.STONE, new BlockPos(3, 2, 3));
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(2, 2, 3));
			helper.assertBlockNotPresent(Blocks.STONE, new BlockPos(4, 1, 3));
		});
		helper.succeed();
	}

	// 手に持った道具で掘れないものは、ホットバーの左から掘れるつるはし・シャベルを探して使う
	@GameTest
	public void usesHotbarTool(GameTestHelper helper) {
		withPlayer(helper, player -> {
			prepare(player, GameType.SURVIVAL, new ItemStack(Items.IRON_SHOVEL));
			player.getInventory().setItem(4, new ItemStack(Items.IRON_SHOVEL));
			player.getInventory().setSelectedSlot(4);
			player.getInventory().setItem(1, new ItemStack(Items.STONE_PICKAXE));
			stand(helper, player, 3.5, 1.0, 5.5, 180, 0);
			helper.setBlock(new BlockPos(3, 2, 3), Blocks.DIRT);
			helper.setBlock(new BlockPos(3, 1, 3), Blocks.STONE);
			Digger.setSettings(player, new DigSettings(true, 1, 2, 1, false));

			breakAt(helper, player, new BlockPos(3, 2, 3), SIDE);

			helper.assertBlockNotPresent(Blocks.DIRT, new BlockPos(3, 2, 3));
			helper.assertBlockNotPresent(Blocks.STONE, new BlockPos(3, 1, 3));
			helper.assertValueEqual(player.getInventory().getSelectedSlot(), 4, "selected slot restored");
			helper.assertValueEqual(player.getInventory().getItem(1).getDamageValue(), 1, "pickaxe damage");
		});
		helper.succeed();
	}

	// 残りが 2 以下の道具は使わず、壊さない。最初のブロックの分と最後の 1 を残す
	@GameTest
	public void stopsBeforeToolBreaks(GameTestHelper helper) {
		withPlayer(helper, player -> {
			prepare(player, GameType.SURVIVAL, new ItemStack(Items.WOODEN_PICKAXE));
			ItemStack pickaxe = player.getMainHandItem();
			pickaxe.setDamageValue(pickaxe.getMaxDamage() - 3);
			stand(helper, player, 3.5, 1.0, 5.5, 180, 0);
			fill(helper, new BlockPos(3, 1, 2), new BlockPos(3, 2, 3), Blocks.STONE);
			Digger.setSettings(player, new DigSettings(true, 1, 2, 2, false));

			breakAt(helper, player, new BlockPos(3, 2, 3), SIDE);

			helper.assertBlockNotPresent(Blocks.STONE, new BlockPos(3, 2, 3));
			int broken = 0;
			for (BlockPos pos : List.of(new BlockPos(3, 1, 3), new BlockPos(3, 2, 2), new BlockPos(3, 1, 2))) {
				if (helper.getBlockState(pos).isAir()) {
					broken++;
				}
			}
			helper.assertValueEqual(broken, 1, "extra blocks broken before the tool is kept");
			helper.assertValueEqual(pickaxe.getMaxDamage() - pickaxe.getDamageValue(), 1, "remaining durability");
		});
		helper.succeed();
	}

	// 手に持った道具で掘れない鉱石は、鉱石一括を始めない（木のつるはしでダイヤモンドを失わない）
	@GameTest
	public void doesNotStartVeinWhenToolCannotMine(GameTestHelper helper) {
		withPlayer(helper, player -> {
			prepare(player, GameType.SURVIVAL, new ItemStack(Items.WOODEN_PICKAXE));
			stand(helper, player, 3.5, 1.0, 5.5, 180, 0);
			helper.setBlock(new BlockPos(3, 2, 3), Blocks.DIAMOND_ORE);
			helper.setBlock(new BlockPos(3, 1, 3), Blocks.DIAMOND_ORE);
			helper.setBlock(new BlockPos(4, 2, 4), Blocks.DIAMOND_ORE);
			Digger.setSettings(player, new DigSettings(false, 3, 3, 1, true));

			breakAt(helper, player, new BlockPos(3, 2, 3), SIDE);

			helper.assertBlockNotPresent(Blocks.DIAMOND_ORE, new BlockPos(3, 2, 3));
			helper.assertBlockPresent(Blocks.DIAMOND_ORE, new BlockPos(3, 1, 3));
			helper.assertBlockPresent(Blocks.DIAMOND_ORE, new BlockPos(4, 2, 4));
		});
		helper.succeed();
	}

	// 石の鉄鉱石と深層岩の鉄鉱石はひとつながり。違う鉱石は残す
	@GameTest
	public void veinConnectsSameOreAcrossVariants(GameTestHelper helper) {
		withPlayer(helper, player -> {
			prepare(player, GameType.CREATIVE, new ItemStack(Items.IRON_PICKAXE));
			stand(helper, player, 3.5, 1.0, 5.5, 180, 0);
			helper.setBlock(new BlockPos(2, 2, 2), Blocks.COAL_ORE);
			helper.setBlock(new BlockPos(3, 3, 3), Blocks.DEEPSLATE_COAL_ORE);
			helper.setBlock(new BlockPos(4, 4, 4), Blocks.COAL_ORE);
			helper.setBlock(new BlockPos(3, 2, 2), Blocks.IRON_ORE);
			helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE);
			Digger.setSettings(player, new DigSettings(false, 3, 3, 1, true));

			breakAt(helper, player, new BlockPos(2, 2, 2), SIDE);

			helper.assertBlockNotPresent(Blocks.COAL_ORE, new BlockPos(2, 2, 2));
			helper.assertBlockNotPresent(Blocks.DEEPSLATE_COAL_ORE, new BlockPos(3, 3, 3));
			helper.assertBlockNotPresent(Blocks.COAL_ORE, new BlockPos(4, 4, 4));
			helper.assertBlockPresent(Blocks.IRON_ORE, new BlockPos(3, 2, 2));
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(2, 1, 2));
		});
		helper.succeed();
	}

	// 溶岩に触れている鉱石は残すが、その向こうのつながりは探し続ける
	@GameTest
	public void veinSkipsLavaButContinues(GameTestHelper helper) {
		withPlayer(helper, player -> {
			prepare(player, GameType.CREATIVE, new ItemStack(Items.IRON_PICKAXE));
			stand(helper, player, 3.5, 1.0, 5.5, 180, 0);
			helper.setBlock(new BlockPos(2, 2, 2), Blocks.COAL_ORE);
			helper.setBlock(new BlockPos(3, 3, 3), Blocks.COAL_ORE);
			helper.setBlock(new BlockPos(4, 4, 4), Blocks.COAL_ORE);
			helper.setBlock(new BlockPos(4, 3, 3), Blocks.LAVA);
			Digger.setSettings(player, new DigSettings(false, 3, 3, 1, true));

			breakAt(helper, player, new BlockPos(2, 2, 2), SIDE);

			helper.assertBlockPresent(Blocks.COAL_ORE, new BlockPos(3, 3, 3));
			helper.assertBlockNotPresent(Blocks.COAL_ORE, new BlockPos(4, 4, 4));
		});
		helper.succeed();
	}

	// 範囲採掘がオンなら、範囲に入った鉱石は範囲の外へ続く鉱脈も掘る。見えるだけの鉱石は残す
	@GameTest
	public void areaExpandsVeinForOresInside(GameTestHelper helper) {
		withPlayer(helper, player -> {
			prepare(player, GameType.CREATIVE, new ItemStack(Items.IRON_PICKAXE));
			stand(helper, player, 3.5, 1.0, 5.5, 180, 0);
			fill(helper, new BlockPos(2, 1, 3), new BlockPos(4, 3, 3), Blocks.STONE);
			helper.setBlock(new BlockPos(2, 2, 3), Blocks.COAL_ORE);
			helper.setBlock(new BlockPos(2, 2, 4), Blocks.COAL_ORE);
			helper.setBlock(new BlockPos(6, 1, 6), Blocks.COAL_ORE);
			Digger.setSettings(player, new DigSettings(true, 3, 3, 1, true));

			breakAt(helper, player, new BlockPos(3, 2, 3), SIDE);

			assertAll(helper, new BlockPos(2, 1, 3), new BlockPos(4, 3, 3), Blocks.AIR);
			helper.assertBlockNotPresent(Blocks.COAL_ORE, new BlockPos(2, 2, 4));
			helper.assertBlockPresent(Blocks.COAL_ORE, new BlockPos(6, 1, 6));
		});
		helper.succeed();
	}

	// 範囲採掘だけオンのときは、範囲の外の鉱石は狙うまで掘らない
	@GameTest
	public void areaOnlyDoesNotExpandVein(GameTestHelper helper) {
		withPlayer(helper, player -> {
			prepare(player, GameType.CREATIVE, new ItemStack(Items.IRON_PICKAXE));
			stand(helper, player, 3.5, 1.0, 5.5, 180, 0);
			fill(helper, new BlockPos(2, 1, 3), new BlockPos(4, 3, 3), Blocks.STONE);
			helper.setBlock(new BlockPos(2, 2, 3), Blocks.COAL_ORE);
			helper.setBlock(new BlockPos(2, 2, 4), Blocks.COAL_ORE);
			Digger.setSettings(player, new DigSettings(true, 3, 3, 1, false));

			breakAt(helper, player, new BlockPos(3, 2, 3), SIDE);

			assertAll(helper, new BlockPos(2, 1, 3), new BlockPos(4, 3, 3), Blocks.AIR);
			helper.assertBlockPresent(Blocks.COAL_ORE, new BlockPos(2, 2, 4));
		});
		helper.succeed();
	}

	// 出たアイテムと経験値は狙ったブロックの位置に集まり、同じアイテムは 1 つのスタックになる。
	// 狙いから水平に 3 以上離れたブロックを含め、回収していないと位置で落ちるようにする
	@GameTest
	public void collectsDropsAtTarget(GameTestHelper helper) {
		FakePlayer player = FakePlayer.get(helper.getLevel());
		PlayerState state = new PlayerState(player);
		try {
			prepare(player, GameType.SURVIVAL, new ItemStack(Items.IRON_PICKAXE));
			stand(helper, player, 3.5, 1.0, 7.5, 180, 0);
			// 7×7 の範囲。足元の段から 7 段、狙った 3 を中央にして 0〜6 まで広がる
			fill(helper, new BlockPos(0, 1, 3), new BlockPos(6, 7, 3), Blocks.STONE);
			helper.setBlock(new BlockPos(0, 2, 3), Blocks.COAL_ORE);
			Digger.setSettings(player, new DigSettings(true, 7, 7, 1, false));

			BlockPos target = helper.absolutePos(new BlockPos(3, 2, 3));
			breakAt(helper, player, new BlockPos(3, 2, 3), SIDE);
			// 拾われないよう、確かめるまでプレイヤーを離しておく
			stand(helper, player, 0.5, 20.0, 0.5, 180, 0);

			helper.runAfterDelay(2, () -> {
				try {
					ServerLevel level = helper.getLevel();
					AABB area = new AABB(target).inflate(8.0);
					List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, area);
					int cobblestone = 0;
					int coal = 0;
					int cobblestoneEntities = 0;
					for (ItemEntity item : items) {
						if (item.getItem().is(Items.COBBLESTONE)) {
							cobblestone += item.getItem().getCount();
							cobblestoneEntities++;
						} else if (item.getItem().is(Items.COAL)) {
							coal += item.getItem().getCount();
						}
						assertNearTarget(item.position(), target, "item " + item.getItem());
					}
					// 範囲の 49 個のうち、狙った石 1 + 範囲の石 47（残り 1 つは石炭鉱石）
					helper.assertValueEqual(cobblestone, 48, "cobblestone count");
					helper.assertValueEqual(coal, 1, "coal count");
					// 回収してまとめていないと、1 つずつ散らばって増える。狙った分とまとめた分で 2 以下
					if (cobblestoneEntities > 2) {
						throw new AssertionError("cobblestone should be merged into 2 stacks at most but was " + cobblestoneEntities);
					}

					for (ExperienceOrb orb : level.getEntitiesOfClass(ExperienceOrb.class, area)) {
						assertNearTarget(orb.position(), target, "experience orb");
					}
					helper.succeed();
				} finally {
					state.restore(player);
				}
			});
		} catch (Throwable throwable) {
			state.restore(player);
			throw throwable;
		}
	}

	// まとめて壊している間の外で増えたアイテムは、範囲のすぐそばでも動かさない
	@GameTest
	public void keepsUnrelatedItemsInSameTick(GameTestHelper helper) {
		withPlayer(helper, player -> {
			prepare(player, GameType.CREATIVE, new ItemStack(Items.IRON_PICKAXE));
			stand(helper, player, 3.5, 1.0, 7.5, 180, 0);
			fill(helper, new BlockPos(2, 1, 3), new BlockPos(4, 3, 3), Blocks.STONE);
			Digger.setSettings(player, new DigSettings(true, 3, 3, 1, false));

			ItemEntity before = helper.spawnItem(Items.DIAMOND, 5.5f, 2.5f, 3.5f);
			Vec3 beforePosition = before.position();
			breakAt(helper, player, new BlockPos(3, 2, 3), SIDE);
			ItemEntity after = helper.spawnItem(Items.DIAMOND, 1.5f, 2.5f, 3.5f);
			Vec3 afterPosition = after.position();

			helper.assertValueEqual(before.position(), beforePosition, "item spawned before the break");
			helper.assertValueEqual(after.position(), afterPosition, "item spawned after the break");
			helper.assertValueEqual(before.getItem().getCount(), 1, "item before the break stays");
			helper.assertValueEqual(after.getItem().getCount(), 1, "item after the break stays");
		});
		helper.succeed();
	}

	// 1 回の採掘で掘る鉱石は、狙ったブロックを含めて 256 個まで。残りは残る
	@GameTest
	public void stopsVeinAtTwoHundredFiftySix(GameTestHelper helper) {
		withPlayer(helper, player -> {
			prepare(player, GameType.CREATIVE, new ItemStack(Items.IRON_PICKAXE));
			stand(helper, player, 3.5, 1.0, 7.5, 180, 0);
			fill(helper, new BlockPos(0, 0, 0), new BlockPos(7, 7, 7), Blocks.COAL_ORE);
			Digger.setSettings(player, new DigSettings(false, 3, 3, 1, true));

			breakAt(helper, player, new BlockPos(0, 0, 0), SIDE);

			int air = 0;
			for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(0, 0, 0), new BlockPos(7, 7, 7))) {
				if (helper.getBlockState(pos).isAir()) {
					air++;
				}
			}
			helper.assertValueEqual(air, 256, "ore blocks broken in one dig");
			helper.assertBlockPresent(Blocks.COAL_ORE, new BlockPos(7, 7, 7));
		});
		helper.succeed();
	}

	// 設定を送ってこない人（クライアントに mod がない人）は両方オフ
	@GameTest
	public void breaksSingleBlockWithoutSettings(GameTestHelper helper) {
		withPlayer(helper, player -> {
			prepare(player, GameType.CREATIVE, new ItemStack(Items.IRON_PICKAXE));
			stand(helper, player, 3.5, 1.0, 5.5, 180, 0);
			fill(helper, new BlockPos(2, 1, 3), new BlockPos(4, 3, 3), Blocks.STONE);
			Digger.forget(player);

			breakAt(helper, player, new BlockPos(3, 2, 3), SIDE);

			helper.assertBlockNotPresent(Blocks.STONE, new BlockPos(3, 2, 3));
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(3, 1, 3));
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(4, 3, 3));
		});
		helper.succeed();
	}

	// Jori で無効にされている間は、設定がオンでも 1 つずつ
	@GameTest
	public void inactiveBreaksSingleBlock(GameTestHelper helper) {
		withPlayer(helper, player -> {
			prepare(player, GameType.CREATIVE, new ItemStack(Items.IRON_PICKAXE));
			stand(helper, player, 3.5, 1.0, 5.5, 180, 0);
			fill(helper, new BlockPos(2, 1, 3), new BlockPos(4, 3, 3), Blocks.STONE);
			Digger.setSettings(player, new DigSettings(true, 3, 3, 1, true));
			Digger.setActive(false);

			breakAt(helper, player, new BlockPos(3, 2, 3), SIDE);

			helper.assertBlockNotPresent(Blocks.STONE, new BlockPos(3, 2, 3));
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(3, 1, 3));
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(4, 3, 3));
		});
		helper.succeed();
	}

	// config/digger.txt は行ごとに読む。知らない行が混ざっていても値を読み、決まりの外の値は既定に戻す
	@GameTest
	public void configReadsAndWrites(GameTestHelper helper) {
		Path file = null;
		try {
			file = Files.createTempFile("digger", ".txt");
			Files.writeString(file, "# memo\nfuture_option=3\n area = true \nsize=5x5\ndepth=4\nvein=false\nbroken line\n");
			DigSettings read = DiggerConfig.load(file);
			helper.assertValueEqual(read, new DigSettings(true, 5, 5, 4, false), "settings among unknown lines");

			Files.writeString(file, "size=4x4\ndepth=9\nvein=maybe\n");
			helper.assertValueEqual(DiggerConfig.load(file), DigSettings.DEFAULT, "out of range values");

			DiggerConfig.save(file, new DigSettings(true, 5, 5, 5, false));
			helper.assertValueEqual(Files.readString(file), "area=true\nsize=5x5\ndepth=5\nvein=false\n", "saved file");
			helper.assertValueEqual(DiggerConfig.load(file.resolveSibling("missing-digger.txt")), DigSettings.DEFAULT, "missing file");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} finally {
			try {
				if (file != null) {
					Files.deleteIfExists(file);
				}
			} catch (IOException ignored) {
			}
		}
		helper.succeed();
	}

	// 鉱石一括が始まるときは範囲採掘もオンでも鉱脈だけ。範囲の外へ続く鉱石も掘り、まわりの石は残す
	@GameTest
	public void veinTakesPriorityOverArea(GameTestHelper helper) {
		withPlayer(helper, player -> {
			prepare(player, GameType.CREATIVE, new ItemStack(Items.IRON_PICKAXE));
			stand(helper, player, 3.5, 1.0, 5.5, 180, 0);
			fill(helper, new BlockPos(2, 1, 3), new BlockPos(4, 3, 3), Blocks.STONE);
			helper.setBlock(new BlockPos(3, 2, 3), Blocks.COAL_ORE);
			helper.setBlock(new BlockPos(3, 2, 4), Blocks.COAL_ORE);
			helper.setBlock(new BlockPos(2, 2, 5), Blocks.COAL_ORE);
			Digger.setSettings(player, new DigSettings(true, 3, 3, 1, true));

			breakAt(helper, player, new BlockPos(3, 2, 3), SIDE);

			helper.assertBlockNotPresent(Blocks.COAL_ORE, new BlockPos(3, 2, 4));
			helper.assertBlockNotPresent(Blocks.COAL_ORE, new BlockPos(2, 2, 5));
			// 範囲に入っていても、鉱石のまわりの石は残る
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(2, 2, 3));
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(4, 1, 3));
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(3, 3, 3));
		});
		helper.succeed();
	}

	// つながった鉱石がない 1 つだけの鉱石でも、狙ったら範囲採掘は始めない
	@GameTest
	public void isolatedOreDoesNotStartArea(GameTestHelper helper) {
		withPlayer(helper, player -> {
			prepare(player, GameType.CREATIVE, new ItemStack(Items.IRON_PICKAXE));
			stand(helper, player, 3.5, 1.0, 5.5, 180, 0);
			fill(helper, new BlockPos(2, 1, 3), new BlockPos(4, 3, 3), Blocks.STONE);
			helper.setBlock(new BlockPos(3, 2, 3), Blocks.COAL_ORE);
			Digger.setSettings(player, new DigSettings(true, 3, 3, 1, true));

			breakAt(helper, player, new BlockPos(3, 2, 3), SIDE);

			helper.assertBlockPresent(Blocks.STONE, new BlockPos(2, 2, 3));
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(4, 1, 3));
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(3, 1, 3));
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(3, 3, 3));
		});
		helper.succeed();
	}

	// 手に持った道具で掘れない鉱石は、鉱石一括も範囲採掘も始まらない（今の動きの固定）。
	// 木のつるはしでダイヤモンド鉱石を狙い、ほかに掘れる道具がないので 1 つだけ壊れる
	@GameTest
	public void unmineableOreBreaksSingleBlock(GameTestHelper helper) {
		withPlayer(helper, player -> {
			prepare(player, GameType.SURVIVAL, new ItemStack(Items.WOODEN_PICKAXE));
			stand(helper, player, 3.5, 1.0, 5.5, 180, 0);
			fill(helper, new BlockPos(2, 1, 3), new BlockPos(4, 3, 3), Blocks.STONE);
			helper.setBlock(new BlockPos(3, 2, 3), Blocks.DIAMOND_ORE);
			helper.setBlock(new BlockPos(3, 2, 4), Blocks.DIAMOND_ORE);
			Digger.setSettings(player, new DigSettings(true, 3, 3, 1, true));

			breakAt(helper, player, new BlockPos(3, 2, 3), SIDE);

			helper.assertBlockNotPresent(Blocks.DIAMOND_ORE, new BlockPos(3, 2, 3));
			helper.assertBlockPresent(Blocks.DIAMOND_ORE, new BlockPos(3, 2, 4));
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(2, 2, 3));
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(4, 3, 3));
		});
		helper.succeed();
	}

	// 範囲採掘で、石より遠い鉱石を先に壊す。耐久が足りなくなっても鉱石は残さない
	@GameTest
	public void breaksFartherOreBeforeNearerStone(GameTestHelper helper) {
		withPlayer(helper, player -> {
			prepare(player, GameType.SURVIVAL, new ItemStack(Items.IRON_PICKAXE));
			ItemStack pickaxe = player.getMainHandItem();
			pickaxe.setDamageValue(pickaxe.getMaxDamage() - 4);
			stand(helper, player, 3.5, 1.0, 5.5, 180, 0);
			fill(helper, new BlockPos(2, 1, 3), new BlockPos(4, 3, 3), Blocks.STONE);
			fill(helper, new BlockPos(2, 1, 2), new BlockPos(4, 3, 2), Blocks.STONE);
			helper.setBlock(new BlockPos(2, 2, 2), Blocks.COAL_ORE);
			Digger.setSettings(player, new DigSettings(true, 3, 3, 2, false));

			breakAt(helper, player, new BlockPos(3, 2, 3), SIDE);

			// 鉱石（狙いから 2 マス）を先に壊すので、鉱石より近い石は耐久が足りずに残る
			helper.assertBlockNotPresent(Blocks.COAL_ORE, new BlockPos(2, 2, 2));
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(2, 2, 3));
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(3, 3, 3));
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(3, 2, 2));
		});
		helper.succeed();
	}

	// スニーク中は範囲採掘をせず、狙った 1 つだけを壊す
	@GameTest
	public void sneakingBreaksSingleBlock(GameTestHelper helper) {
		withPlayer(helper, player -> {
			prepare(player, GameType.CREATIVE, new ItemStack(Items.IRON_PICKAXE));
			stand(helper, player, 3.5, 1.0, 5.5, 180, 0);
			fill(helper, new BlockPos(2, 1, 3), new BlockPos(4, 3, 3), Blocks.STONE);
			Digger.setSettings(player, new DigSettings(true, 3, 3, 1, false));
			player.setShiftKeyDown(true);

			breakAt(helper, player, new BlockPos(3, 2, 3), SIDE);

			helper.assertBlockNotPresent(Blocks.STONE, new BlockPos(3, 2, 3));
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(2, 2, 3));
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(4, 1, 3));
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(3, 3, 3));
		});
		helper.succeed();
	}

	// スニーク中も鉱石一括は働く
	@GameTest
	public void sneakingKeepsVeinMining(GameTestHelper helper) {
		withPlayer(helper, player -> {
			prepare(player, GameType.CREATIVE, new ItemStack(Items.IRON_PICKAXE));
			stand(helper, player, 3.5, 1.0, 5.5, 180, 0);
			fill(helper, new BlockPos(2, 1, 3), new BlockPos(4, 3, 3), Blocks.STONE);
			helper.setBlock(new BlockPos(3, 2, 3), Blocks.COAL_ORE);
			helper.setBlock(new BlockPos(3, 2, 4), Blocks.COAL_ORE);
			helper.setBlock(new BlockPos(2, 3, 4), Blocks.COAL_ORE);
			Digger.setSettings(player, new DigSettings(true, 3, 3, 1, true));
			player.setShiftKeyDown(true);

			breakAt(helper, player, new BlockPos(3, 2, 3), SIDE);

			helper.assertBlockNotPresent(Blocks.COAL_ORE, new BlockPos(3, 2, 4));
			helper.assertBlockNotPresent(Blocks.COAL_ORE, new BlockPos(2, 3, 4));
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(2, 2, 3));
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(4, 1, 3));
		});
		helper.succeed();
	}

	// 奥行きは、壊すブロックが 1 つもない層で止める。2 層目が空なら 3 層目は残る
	@GameTest
	public void depthStopsAtEmptyLayer(GameTestHelper helper) {
		withPlayer(helper, player -> {
			prepare(player, GameType.CREATIVE, new ItemStack(Items.IRON_PICKAXE));
			stand(helper, player, 3.5, 1.0, 5.5, 180, 0);
			fill(helper, new BlockPos(0, 0, 0), new BlockPos(7, 0, 7), Blocks.STONE);
			fill(helper, new BlockPos(2, 1, 3), new BlockPos(4, 3, 3), Blocks.STONE);
			helper.setBlock(new BlockPos(3, 2, 1), Blocks.STONE);
			Digger.setSettings(player, new DigSettings(true, 3, 3, 3, false));

			breakAt(helper, player, new BlockPos(3, 2, 3), SIDE);

			assertAll(helper, new BlockPos(2, 1, 3), new BlockPos(4, 3, 3), Blocks.AIR);
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(3, 2, 1));
		});
		helper.succeed();
	}

	// 2 層目に 1 つでも壊せるブロックがあれば、3 層目も壊す
	@GameTest
	public void depthContinuesWhenLayerHasBlock(GameTestHelper helper) {
		withPlayer(helper, player -> {
			prepare(player, GameType.CREATIVE, new ItemStack(Items.IRON_PICKAXE));
			stand(helper, player, 3.5, 1.0, 5.5, 180, 0);
			fill(helper, new BlockPos(0, 0, 0), new BlockPos(7, 0, 7), Blocks.STONE);
			fill(helper, new BlockPos(2, 1, 3), new BlockPos(4, 3, 3), Blocks.STONE);
			helper.setBlock(new BlockPos(3, 2, 2), Blocks.STONE);
			helper.setBlock(new BlockPos(3, 2, 1), Blocks.STONE);
			Digger.setSettings(player, new DigSettings(true, 3, 3, 3, false));

			breakAt(helper, player, new BlockPos(3, 2, 3), SIDE);

			helper.assertBlockNotPresent(Blocks.STONE, new BlockPos(3, 2, 2));
			helper.assertBlockNotPresent(Blocks.STONE, new BlockPos(3, 2, 1));
		});
		helper.succeed();
	}

	// 上の面でも、壊すブロックがない層より奥は壊さない
	@GameTest
	public void depthStopsAtEmptyLayerOnTopFace(GameTestHelper helper) {
		withPlayer(helper, player -> {
			prepare(player, GameType.CREATIVE, new ItemStack(Items.IRON_PICKAXE));
			stand(helper, player, 3.5, 6.0, 3.5, 180, 90);
			fill(helper, new BlockPos(2, 2, 2), new BlockPos(4, 2, 4), Blocks.STONE);
			helper.setBlock(new BlockPos(3, 0, 3), Blocks.STONE);
			Digger.setSettings(player, new DigSettings(true, 3, 3, 3, false));

			breakAt(helper, player, new BlockPos(3, 2, 3), Direction.UP);

			assertAll(helper, new BlockPos(2, 2, 2), new BlockPos(4, 2, 4), Blocks.AIR);
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(3, 0, 3));
		});
		helper.succeed();
	}

	// つるはしだけでも、シャベルで掘る土・砂利は壊す。正しい道具が要る積もった雪は残す
	@GameTest
	public void pickaxeBreaksShovelBlocks(GameTestHelper helper) {
		withPlayer(helper, player -> {
			prepare(player, GameType.SURVIVAL, new ItemStack(Items.IRON_PICKAXE));
			stand(helper, player, 3.5, 1.0, 5.5, 180, 0);
			fill(helper, new BlockPos(0, 0, 0), new BlockPos(7, 0, 7), Blocks.STONE);
			fill(helper, new BlockPos(2, 1, 3), new BlockPos(4, 3, 3), Blocks.STONE);
			helper.setBlock(new BlockPos(2, 2, 3), Blocks.DIRT);
			helper.setBlock(new BlockPos(2, 1, 3), Blocks.GRAVEL);
			helper.setBlock(new BlockPos(4, 1, 3), Blocks.SNOW);
			Digger.setSettings(player, new DigSettings(true, 3, 3, 1, false));

			breakAt(helper, player, new BlockPos(3, 2, 3), SIDE);

			helper.assertBlockNotPresent(Blocks.DIRT, new BlockPos(2, 2, 3));
			helper.assertBlockNotPresent(Blocks.GRAVEL, new BlockPos(2, 1, 3));
			helper.assertBlockPresent(Blocks.SNOW, new BlockPos(4, 1, 3));
		});
		helper.succeed();
	}

	// ホットバーにシャベルがあるときは、シャベルで土・砂利を壊し、耐久はシャベルだけ減る
	@GameTest
	public void usesShovelDurabilityForShovelBlocks(GameTestHelper helper) {
		withPlayer(helper, player -> {
			prepare(player, GameType.SURVIVAL, new ItemStack(Items.IRON_PICKAXE));
			player.getInventory().setItem(1, new ItemStack(Items.IRON_SHOVEL));
			stand(helper, player, 3.5, 1.0, 5.5, 180, 0);
			helper.setBlock(new BlockPos(3, 2, 3), Blocks.STONE);
			helper.setBlock(new BlockPos(2, 2, 3), Blocks.DIRT);
			helper.setBlock(new BlockPos(4, 2, 3), Blocks.GRAVEL);
			Digger.setSettings(player, new DigSettings(true, 3, 3, 1, false));

			breakAt(helper, player, new BlockPos(3, 2, 3), SIDE);

			helper.assertBlockNotPresent(Blocks.DIRT, new BlockPos(2, 2, 3));
			helper.assertBlockNotPresent(Blocks.GRAVEL, new BlockPos(4, 2, 3));
			helper.assertValueEqual(player.getInventory().getItem(1).getDamageValue(), 2, "shovel damage");
			helper.assertValueEqual(player.getMainHandItem().getDamageValue(), 1, "pickaxe damage");
		});
		helper.succeed();
	}

	// シャベルの耐久が足りないときは、つるはしで土・砂利を壊す
	@GameTest
	public void usesPickaxeWhenShovelIsWorn(GameTestHelper helper) {
		withPlayer(helper, player -> {
			prepare(player, GameType.SURVIVAL, new ItemStack(Items.IRON_PICKAXE));
			ItemStack shovel = new ItemStack(Items.IRON_SHOVEL);
			shovel.setDamageValue(shovel.getMaxDamage() - 2);
			player.getInventory().setItem(1, shovel);
			stand(helper, player, 3.5, 1.0, 5.5, 180, 0);
			helper.setBlock(new BlockPos(3, 2, 3), Blocks.STONE);
			helper.setBlock(new BlockPos(2, 2, 3), Blocks.DIRT);
			Digger.setSettings(player, new DigSettings(true, 3, 3, 1, false));

			breakAt(helper, player, new BlockPos(3, 2, 3), SIDE);

			helper.assertBlockNotPresent(Blocks.DIRT, new BlockPos(2, 2, 3));
			helper.assertValueEqual(
					player.getInventory().getItem(1).getDamageValue(), shovel.getMaxDamage() - 2, "worn shovel stays");
			helper.assertValueEqual(player.getMainHandItem().getDamageValue(), 2, "pickaxe damage");
		});
		helper.succeed();
	}

	// 掘っている途中でシャベルの耐久が足りなくなったら、残りの土はつるはしで壊す
	@GameTest
	public void switchesToPickaxeWhenShovelWearsOut(GameTestHelper helper) {
		withPlayer(helper, player -> {
			prepare(player, GameType.SURVIVAL, new ItemStack(Items.IRON_PICKAXE));
			ItemStack shovel = new ItemStack(Items.IRON_SHOVEL);
			shovel.setDamageValue(shovel.getMaxDamage() - 4);
			player.getInventory().setItem(1, shovel);
			stand(helper, player, 3.5, 1.0, 5.5, 180, 0);
			fill(helper, new BlockPos(2, 1, 3), new BlockPos(4, 3, 3), Blocks.DIRT);
			helper.setBlock(new BlockPos(3, 2, 3), Blocks.STONE);
			Digger.setSettings(player, new DigSettings(true, 3, 3, 1, false));

			breakAt(helper, player, new BlockPos(3, 2, 3), SIDE);

			assertAll(helper, new BlockPos(2, 1, 3), new BlockPos(4, 3, 3), Blocks.AIR);
			helper.assertValueEqual(
					player.getInventory().getItem(1).getDamageValue(), shovel.getMaxDamage() - 2, "worn shovel is kept");
			// 土 8 個のうち 6 個と、狙った石
			helper.assertValueEqual(player.getMainHandItem().getDamageValue(), 7, "pickaxe damage");
		});
		helper.succeed();
	}

	private static void prepare(FakePlayer player, GameType mode, ItemStack held) {
		player.setGameMode(mode);
		for (int slot = 0; slot < 9; slot++) {
			player.getInventory().setItem(slot, ItemStack.EMPTY);
		}
		player.getInventory().setItem(0, held);
		player.getInventory().setSelectedSlot(0);
	}

	private static void stand(
			GameTestHelper helper, FakePlayer player, double x, double y, double z, float yaw, float pitch) {
		BlockPos origin = helper.absolutePos(BlockPos.ZERO);
		player.teleportTo(helper.getLevel(), origin.getX() + x, origin.getY() + y, origin.getZ() + z,
				Set.of(), yaw, pitch, true);
	}

	private static void breakAt(GameTestHelper helper, FakePlayer player, BlockPos relativePos, Direction face) {
		((BreakDirectionAccess) player.gameMode).digger$setBreakDirection(face);
		player.gameMode.destroyBlock(helper.absolutePos(relativePos));
	}

	private static void fill(GameTestHelper helper, BlockPos from, BlockPos to, Block block) {
		for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
			helper.setBlock(pos, block);
		}
	}

	private static void assertAll(GameTestHelper helper, BlockPos from, BlockPos to, Block block) {
		for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
			helper.assertBlockPresent(block, pos);
		}
	}

	private static void assertNearTarget(Vec3 pos, BlockPos target, String description) {
		double dx = pos.x - (target.getX() + 0.5);
		double dz = pos.z - (target.getZ() + 0.5);
		if (dx * dx + dz * dz > 1.5 * 1.5) {
			throw new AssertionError(description + " should be at " + target + " but was " + pos);
		}
	}

	private static void withPlayer(GameTestHelper helper, java.util.function.Consumer<FakePlayer> action) {
		FakePlayer player = FakePlayer.get(helper.getLevel());
		PlayerState state = new PlayerState(player);
		Digger.forget(player);
		try {
			action.accept(player);
		} finally {
			state.restore(player);
		}
	}

	// FakePlayer は使い回されるので、持ち物・位置・向き・ゲームモード・設定・有効無効を必ず戻す
	private static final class PlayerState {
		private final GameType mode;
		private final List<ItemStack> items;
		private final int slot;
		private final Vec3 position;
		private final float yaw;
		private final float pitch;
		private final boolean shiftKeyDown;
		private final boolean active;

		private PlayerState(FakePlayer player) {
			this.mode = player.gameMode.getGameModeForPlayer();
			this.items = new ArrayList<>();
			for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
				this.items.add(player.getInventory().getItem(slot).copy());
			}
			this.slot = player.getInventory().getSelectedSlot();
			this.position = player.position();
			this.yaw = player.getYRot();
			this.pitch = player.getXRot();
			this.shiftKeyDown = player.isShiftKeyDown();
			this.active = Digger.isActive();
		}

		private void restore(FakePlayer player) {
			player.setGameMode(mode);
			for (int slot = 0; slot < items.size(); slot++) {
				player.getInventory().setItem(slot, items.get(slot));
			}
			player.getInventory().setSelectedSlot(slot);
			player.teleportTo(player.level(), position.x, position.y, position.z, Set.of(), yaw, pitch, true);
			player.setShiftKeyDown(shiftKeyDown);
			Digger.forget(player);
			Digger.setActive(active);
		}
	}
}
