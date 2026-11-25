package com.omuraisu.minimap.test;

import static com.omuraisu.minimap.test.MapTests.*;
import static com.omuraisu.testkit.Asserts.assertEquals;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.DeflaterOutputStream;

import com.omuraisu.minimap.TerrainRegions;
import com.omuraisu.minimap.WorldId;
import com.omuraisu.minimap.client.MinimapClient;
import com.omuraisu.minimap.client.Pins;
import com.omuraisu.testkit.Scenario;
import com.omuraisu.testkit.TestServers;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

// 遠くの地形の保存先をワールドごとに分ける。同じアドレス（localhost）で別のワールドのサーバーに入っても、
// 前のワールドの地図が出ないことと、前の版が作ったワールドを分けないファイルを消すことを確かめる
public class WorldChangeTest implements Scenario {
	// 前の版のファイルに入れる、実際の地形ではありえない色（MapColor の packed id は 8 ビットに収まる範囲）
	private static final byte LEGACY_COLOR = (byte) 0xFF;
	// FarCache の MAGIC と、1 チャンク分のバイト数（色 256 + 北の端 32 + 南の端 32）
	private static final int LEGACY_MAGIC = 0x4D4D4632;
	private static final int LEGACY_TILE_BYTES = 320;

	@Override
	public String name() {
		return "world-change";
	}

	@Override
	public void run(ClientGameTestContext context) {
		resetConfig(context);
		testWorldId();
		testWorldChange(context);
	}

