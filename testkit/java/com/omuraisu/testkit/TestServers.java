package com.omuraisu.testkit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;

// クライアントのゲームテストで専用サーバーを立てる。サーバーはクライアントと同じ JVM で動く
public final class TestServers {
	private TestServers() {
	}

	public static TestDedicatedServerContext create(ClientGameTestContext context) {
		return create(context, new Properties());
	}

	// properties には server.properties に足す値を入れる。ポートは空いているものを選ぶが、
	// server-port があればそれを使う（同じアドレスで別のワールドのサーバーを順に立てるときなど）
	public static TestDedicatedServerContext create(ClientGameTestContext context, Properties properties) {
		Properties all = new Properties();
		all.putAll(properties);
		try {
			// テスト用のサーバーを起動するため、テスト環境の Minecraft EULA に同意する
			Files.writeString(Path.of("eula.txt"), "eula=true\n");
			// 普段のサーバーと重ならないよう空いているポートを使う
			if (all.getProperty("server-port") == null) {
				try (ServerSocket socket = new ServerSocket(0)) {
					all.setProperty("server-port", String.valueOf(socket.getLocalPort()));
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return context.worldBuilder().createServer(all);
	}
}
