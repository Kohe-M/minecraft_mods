package com.omuraisu.testkit;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

import com.mojang.blaze3d.platform.NativeImage;

// takeScreenshot で撮った画像の画素を読む
public final class Screenshots {
	private Screenshots() {
	}

	public static <T> T read(Path path, Function<NativeImage, T> reader) {
		try (InputStream in = Files.newInputStream(path); NativeImage image = NativeImage.read(in)) {
			return reader.apply(image);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
