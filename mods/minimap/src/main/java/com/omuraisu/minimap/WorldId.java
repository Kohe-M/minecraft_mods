package com.omuraisu.minimap;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

import com.mojang.logging.LogUtils;

import org.slf4j.Logger;

// ワールドごとの識別子。セーブのルートに残し、同じワールドなら何度入っても同じ ID にする。
// クライアントはこの ID で遠くの地形のファイルを分ける（同じアドレスの別のワールドと混ざらないようにするため）
public final class WorldId {
	private static final Logger LOGGER = LogUtils.getLogger();

	private WorldId() {
	}

	public static UUID load(Path file) {
		String text;
		try {
			text = Files.readString(file).strip();
		} catch (NoSuchFileException | CharacterCodingException e) {
			// まだない。または、文字として読めない壊れた中身
			return create(file);
		} catch (IOException e) {
			// 読めないときはファイルを上書きしない。その回だけの ID を使う
			LOGGER.warn("Failed to read world id {}: {}", file, e.toString());
			return UUID.randomUUID();
		}
		UUID id = parse(text);
		if (id != null) {
			return id;
		}
		// 空や、UUID として読めない中身も作り直す
		return create(file);
	}

	private static UUID create(Path file) {
		UUID id = UUID.randomUUID();
		try {
			TextFile.replace(file, id + "\n");
		} catch (IOException e) {
			// 書けなくてもその回だけは ID を使える。黙って捨てずにログに出す
			LOGGER.warn("Failed to save world id {}: {}", file, e.toString());
		}
		return id;
	}

	// 正規の形の UUID でなければ null。短い形（1-1-1-1-1）も UUID.fromString は読めてしまうので確かめる
	public static UUID parse(String text) {
		try {
			UUID id = UUID.fromString(text);
			return id.toString().equals(text.toLowerCase(Locale.ROOT)) ? id : null;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
