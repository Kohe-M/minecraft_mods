package com.omuraisu.minimap;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.nbt.TagType;
import net.minecraft.nbt.visitors.CollectToTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

// リージョンファイル（.mca）を、vanilla の IO スレッドを通さずに自前のスレッドから読む。どのスレッドから同時に呼んでもよい。
// vanilla が同時に書いていることがあるので、読めないもの・別のチャンクの中身・別の版のデータは「読めなかった」として返し、
// 呼ぶ側が vanilla の経路で読み直す。展開と読み解きは呼んだスレッドで行い、色に要る項目だけを読む
final class RegionReader implements AutoCloseable {
	private static final int SECTOR_BYTES = 4096;
	private static final int EXTERNAL_STREAM_FLAG = 128;
	// 開いたままにしておくリージョンファイルの数。超えたらまとめて閉じる
	private static final int MAX_OPEN = 256;
	private static final Set<String> TOP_FIELDS = Set.of("DataVersion", "xPos", "zPos", "Status", "sections");
	private static final Set<String> SECTION_FIELDS = Set.of("Y", "block_states");

	private final Path folder;
	private final ConcurrentHashMap<Long, FileChannel> channels = new ConcurrentHashMap<>();
	private volatile boolean closed;

	RegionReader(Path folder) {
		this.folder = folder;
	}

	// 生成済みのチャンクの中身。セーブになければ ABSENT、ここでは読めなければ null
	CompoundTag read(ChunkPos pos) {
		try {
			FileChannel channel = channel(pos.getRegionX(), pos.getRegionZ());
			if (channel == null) {
				return ABSENT;
			}
			ByteBuffer header = ByteBuffer.allocate(4);
			if (!readFully(channel, header, (long) (pos.getRegionLocalX() + pos.getRegionLocalZ() * 32) * 4)) {
				return null;
			}
			int offset = header.getInt(0);
			if (offset == 0) {
				return ABSENT;
			}
			int sector = offset >>> 8 & 0xFFFFFF;
			int sectors = offset & 0xFF;
			if (sector < 2 || sectors == 0) {
				return null;
			}
			ByteBuffer data = ByteBuffer.allocate(sectors * SECTOR_BYTES);
			if (!readFully(channel, data, (long) sector * SECTOR_BYTES)) {
				return null;
			}
			int length = data.getInt(0);
			byte version = data.get(4);
			if (length <= 1 || length - 1 > data.capacity() - 5 || (version & EXTERNAL_STREAM_FLAG) != 0 || !RegionFileVersion.isValidVersion(version)) {
				return null;
			}
			CompoundTag tag = parse(RegionFileVersion.fromId(version), data.array(), length - 1);
			// 読んでいる間に vanilla が書き換えた場所を読むと、別のチャンクの中身になることがある
			if (tag == null || tag.getIntOr("xPos", Integer.MIN_VALUE) != pos.x() || tag.getIntOr("zPos", Integer.MIN_VALUE) != pos.z()
					|| tag.getIntOr("DataVersion", -1) != SharedConstants.getCurrentVersion().dataVersion().version()) {
				return null;
			}
			return tag;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	@Override
	public void close() {
		closed = true;
		closeChannels();
	}

	private static CompoundTag parse(RegionFileVersion version, byte[] data, int length) throws IOException {
		TerrainFields visitor = new TerrainFields();
		try (DataInputStream in = new DataInputStream(version.wrap(new ByteArrayInputStream(data, 5, length)))) {
			NbtIo.parse(in, visitor, NbtAccounter.unlimitedHeap());
		}
		return visitor.getResult() instanceof CompoundTag tag ? tag : null;
	}

	private static boolean readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
		while (buffer.hasRemaining()) {
			int read = channel.read(buffer, position + buffer.position());
			if (read < 0) {
				return false;
			}
		}
		return true;
	}

	// まだないリージョンは開かない（あとで作られることがあるので、ないことは覚えない）
	private FileChannel channel(int regionX, int regionZ) throws IOException {
		if (closed) {
			throw new IOException("closed");
		}
		long key = ChunkPos.pack(regionX, regionZ);
		FileChannel channel = channels.get(key);
		if (channel != null) {
			return channel;
		}
		Path path = folder.resolve("r." + regionX + "." + regionZ + ".mca");
		if (!Files.isRegularFile(path)) {
			return null;
		}
		if (channels.size() >= MAX_OPEN) {
			closeChannels();
		}
		try {
			return channels.computeIfAbsent(key, k -> {
				try {
					return FileChannel.open(path, StandardOpenOption.READ);
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			});
		} catch (UncheckedIOException e) {
			if (e.getCause() instanceof NoSuchFileException) {
				return null;
			}
			throw e.getCause();
		}
	}

	private void closeChannels() {
		for (Long key : channels.keySet()) {
			FileChannel channel = channels.remove(key);
			if (channel != null) {
				try {
					channel.close();
				} catch (IOException e) {
					// 閉じられなくても読むのには困らない
				}
			}
		}
	}

	// セーブにないことを表す（null は「ここでは読めなかった」）
	static final CompoundTag ABSENT = new CompoundTag();

	// 一番上の DataVersion・xPos・zPos・Status と、sections の各要素の Y・block_states だけを読む。
	// 光やバイオーム、エンティティは読み飛ばして作らない
	private static final class TerrainFields extends CollectToTag {
		@Override
		public StreamTagVisitor.EntryResult visitEntry(TagType<?> type, String name) {
			int depth = depth();
			if (depth == 1 && !TOP_FIELDS.contains(name) || depth == 3 && !SECTION_FIELDS.contains(name)) {
				return StreamTagVisitor.EntryResult.SKIP;
			}
			return super.visitEntry(type, name);
		}
	}
}