	// 同じファイルなら同じ ID、違うファイルなら違う ID。壊れた中身は作り直して、次からは同じ ID
	private static void testWorldId() {
		Path directory;
		try {
			directory = Files.createTempDirectory("minimap-world-id");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		try {
			Path file = directory.resolve("minimap_world_id.txt");
			UUID first = WorldId.load(file);
			assertEquals("world id from the same file", first, WorldId.load(file));
			if (WorldId.load(directory.resolve("other.txt")).equals(first)) {
				throw new AssertionError("different files should have different world ids");
			}
			for (String broken : List.of("", "1-1-1-1-1", "not a uuid")) {
				Files.writeString(file, broken);
				UUID created = WorldId.load(file);
				assertEquals("world id rewritten from " + (broken.isEmpty() ? "an empty file" : broken), created, WorldId.load(file));
				assertEquals("saved world id", created.toString(), Files.readString(file).strip());
			}
			// 文字として読めない中身
			Files.write(file, new byte[] {(byte) 0xFF, (byte) 0xFE, 0});
			UUID created = WorldId.load(file);
			assertEquals("world id rewritten from bytes that are not text", created, WorldId.load(file));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} finally {
			deleteRecursively(directory);
		}
	}

	private static void testWorldChange(ClientGameTestContext context) {
		int port = freePort();
		String address = "localhost:" + port;
		Path gameDirectory = context.computeOnClient(client -> FabricLoader.getInstance().getGameDir());
		Path addressFolder = gameDirectory.resolve("minimap_far").resolve(encodeAddress(address));
		// 前の実行の残りを消す。シングルプレイのセーブには触らない
		deleteRecursively(addressFolder);

		int renderDistance = context.computeOnClient(client -> client.options.getEffectiveRenderDistance());
		ChunkPos far = null;
		int regionX = 0;
		int regionZ = 0;
		byte[] colorsA = null;
		String idA = null;
		Path fileA = null;

		try (TestDedicatedServerContext serverA = TestServers.create(context, properties(port, "minimap-world-change-a"))) {
			far = farChunk(serverA, renderDistance);
			regionX = TerrainRegions.coordinate(far.x());
			regionZ = TerrainRegions.coordinate(far.z());
			// 描画距離の外・地図の範囲の中のチャンクを、全部白いコンクリートにする
			ChunkPos marked = far;
			serverA.runOnServer(minecraft -> {
				ServerLevel level = minecraft.overworld();
				fill(level, new BlockPos(marked.getMinBlockX(), 100, marked.getMinBlockZ()),
						new BlockPos(marked.getMaxBlockX(), 100, marked.getMaxBlockZ()), Blocks.CONCRETE.white());
			});
			idA = readWorldId(serverA);

			// 前の版の形のファイルと、消してはいけないファイルを、接続する前に置いておく
			Path legacy = regionFile(addressFolder, regionX, regionZ);
			byte[] legacyColors = new byte[16 * 16];
			Arrays.fill(legacyColors, LEGACY_COLOR);
			writeLegacy(legacy, far);
			Path note = legacy.getParent().resolve("note.txt");
			writeString(note, "keep\n");

			try (TestDedicatedServerConnection connection = serverA.connect()) {
				colorsA = waitForLegacySafeColor(context, far, legacyColors);
				assertEquals("world A id", idA, readWorldId(serverA));
				waitForFile(context, legacy, false);
				if (!Files.exists(note)) {
					throw new AssertionError("files that are not far terrain should be kept: " + note);
				}
			}
			Path folderA = addressFolder.resolve(idA);
			fileA = regionFile(folderA, regionX, regionZ);
			waitForFile(context, fileA, true);

			// 入り直すと、A のフォルダーに残した色から出す
			final ChunkPos target = far;
			final byte[] expected = colorsA;
			int receivedBefore = context.computeOnClient(client -> MinimapClient.FAR_TERRAIN.received());
			try (TestDedicatedServerConnection connection = serverA.connect()) {
				// 入り直してから届いた数より多く覚えていれば、ファイルから読んでいる
				context.waitFor(client -> MinimapClient.FAR_TERRAIN.count(OVERWORLD) > MinimapClient.FAR_TERRAIN.received() - receivedBefore, 400);
				context.waitFor(client -> {
					byte[] colors = MinimapClient.FAR_TERRAIN.colors(OVERWORLD, target.x(), target.z());
					return colors != null && Arrays.equals(colors, expected);
				}, 2400);
				int stamp = context.computeOnClient(client -> MinimapClient.FAR_TERRAIN.stamp(OVERWORLD, target.x(), target.z()));
				if (stamp == 0) {
					throw new AssertionError("the color from world A should be remembered with its version");
				}
			}
		}

		// 同じポートで、別のワールドのサーバーを立てる。ワールドの ID が別なことを先に確かめる
		final ChunkPos marked = far;
		final byte[] colorsOfA = colorsA;
		try (TestDedicatedServerContext serverB = TestServers.create(context, properties(port, "minimap-world-change-b"))) {
			String idB = readWorldId(serverB);
			if (idB.equals(idA)) {
				throw new AssertionError("the two worlds should have different ids: " + idB);
			}
			Path fileB = regionFile(addressFolder.resolve(idB), regionX, regionZ);
			try (TestDedicatedServerConnection connection = serverB.connect()) {
				int arrivedAt = -1;
				for (int tick = 0; tick < 2400; tick++) {
					byte[] colors = context.computeOnClient(client -> MinimapClient.FAR_TERRAIN.colors(OVERWORLD, marked.x(), marked.z()));
					if (colors != null) {
						if (Arrays.equals(colors, colorsOfA)) {
							throw new AssertionError("world B showed the far terrain of world A for " + marked);
						}
						if (arrivedAt < 0) {
							arrivedAt = tick;
						}
					}
					// 接続してすぐから少なくとも 100 tick、届いた後も 60 tick は毎 tick 比べる
					if (arrivedAt >= 0 && tick - arrivedAt >= 60 && tick >= 100) {
						break;
					}
					context.waitTick();
				}
				if (arrivedAt < 0) {
					throw new AssertionError("far terrain of world B should arrive");
				}

				connection.waitForChunksRender();
				toggleMap(context);
				moveCursorAway(context);
				context.waitTicks(MAP_REFRESH_TICKS);
				context.takeScreenshot("world-change-map");
				toggleMap(context);
			}
			// マップ画面を閉じたときに書く。B のフォルダーが A と別にでき、A のファイルは残る
			waitForFile(context, fileB, true);
			if (!Files.exists(fileA)) {
				throw new AssertionError("world A's far terrain should stay: " + fileA);
			}
		}
	}

	private static Properties properties(int port, String levelName) {
		Properties properties = new Properties();
		properties.setProperty("server-port", String.valueOf(port));
		// 同じゲームのフォルダーでも別のワールドにする（テストのサーバーは level-name のフォルダーを作り直す）
		properties.setProperty("level-name", levelName);
		return properties;
	}

	private static int freePort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static ChunkPos farChunk(TestDedicatedServerContext server, int renderDistance) {
		BlockPos spawn = server.computeOnServer(minecraft -> minecraft.overworld().getRespawnData().pos());
		return new ChunkPos((spawn.getX() >> 4) + renderDistance + 8, spawn.getZ() >> 4);
	}

	private static String readWorldId(TestDedicatedServerContext server) {
		Path file = server.computeOnServer(minecraft -> minecraft.getWorldPath(LevelResource.ROOT).resolve("minimap_world_id.txt"));
		try {
			String id = Files.readString(file).strip();
			if (!UUID.fromString(id).toString().equals(id)) {
				throw new AssertionError("world id should be written in its canonical form: " + id);
			}
			return id;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	// Pins と同じアドレスのファイル名にする
	private static String encodeAddress(String address) {
		String name = Pins.serverFile(address).getFileName().toString();
		return name.substring(0, name.length() - ".txt".length());
	}

	private static Path regionFile(Path root, int regionX, int regionZ) {
		return root.resolve("minecraft").resolve("overworld").resolve("r." + regionX + "." + regionZ + ".bin");
	}

	// FarCache.write と同じ形。チャンク 1 つ分の色を、実際の地形ではありえない値にする
	private static void writeLegacy(Path file, ChunkPos chunk) {
		try {
			Files.createDirectories(file.getParent());
			try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
					new DeflaterOutputStream(Files.newOutputStream(file))))) {
				out.writeInt(LEGACY_MAGIC);
				out.writeInt(LEGACY_TILE_BYTES);
				out.writeInt(1);
				out.writeShort(TerrainRegions.index(chunk.x(), chunk.z()));
				out.writeInt(1);
				byte[] data = new byte[LEGACY_TILE_BYTES];
				Arrays.fill(data, 0, 16 * 16, LEGACY_COLOR);
				out.write(data);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	// A の白いコンクリートの色が届くまで、毎 tick、前の版の色が出ないことを確かめる
	private static byte[] waitForLegacySafeColor(ClientGameTestContext context, ChunkPos chunk, byte[] legacyColors) {
		int white = Blocks.CONCRETE.white().defaultMapColor().id;
		for (int tick = 0; tick < 2400; tick++) {
			byte[] colors = context.computeOnClient(client -> MinimapClient.FAR_TERRAIN.colors(OVERWORLD, chunk.x(), chunk.z()));
			if (colors != null) {
				if (Arrays.equals(colors, legacyColors)) {
					throw new AssertionError("the far terrain of an older version should never be shown for " + chunk);
				}
				if (Byte.toUnsignedInt(colors[8 * 16 + 8]) >> 2 == white) {
					context.waitTicks(15);
					return colors;
				}
			}
			context.waitTick();
		}
		throw new AssertionError("the color of world A should arrive for " + chunk);
	}

	private static void waitForFile(ClientGameTestContext context, Path file, boolean exists) {
		for (int tick = 0; tick < 400; tick++) {
			if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) == exists) {
				return;
			}
			context.waitTick();
		}
		throw new AssertionError(file + (exists ? " should be created" : " should be deleted"));
	}

	private static void writeString(Path file, String text) {
		try {
			Files.writeString(file, text);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void deleteRecursively(Path path) {
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try (Stream<Path> paths = Files.walk(path)) {
			for (Path entry : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(entry);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
