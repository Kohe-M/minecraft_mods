package com.omuraisu.digger;

import org.jspecify.annotations.Nullable;

// 範囲採掘の大きさの段階。幅 × 高さ。1×2 はブランチマイニング用で、ほかは奇数にして狙ったブロックを中央に置く。
// ホイールではこの並びを端で止めて動かし、一周させない
public enum AreaSize {
	ONE_BY_TWO("1x2", 1, 2),
	THREE_BY_THREE("3x3", 3, 3),
	FIVE_BY_FIVE("5x5", 5, 5),
	SEVEN_BY_SEVEN("7x7", 7, 7),
	NINE_BY_NINE("9x9", 9, 9);

	private final String id;
	private final int width;
	private final int height;

	AreaSize(String id, int width, int height) {
		this.id = id;
		this.width = width;
		this.height = height;
	}

	public String id() {
		return id;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	public AreaSize step(int steps) {
		int next = Math.clamp(ordinal() + steps, 0, values().length - 1);
		return values()[next];
	}

	public static @Nullable AreaSize byId(String id) {
		for (AreaSize size : values()) {
			if (size.id.equals(id)) {
				return size;
			}
		}
		return null;
	}

	public static AreaSize of(int width, int height) {
		for (AreaSize size : values()) {
			if (size.width == width && size.height == height) {
				return size;
			}
		}
		// 設定ファイルと通信は段階のどれかに揃えてある。念のため既定に寄せる
		return THREE_BY_THREE;
	}
}
