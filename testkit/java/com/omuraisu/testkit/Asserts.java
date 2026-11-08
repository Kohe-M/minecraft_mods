package com.omuraisu.testkit;

import java.util.Objects;

// 失敗したときに、何を比べていたかが分かるように名前を付ける
public final class Asserts {
	private Asserts() {
	}

	public static void assertEquals(String name, Object expected, Object actual) {
		if (!Objects.equals(expected, actual)) {
			throw new AssertionError(name + ": expected " + expected + " but was " + actual);
		}
	}
}
