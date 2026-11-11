package com.omuraisu.timber.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.omuraisu.timber.Timber;
import com.omuraisu.timber.TimberConfig;

import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

public class TimberGameTest {
	private static final List<BlockPos> TRUNK = List.of(
			new BlockPos(3, 1, 3), new BlockPos(3, 2, 3), new BlockPos(3, 3, 3),
			new BlockPos(3, 4, 3), new BlockPos(4, 5, 4), new BlockPos(2, 5, 3));

	@GameTest
	public void fellsWholeTree(GameTestHelper helper) {
		placeTrunk(helper);
		helper.setBlock(new BlockPos(4, 6, 4), Blocks.OAK_LEAVES);
		ItemStack axe = chop(helper, true, new ItemStack(Items.IRON_AXE));

		TRUNK.forEach(pos -> helper.assertBlockNotPresent(Blocks.OAK_LOG, pos));
		helper.assertValueEqual(axe.getDamageValue(), TRUNK.size(), "axe damage");
		helper.succeed();
	}

	@GameTest
	public void keepsBuildingsWithoutLeaves(GameTestHelper helper) {
		placeTrunk(helper);
		chop(helper, true, new ItemStack(Items.IRON_AXE));

		assertOnlyFirstLogBroken(helper);
		helper.succeed();
	}

	@GameTest
	public void disabledBreaksSingleLog(GameTestHelper helper) {
		placeTrunk(helper);
		helper.setBlock(new BlockPos(4, 6, 4), Blocks.OAK_LEAVES);
		chop(helper, false, new ItemStack(Items.IRON_AXE));

		assertOnlyFirstLogBroken(helper);
		helper.succeed();
	}

	// Jori で無効にされている間は、一括伐採を ON にしている人でも 1 つずつ
	@GameTest
	public void inactiveBreaksSingleLog(GameTestHelper helper) {
		placeTrunk(helper);
		helper.setBlock(new BlockPos(4, 6, 4), Blocks.OAK_LEAVES);
		Timber.setActive(false);
		try {
			chop(helper, true, new ItemStack(Items.IRON_AXE));
		} finally {
			Timber.setActive(true);
		}

		assertOnlyFirstLogBroken(helper);
		helper.succeed();
	}

	@GameTest
	public void sneakingStillFells(GameTestHelper helper) {
		placeTrunk(helper);
		helper.setBlock(new BlockPos(4, 6, 4), Blocks.OAK_LEAVES);
		FakePlayer.get(helper.getLevel()).setShiftKeyDown(true);
		chop(helper, true, new ItemStack(Items.IRON_AXE));
		FakePlayer.get(helper.getLevel()).setShiftKeyDown(false);

		TRUNK.forEach(pos -> helper.assertBlockNotPresent(Blocks.OAK_LOG, pos));
		helper.succeed();
	}

	@GameTest
	public void stopsBeforeAxeBreaks(GameTestHelper helper) {
		placeTrunk(helper);
		helper.setBlock(new BlockPos(4, 6, 4), Blocks.OAK_LEAVES);
		ItemStack axe = new ItemStack(Items.WOODEN_AXE);
		axe.setDamageValue(axe.getMaxDamage() - 3);
		axe = chop(helper, true, axe);

		helper.assertValueEqual(axe.getMaxDamage() - axe.getDamageValue(), 1, "remaining durability");
		helper.assertBlockPresent(Blocks.OAK_LOG, TRUNK.getLast());
		helper.succeed();
	}

	// クライアントの config/timber.txt は行ごとに読む。知らない行が混ざっていても enabled を読め、書いていなければオン。
	// 読み書きは main の TimberConfig にあるので、サーバーのゲームテストから一時ファイルで確かめる
	@GameTest
	public void configIgnoresUnknownLines(GameTestHelper helper) {
		Path file = null;
		try {
			file = Files.createTempFile("timber", ".txt");
			Files.writeString(file, "# memo\nfuture_option=3\n enabled = false \nbroken line\n");
			helper.assertValueEqual(TimberConfig.loadEnabled(file), false, "enabled=false among unknown lines");
			Files.writeString(file, "future_option=3\n");
			helper.assertValueEqual(TimberConfig.loadEnabled(file), true, "enabled without the line");
			TimberConfig.saveEnabled(file, false);
			helper.assertValueEqual(Files.readString(file), "enabled=false\n", "saved file");
			helper.assertValueEqual(TimberConfig.loadEnabled(file.resolveSibling("missing-timber.txt")), true, "enabled without the file");
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

	private static void placeTrunk(GameTestHelper helper) {
		TRUNK.forEach(pos -> helper.setBlock(pos, Blocks.OAK_LOG));
	}

	private static void assertOnlyFirstLogBroken(GameTestHelper helper) {
		helper.assertBlockNotPresent(Blocks.OAK_LOG, TRUNK.getFirst());
		TRUNK.subList(1, TRUNK.size()).forEach(pos -> helper.assertBlockPresent(Blocks.OAK_LOG, pos));
	}

	private static ItemStack chop(GameTestHelper helper, boolean enabled, ItemStack axe) {
		FakePlayer player = FakePlayer.get(helper.getLevel());
		player.setGameMode(GameType.SURVIVAL);
		player.setItemInHand(InteractionHand.MAIN_HAND, axe);
		Timber.setEnabled(player, enabled);
		player.gameMode.destroyBlock(helper.absolutePos(TRUNK.getFirst()));
		Timber.setEnabled(player, true);
		return player.getMainHandItem();
	}
}
