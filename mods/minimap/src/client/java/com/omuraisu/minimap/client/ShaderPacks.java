package com.omuraisu.minimap.client;

import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.api.v0.IrisApi;

// シェーダーパックを使っているか。Iris のクラスに触れるのは ShaderPackApi だけで、Iris のない起動では読み込まれない
final class ShaderPacks {
	static boolean inUse() {
		return FabricLoader.getInstance().isModLoaded("iris") && ShaderPackApi.inUse();
	}

	private static final class ShaderPackApi {
		private static boolean inUse() {
			return IrisApi.getInstance().isShaderPackInUse();
		}
	}

	private ShaderPacks() {
	}
}
