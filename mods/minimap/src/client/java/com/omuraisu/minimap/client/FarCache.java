package com.omuraisu.minimap.client;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import com.mojang.logging.LogUtils;

import org.slf4j.Logger;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntSet;

import net.minecraft.resources.Identifier;

// 届いた遠くの地形を、ワールド（シングルプレイ）やサーバーごとにファイルへ残す。32 × 32 チャンクで 1 ファイル。
// 読み書きは 1 本のスレッドで順に行うので、書いた直後に読んでも書いた中身が返る
final class FarCache {
	private static final int MAGIC = 0x4D4D4631;
	private static final Logger LOGGER = LogUtils.getLogger();

	private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Minimap Cache");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});

	// 残さないときは null
	private Path root;

	void open(Path root) {
		this.root = root;
	}

	void close() {
		root = null;
	}

	boolean isOpen() {
		return root != null;
	}

	// リージョンのチャンク（リージョンの中の番号 → データ）。ファイルがなければ空
	CompletableFuture<Int2ObjectMap<byte[]>> load(Identifier dimension, int regionX, int regionZ, int tileBytes) {
		if (root == null) {
			return CompletableFuture.completedFuture(new Int2ObjectOpenHashMap<>());
		}
		Path file = file(root, dimension, regionX, regionZ);
		return CompletableFuture.supplyAsync(() -> read(file, tileBytes), io);
	}

	// ファイルにある分に updates を重ね、removed を消して書き直す。メモリにはリージョンの一部しかないことがあるので、ファイルと合わせる
	void save(Identifier dimension, int regionX, int regionZ, int tileBytes, Int2ObjectMap<byte[]> updates, IntSet removed) {
		if (root == null) {
			return;
		}
		Path file = file(root, dimension, regionX, regionZ);
		io.execute(() -> {
			Int2ObjectMap<byte[]> tiles = read(file, tileBytes);
			removed.forEach(tiles::remove);
			tiles.putAll(updates);
			write(file, tiles, tileBytes);
		});
	}

	private static Path file(Path root, Identifier dimension, int regionX, int regionZ) {
		return root.resolve(dimension.getNamespace()).resolve(dimension.getPath()).resolve("r." + regionX + "." + regionZ + ".bin");
	}

	// 壊れていたら空として扱う（次に書くときに作り直す）
	private static Int2ObjectMap<byte[]> read(Path file, int tileBytes) {
		Int2ObjectMap<byte[]> tiles = new Int2ObjectOpenHashMap<>();
		if (!Files.isRegularFile(file)) {
			return tiles;
		}
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new InflaterInputStream(Files.newInputStream(file))))) {
			if (in.readInt() != MAGIC || in.readInt() != tileBytes) {
				return tiles;
			}
			int count = in.readInt();
			for (int i = 0; i < count; i++) {
				int index = in.readUnsignedShort();
				byte[] data = new byte[tileBytes];
				in.readFully(data);
				tiles.put(index, data);
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Failed to read far terrain cache {}", file, e);
			tiles.clear();
		}
		return tiles;
	}

	// 書きかけで落ちても前のファイルが残るよう、別のファイルに書いてから置き換える
	private static void write(Path file, Int2ObjectMap<byte[]> tiles, int tileBytes) {
		try {
			if (tiles.isEmpty()) {
				Files.deleteIfExists(file);
				return;
			}
			Files.createDirectories(file.getParent());
			Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
			try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new DeflaterOutputStream(Files.newOutputStream(temporary))))) {
				out.writeInt(MAGIC);
				out.writeInt(tileBytes);
				out.writeInt(tiles.size());
				for (Int2ObjectMap.Entry<byte[]> entry : tiles.int2ObjectEntrySet()) {
					out.writeShort(entry.getIntKey());
					out.write(entry.getValue());
				}
			}
			Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			LOGGER.warn("Failed to write far terrain cache {}", file, e);
		}
	}
}
