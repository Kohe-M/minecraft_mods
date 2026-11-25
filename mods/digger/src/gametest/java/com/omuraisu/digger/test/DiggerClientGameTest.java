package com.omuraisu.digger.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Properties;

import com.mojang.blaze3d.platform.InputConstants;
import com.omuraisu.digger.DigSettings;
import com.omuraisu.digger.Digger;
import com.omuraisu.digger.DiggerConfig;
import com.omuraisu.digger.SettingsPayload;
import com.omuraisu.digger.client.DiggerClient;
import com.omuraisu.testkit.Scenario;
import com.omuraisu.testkit.TestServers;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

public class DiggerClientGameTest implements Scenario {
	private ClientGameTestContext context;
	private TestSingleplayerContext singleplayer;
	private TestScene scene;

	@Override
	public String name() {
		return "digger";
	}

	@Override
	public void run(ClientGameTestContext context) {
		this.context = context;
		// 前回の実行の設定を残さない
		resetConfig();
		context.runOnClient(client -> Digger.setActive(true));
		try {
			try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
				this.singleplayer = singleplayer;
				this.scene = TestScene.build(context, singleplayer);

				testKeysAndConfig();
				testAreaOutline();
				testVeinOutline();
				testInactiveOutline();
				testMining();
			}
			testUnsupportedServer();
		} finally {
			context.runOnClient(client -> Digger.setActive(true));
			set(DigSettings.DEFAULT);
		}
	}

	// 実際のキーとホイールで切り替えと大きさ・奥行きが変わり、設定ファイルに残る
	private void testKeysAndConfig() {
		set(DigSettings.DEFAULT);
		if (!DiggerClient.settings().equals(DigSettings.DEFAULT)) {
			throw new AssertionError("initial settings should be " + DigSettings.DEFAULT + " but were " + DiggerClient.settings());
		}

		press(DiggerClient.areaKey);
		assertSettings(new DigSettings(true, 3, 3, 1, true));

		int slot = selectedSlot();
		holdAreaScroll(1, false);
		assertSettings(new DigSettings(true, 5, 5, 1, true));
		if (selectedSlot() != slot) {
			throw new AssertionError("hotbar slot should not change while the area key is held");
		}

		holdAreaScroll(1, true);
		assertSettings(new DigSettings(true, 5, 5, 2, true));

		// 端で止まり、一周しない
		holdAreaScroll(10, false);
		assertSettings(new DigSettings(true, 9, 9, 2, true));
		holdAreaScroll(1, false);
		assertSettings(new DigSettings(true, 9, 9, 2, true));
		holdAreaScroll(-10, false);
		assertSettings(new DigSettings(true, 1, 2, 2, true));
		holdAreaScroll(-1, false);
		assertSettings(new DigSettings(true, 1, 2, 2, true));

		holdAreaScroll(1, true);
		holdAreaScroll(1, true);
		holdAreaScroll(1, true);
		holdAreaScroll(1, true);
		holdAreaScroll(1, true);
		assertSettings(new DigSettings(true, 1, 2, 5, true));

		press(DiggerClient.veinKey);
		assertSettings(new DigSettings(true, 1, 2, 5, false));
		press(DiggerClient.veinKey);
		assertSettings(new DigSettings(true, 1, 2, 5, true));
		assertConfig(DiggerClient.settings());

		// 行ごとに読み、知らない行が混ざっていても値を読める。決まりの外の値は既定に戻す
		writeConfig("# memo\nfuture_option=3\n area = true \nsize=5x5\ndepth=4\nvein=false\nbroken line\n");
		context.runOnClient(client -> DiggerClient.load());
		assertSettings(new DigSettings(true, 5, 5, 4, false));
		writeConfig("size=4x4\ndepth=9\nvein=maybe\n");
		context.runOnClient(client -> DiggerClient.load());
		assertSettings(DigSettings.DEFAULT);
	}

	// 範囲採掘の枠線を、オンとオフの両方で画素から確かめる
	private void testAreaOutline() {
		set(new DigSettings(true, 3, 3, 1, false));
		aim(scene.areaTarget);
		context.waitTicks(5);
		int on = OutlinePixels.count(context.takeScreenshot("digger-outline-area-on"));

		set(new DigSettings(false, 3, 3, 1, false));
		context.waitTicks(5);
		int off = OutlinePixels.count(context.takeScreenshot("digger-outline-area-off"));

		if (on < 50 || off > 20) {
			throw new AssertionError("area outline should be visible on and gone off but was " + on + " on and " + off + " off");
		}
	}

	// 鉱石一括だけのときも枠線を出す
	private void testVeinOutline() {
		set(new DigSettings(false, 3, 3, 1, true));
		aim(scene.coalTarget);
		context.waitTicks(5);
		context.runOnClient(client -> {
			if (!(client.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit)
					|| !client.level.getBlockState(hit.getBlockPos()).is(Blocks.COAL_ORE)) {
				throw new AssertionError("vein aim: target " + scene.coalTarget
						+ " hit " + (client.hitResult instanceof net.minecraft.world.phys.BlockHitResult blockHit
								? client.level.getBlockState(blockHit.getBlockPos()) + " at " + blockHit.getBlockPos()
								: client.hitResult)
						+ " player " + client.player.position() + " eye " + client.player.getEyePosition());
			}
		});
		int on = OutlinePixels.count(context.takeScreenshot("digger-outline-vein-on"));

		set(new DigSettings(false, 3, 3, 1, false));
		context.waitTicks(5);
		int off = OutlinePixels.count(context.takeScreenshot("digger-outline-vein-off"));

		if (on < 30 || off > 20) {
			throw new AssertionError("vein outline should be visible on and gone off but was " + on + " on and " + off + " off");
		}
	}

	// Jori で無効の間は枠線も出さない
	private void testInactiveOutline() {
		set(new DigSettings(true, 3, 3, 1, false));
		aim(scene.areaTarget);
		context.waitTicks(5);
		context.runOnClient(client -> Digger.setActive(false));
		try {
			context.waitTicks(5);
			int off = OutlinePixels.count(context.takeScreenshot("digger-outline-inactive"));
			if (off > 20) {
				throw new AssertionError("inactive should not draw an outline but " + off + " pixels were drawn");
			}
		} finally {
			context.runOnClient(client -> Digger.setActive(true));
			context.waitTicks(3);
		}
	}

	// 実際に左クリックを長押しして、サーバーのブロックが範囲どおりに消える
	private void testMining() {
		set(new DigSettings(true, 3, 3, 1, false));
		aim(scene.areaTarget);
		context.waitTicks(5);

		context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(40);
		context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(5);

		List<BlockPos> broken = singleplayer.getServer().computeOnServer(server -> {
			var level = server.overworld();
			var found = new java.util.ArrayList<BlockPos>();
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = 0; dy <= 2; dy++) {
					BlockPos pos = new BlockPos(scene.x + dx, scene.feetY + dy, scene.wallZ);
					if (level.getBlockState(pos).isAir()) {
						found.add(pos);
					}
				}
			}
			return found;
		});
		if (broken.size() != 9) {
			throw new AssertionError("area mining should break 9 blocks but broke " + broken.size() + ": " + broken);
		}
		singleplayer.getServer().runOnServer(server -> {
			var level = server.overworld();
			if (!level.getBlockState(new BlockPos(scene.x - 2, scene.feetY + 1, scene.wallZ)).is(Blocks.STONE)) {
				throw new AssertionError("area mining should not reach two blocks left");
			}
			if (!level.getBlockState(new BlockPos(scene.x, scene.feetY + 3, scene.wallZ)).is(Blocks.STONE)) {
				throw new AssertionError("area mining should not reach one block above");
			}
		});
	}

	// サーバーが受け付けないときは、キーを押しても切り替えず、使えないことを出す
	private void testUnsupportedServer() {
		context.runOnClient(client -> Digger.setActive(false));
		Properties properties = new Properties();
		properties.setProperty("spawn-protection", "0");
		try (TestDedicatedServerContext server = TestServers.create(context, properties);
				TestDedicatedServerConnection connection = server.connect()) {
			connection.waitForChunksRender();
			context.runOnClient(client -> Digger.setActive(true));
			context.waitTicks(3);

			boolean canSend = context.computeOnClient(client -> ClientPlayNetworking.canSend(SettingsPayload.TYPE));
			if (canSend) {
				throw new AssertionError("a server without the receiver should not accept digger settings");
			}

			DigSettings before = DiggerClient.settings();
			press(DiggerClient.areaKey);
			press(DiggerClient.veinKey);
			if (!before.equals(DiggerClient.settings())) {
				throw new AssertionError("keys should not change settings when the server does not accept them");
			}
		} finally {
			context.runOnClient(client -> Digger.setActive(true));
		}
	}

	private void holdAreaScroll(int steps, boolean shift) {
		context.getInput().holdKey(DiggerClient.areaKey);
		if (shift) {
			context.getInput().holdShift();
		}
		context.waitTick();
		context.getInput().scroll(steps);
		context.waitTick();
		if (shift) {
			context.getInput().releaseShift();
		}
		context.getInput().releaseKey(DiggerClient.areaKey);
		context.waitTicks(3);
	}

	private void press(net.minecraft.client.KeyMapping key) {
		context.getInput().pressKey(key);
		context.waitTicks(3);
	}

	private void aim(BlockPos target) {
		context.getInput().lookAt(target);
		context.waitTicks(3);
	}

	private int selectedSlot() {
		return context.computeOnClient(client -> client.player.getInventory().getSelectedSlot());
	}

	private void assertSettings(DigSettings expected) {
		context.waitTicks(2);
		if (!DiggerClient.settings().equals(expected)) {
			throw new AssertionError("settings should be " + expected + " but were " + DiggerClient.settings());
		}
	}

	private void assertConfig(DigSettings expected) {
		try {
			String text = Files.readString(DiggerClient.CONFIG);
			if (!text.equals("area=" + expected.area() + "\nsize=" + expected.width() + "x" + expected.height()
					+ "\ndepth=" + expected.depth() + "\nvein=" + expected.vein() + "\n")) {
				throw new AssertionError("config should contain " + expected + " but was " + text);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private void writeConfig(String text) {
		try {
			Files.writeString(DiggerClient.CONFIG, text);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private void set(DigSettings settings) {
		DiggerConfig.save(DiggerClient.CONFIG, settings);
		context.runOnClient(client -> DiggerClient.load());
		context.waitTicks(3);
	}

	private void resetConfig() {
		try {
			Files.deleteIfExists(DiggerClient.CONFIG);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		context.runOnClient(client -> DiggerClient.load());
		context.waitTicks(2);
	}
}
