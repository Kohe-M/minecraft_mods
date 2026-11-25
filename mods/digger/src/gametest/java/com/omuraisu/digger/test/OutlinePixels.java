package com.omuraisu.digger.test;

import java.nio.file.Path;

import com.omuraisu.testkit.Screenshots;

import net.minecraft.util.ARGB;

// 枠線の水色（0xD04FD8E0）を石の壁に重ねた画素を数える。空の青や草の緑と区別するため、
// 赤より緑と青が強く、緑と青が近い色だけを拾う。照準のある中央は避ける
final class OutlinePixels {
	private OutlinePixels() {
	}

	static int count(Path path) {
		return Screenshots.read(path, image -> {
			int centerX = image.getWidth() / 2;
			int centerY = image.getHeight() / 2;
			int count = 0;
			for (int y = 0; y < image.getHeight(); y++) {
				for (int x = 0; x < image.getWidth(); x++) {
					if (Math.abs(x - centerX) < 50 && Math.abs(y - centerY) < 50) {
						continue;
					}
					int pixel = image.getPixel(x, y);
					int red = ARGB.red(pixel);
					int green = ARGB.green(pixel);
					int blue = ARGB.blue(pixel);
					if (green - red > 40 && blue - red > 40 && blue - green < 30) {
						count++;
					}
				}
			}
			return count;
		});
	}
}
