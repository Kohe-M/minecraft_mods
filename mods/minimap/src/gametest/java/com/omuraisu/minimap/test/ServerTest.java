package com.omuraisu.minimap.test;

import static com.omuraisu.minimap.test.MapTests.*;
import static com.omuraisu.testkit.Asserts.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.mojang.authlib.GameProfile;
import com.omuraisu.minimap.Minimap;
import com.omuraisu.minimap.Pin;
import com.omuraisu.minimap.PinEdit;
import com.omuraisu.minimap.PinServer;
import com.omuraisu.minimap.client.MinimapClient;
import com.omuraisu.minimap.client.Pins;
import com.omuraisu.testkit.Scenario;
import com.omuraisu.testkit.Screenshots;
import com.omuraisu.testkit.TestServers;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;

// 専用サーバー: ピンの共有と移行・他のプレイヤー・遠くの地形・ピンを共有しないサーバー
public class ServerTest implements Scenario {
	@Override
	public String name() {
		return "server";
	}

	@Override
	public void run(ClientGameTestContext context) {
		resetConfig(context);
		testDedicatedServer(context);
		testServerWithoutPins(context);
	}

	// 専用サーバーに接続しても動く。ピンはサーバーのワールドに保存してサーバーにいる人で共有し、他のプレイヤーは通信で届く
	private static void testDedicatedServer(ClientGameTestContext context) {
		try {
			// 前回の実行のピンが残らないようにする
			Path pinDirectory = Pins.serverFile("x").getParent();
			if (Files.isDirectory(pinDirectory)) {
				try (Stream<Path> files = Files.list(pinDirectory)) {
					for (Path file : files.toList()) {
						Files.delete(file);
					}
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		try (TestDedicatedServerContext server = TestServers.create(context)) {
			Path serverFile = server.computeOnServer(minecraft -> minecraft.getWorldPath(LevelResource.ROOT).resolve("minimap_pins.txt"));
			Path localFile;
			int[] feet;
			Pin pin;
			Pin named;
			try (TestDedicatedServerConnection connection = server.connect()) {
				connection.waitForChunksRender();
				context.waitTicks(40);
				context.runOnClient(client -> client.gui.toastManager().clear());
				context.waitTicks(3);
				frame(context, "server-minimap", 854, 480);

				feet = context.computeOnClient(client -> new int[] {client.player.getBlockX(), client.player.getBlockZ()});
				toggleMap(context);
				moveCursorAway(context);
				context.waitTicks(MAP_REFRESH_TICKS);
				ScreenView view = new ScreenView(854, 480, 2, feet[0], feet[1]);

				// 刺したピンはサーバーのワールドに保存され、手元には残さない
				pin = new Pin(OVERWORLD, feet[0] + 10, feet[1] - 8);
				placeAt(context, view.x(pin.x()), view.y(pin.z()));
				confirmName(context);
				assertPins(OVERWORLD, pin);
				context.waitTicks(5);
				assertFileEquals(serverFile, pin.line() + "\n");
				localFile = context.computeOnClient(client -> Pins.serverFile(client.getCurrentServer().ip));
				if (Files.exists(localFile)) {
					throw new AssertionError("pins on a server with minimap should not be saved to " + localFile);
				}

				// 名前も残る
				named = new Pin(OVERWORLD, feet[0] + 30, feet[1] + 20, "Camp");
				placeAt(context, view.x(named.x()), view.y(named.z()));
				context.getInput().typeChars(named.name());
				confirmName(context);
				assertPins(OVERWORLD, pin, named);
				context.takeScreenshot("server-map");

				// サーバーにも入っていれば、描画距離の外も出る
				int renderDistance = context.computeOnClient(client -> client.options.getEffectiveRenderDistance());
				ChunkPos far = new ChunkPos((feet[0] >> 4) + renderDistance + 8, feet[1] >> 4);
				zoomAtCenter(context, -3);
				waitForFarTerrain(context, far);
				ScreenView farView = new ScreenView(854, 480, 0.25, feet[0] + 0.5, feet[1] + 0.5);
				int farPixel = Screenshots.read(context.takeScreenshot("server-far-terrain"),
						image -> image.getPixel(farView.x(far.getMiddleBlockX()), farView.y(far.getMiddleBlockZ())));
				if (farPixel == EMPTY_COLOR) {
					throw new AssertionError("far terrain from the dedicated server should be drawn");
				}
				zoomAtCenter(context, 3);
				toggleMap(context);
				assertFileEquals(serverFile, pin.line() + "\n" + named.line() + "\n");

				testSharedPins(context, server, pin, named);
				testServerPlayers(context, server, feet[0], feet[1]);
			}

			// 切断するとピンは閉じる。サーバーに mod を入れる前に手元に刺していたピンは、接続すると共有に移る
			assertPins(OVERWORLD);
			Pin legacy = new Pin(OVERWORLD, feet[0] - 30, feet[1] - 25, "Old");
			try {
				Files.createDirectories(localFile.getParent());
				Files.writeString(localFile, legacy.line() + "\n" + new Pin(OVERWORLD, pin.x(), pin.z(), "Duplicate").line() + "\n");
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			try (TestDedicatedServerConnection connection = server.connect()) {
				connection.waitForChunksRender();
				context.waitTicks(5);
				assertPins(OVERWORLD, pin, named, legacy);
				assertFileEquals(serverFile, pin.line() + "\n" + named.line() + "\n" + legacy.line() + "\n");
				if (Files.exists(localFile)) {
					throw new AssertionError("local pins should be moved to the server and " + localFile + " deleted");
				}
			}

		}
	}

	// 同じサーバーの他の人が刺したり抜いたりしたピンも届く。他の人の操作は FakePlayer の変更として送る
	private static void testSharedPins(ClientGameTestContext context, TestDedicatedServerContext server, Pin pin, Pin named) {
		GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes("minimap-pin-test".getBytes(StandardCharsets.UTF_8)), "Alex");
		Pin other = new Pin(OVERWORLD, pin.x() - 25, pin.z() + 5, "Shared");
		server.runOnServer(minecraft -> PinServer.edit(FakePlayer.get(minecraft.overworld(), profile), new PinEdit(PinEdit.Action.PLACE, other)));
		context.waitTicks(5);
		assertPins(OVERWORLD, pin, named, other);
		map(context, "server-shared-pin", 854, 480);

		Pin renamed = new Pin(OVERWORLD, other.x(), other.z(), "Shared2");
		server.runOnServer(minecraft -> PinServer.edit(FakePlayer.get(minecraft.overworld(), profile), new PinEdit(PinEdit.Action.RENAME, renamed)));
		context.waitTicks(5);
		assertPins(OVERWORLD, pin, named, renamed);

		server.runOnServer(minecraft -> PinServer.edit(FakePlayer.get(minecraft.overworld(), profile), new PinEdit(PinEdit.Action.REMOVE, other)));
		context.waitTicks(5);
		assertPins(OVERWORLD, pin, named);
	}

	// ピンを共有しないサーバー（minimap を無効にした専用サーバー）では、サーバーごとの手元のファイルに保存する
	private static void testServerWithoutPins(ClientGameTestContext context) {
		context.runOnClient(client -> Minimap.setActive(false));
		try (TestDedicatedServerContext server = TestServers.create(context)) {
			try (TestDedicatedServerConnection connection = server.connect()) {
				connection.waitForChunksRender();
				// 受け口は接続したときに決まるので、有効に戻しても共有しない
				context.runOnClient(client -> Minimap.setActive(true));
				context.waitTicks(20);
				if (context.computeOnClient(client -> MinimapClient.PINS.isShared())) {
					throw new AssertionError("pins should not be shared with a server that does not accept them");
				}
				int[] feet = context.computeOnClient(client -> new int[] {client.player.getBlockX(), client.player.getBlockZ()});
				toggleMap(context);
				moveCursorAway(context);
				context.waitTicks(MAP_REFRESH_TICKS);
				ScreenView view = new ScreenView(854, 480, 2, feet[0], feet[1]);
				Pin pin = new Pin(OVERWORLD, feet[0] + 10, feet[1] - 8);
				placeAt(context, view.x(pin.x()), view.y(pin.z()));
				confirmName(context);
				toggleMap(context);
				assertPins(OVERWORLD, pin);
				Path file = context.computeOnClient(client -> Pins.serverFile(client.getCurrentServer().ip));
				assertFileEquals(file, pin.line() + "\n");
				Path serverFile = server.computeOnServer(minecraft -> minecraft.getWorldPath(LevelResource.ROOT).resolve("minimap_pins.txt"));
				if (Files.exists(serverFile)) {
					throw new AssertionError("a server that does not share pins should not save them");
				}
			}
		} finally {
			context.runOnClient(client -> Minimap.setActive(true));
		}
	}

	private static void testServerPlayers(ClientGameTestContext context, TestDedicatedServerContext server, int x, int z) {
		int dx = 20;
		int dz = -12;
		int edge = 80 - 5;
		Map before = map(context, "server-player-before", 854, 480);

		GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes("minimap-server-test".getBytes(StandardCharsets.UTF_8)), "Steve");
		double y = context.computeOnClient(client -> client.player.getY());
		FakePlayer other = server.computeOnServer(minecraft -> {
			ServerLevel level = minecraft.overworld();
			FakePlayer fake = FakePlayer.get(level, profile);
			fake.setPos(x + dx + 0.5, y, z + dz + 0.5);
			minecraft.getPlayerList().broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(fake)));
			level.addNewPlayer(fake);
			return fake;
		});
		context.waitTicks(20);
		assertEquals("players known to the client", 2, (int) context.computeOnClient(client -> client.level.players().size()));
		if (map(context, "server-player", 854, 480).pixelRelative(dx, dz) == before.pixelRelative(dx, dz)) {
			throw new AssertionError("nearby player should be drawn on the minimap");
		}

		// エンティティが届かない距離の相手は、ロケーターバーのパケットで方角が分かる
		server.runOnServer(minecraft -> {
			ServerLevel level = minecraft.overworld();
			other.setPos(x + 5000.5, y, z + 0.5);
			other.baseTick();
			level.getWaypointManager().trackWaypoint(other);
			level.getWaypointManager().updatePlayer(minecraft.getPlayerList().getPlayers().getFirst());
		});
		context.waitTicks(20);
		assertEquals("far player should not be a tracked entity", 1, (int) context.computeOnClient(client -> client.level.players().size()));
		if (map(context, "server-player-far", 854, 480).pixelRelative(edge, 0) == before.pixelRelative(edge, 0)) {
			throw new AssertionError("far player should be drawn at the east edge of the minimap");
		}

		server.runOnServer(minecraft -> {
			minecraft.overworld().getWaypointManager().untrackWaypoint(other);
			minecraft.overworld().removePlayerImmediately(other, Entity.RemovalReason.DISCARDED);
			minecraft.getPlayerList().broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(profile.id())));
		});
		context.waitTicks(10);
		if (map(context, "server-player-left", 854, 480).pixelRelative(edge, 0) != before.pixelRelative(edge, 0)) {
			throw new AssertionError("player who left should disappear from the minimap");
		}
	}
}
