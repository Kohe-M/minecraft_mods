package com.omuraisu.minimap.client;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import com.mojang.logging.LogUtils;
import com.omuraisu.minimap.TerrainRegions;
import com.omuraisu.minimap.TerrainTile;
import com.omuraisu.minimap.WorldId;

import org.slf4j.Logger;

import net.minecraft.resources.Identifier;

// 届いた遠くの地形を、ワールドごとにファイルへ残す。32 × 32 チャンクで 1 ファイル。
// 色と一緒に版（TerrainTile.stamp）も残し、次に入ったときは版が変わったチャンクだけを送ってもらう。
// 読み書きは 1 本のスレッドで順に行うので、書いた直後に読んでも書いた中身が返る
final class FarCache {
	// 版のない古い形。色はそのまま使い、版は分からないものとして送り直してもらう
	private static final int MAGIC_UNSTAMPED = 0x4D4D4631;
	private static final int MAGIC = 0x4D4D4632;
	// 前の版が作った、ワールドを分けないファイルの名前
	private static final Pattern LEGACY_REGION = Pattern.compile("r\\.-?\\d+\\.-?\\d+\\.bin(?:\\.tmp)?");
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

	// リージョンのチャンク。ファイルがなければ空
	CompletableFuture<Tiles> load(Identifier dimension, int regionX, int regionZ, int tileBytes) {
		if (root == null) {
			return CompletableFuture.completedFuture(new Tiles());
		}
		Path file = file(root, dimension, regionX, regionZ);
		return CompletableFuture.supplyAsync(() -> read(file, tileBytes), io);
	}

	// リージョンをまるごと書き直す。1 つもなければファイルを消す。tiles は書き終えるまで変えないこと
	void save(Identifier dimension, int regionX, int regionZ, int tileBytes, Tiles tiles) {
		if (root == null) {
			return;
		}
		Path file = file(root, dimension, regionX, regionZ);
		io.execute(() -> write(file, tiles, tileBytes));
	}

	// 前の版が作った、ワールドを分けないファイルを消す。アドレスのフォルダーの直下の、名前が UUID でないフォルダー
	// （次元の名前空間。minecraft など）の中の、リージョンのファイルだけを消す。どのワールドの色か分からないので引き継がない。
	// シンボリックリンクはたどらない。失敗しても遊ぶのには困らないので、ログに出すだけ
	void deleteLegacy(Path address) {
		io.execute(() -> {
			try {
				deleteLegacyFiles(address);
			} catch (IOException | RuntimeException e) {
				LOGGER.warn("Failed to delete the far terrain cache of an older version {}", address, e);
			}
		});
	}

	private static void deleteLegacyFiles(Path address) throws IOException {
		if (!Files.isDirectory(address, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		List<Path> folders;
		try (Stream<Path> entries = Files.list(address)) {
			folders = entries
					.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
					.filter(path -> WorldId.parse(path.getFileName().toString()) == null)
					.toList();
		}
		for (Path folder : folders) {
			Files.walkFileTree(folder, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
					if (attributes.isRegularFile() && LEGACY_REGION.matcher(file.getFileName().toString()).matches()) {
						Files.deleteIfExists(file);
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, IOException failure) {
					LOGGER.warn("Failed to clean far terrain cache {}", file, failure);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
					try {
						Files.delete(directory);
					} catch (DirectoryNotEmptyException ignored) {
						// ほかのファイルがあるので残す
					}
					return FileVisitResult.CONTINUE;
				}
			});
		}
	}

	private static Path file(Path root, Identifier dimension, int regionX, int regionZ) {
		return root.resolve(dimension.getNamespace()).resolve(dimension.getPath()).resolve("r." + regionX + "." + regionZ + ".bin");
	}

	// 壊れていたら空として扱う（次に書くときに作り直す）
	private static Tiles read(Path file, int tileBytes) {
		Tiles tiles = new Tiles();
		if (!Files.isRegularFile(file)) {
			return tiles;
		}
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new InflaterInputStream(Files.newInputStream(file))))) {
			int magic = in.readInt();
			if (magic != MAGIC && magic != MAGIC_UNSTAMPED || in.readInt() != tileBytes) {
				return tiles;
			}
			int count = in.readInt();
			for (int i = 0; i < count; i++) {
				int index = in.readUnsignedShort();
				int stamp = magic == MAGIC ? in.readInt() : TerrainTile.STALE;
				byte[] data = new byte[tileBytes];
				in.readFully(data);
				if (index < TerrainRegions.COUNT && stamp != 0) {
					tiles.data[index] = data;
					tiles.stamps[index] = stamp;
				}
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Failed to read far terrain cache {}", file, e);
			return new Tiles();
		}
		return tiles;
	}

	// 書きかけで落ちても前のファイルが残るよう、別のファイルに書いてから置き換える
	private static void write(Path file, Tiles tiles, int tileBytes) {
		try {
			int count = 0;
			for (byte[] data : tiles.data) {
				if (data != null) {
					count++;
				}
			}
			if (count == 0) {
				Files.deleteIfExists(file);
				return;
			}
			Files.createDirectories(file.getParent());
			Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
			try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new DeflaterOutputStream(Files.newOutputStream(temporary))))) {
				out.writeInt(MAGIC);
				out.writeInt(tileBytes);
				out.writeInt(count);
				for (int index = 0; index < tiles.data.length; index++) {
					if (tiles.data[index] != null) {
						out.writeShort(index);
						out.writeInt(tiles.stamps[index]);
						out.write(tiles.data[index]);
					}
				}
			}
			Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			LOGGER.warn("Failed to write far terrain cache {}", file, e);
		}
	}

	// リージョン 1 つ分の色と版。リージョンの中の番号（TerrainRegions.index）で並べ、色のないチャンクは null と 0
	static final class Tiles {
		final byte[][] data = new byte[TerrainRegions.COUNT][];
		final int[] stamps = new int[TerrainRegions.COUNT];
	}
}
