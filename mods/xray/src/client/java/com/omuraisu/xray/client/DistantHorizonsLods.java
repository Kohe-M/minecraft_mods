package com.omuraisu.xray.client;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig;
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiCullingFrustum;
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiShadowCullingFrustum;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;

// 透視中は Distant Horizons（DH）の LOD を描かせない。
// DH の描画パス自体は走らせたままにする（renderingEnabled を false にすると、シェーダーパック使用中に Iris が
// パックを丸ごと読み直して切り替えのたびに止まる。DhApiBeforeRenderEvent の cancel では前のフレームの LOD の色が
// 残り、フェードでバニラの画素に混ざる）。視錐台の上書きは、LOD の色と深度を毎フレーム消し、フェードを何もしなくする。
// DH のクラスを参照するのはこのクラスだけ。DH のない起動では読み込まれない
final class DistantHorizonsLods {
	// DH 本体（-1）や Iris の上書きより高くして、必ずこちらが使われるようにする
	private static final int PRIORITY = 1000;

	private static final CullingFrustum FRUSTUM = new CullingFrustum();
	private static final ShadowCullingFrustum SHADOW_FRUSTUM = new ShadowCullingFrustum();

	private static boolean hidden;

	// 設定で視錐台カリングが切られていると上書きした視錐台が使われないので、隠す間だけ有効にする。
	// DH の API が変わっていて途中で LinkageError になっても LOD が隠れたままにならないよう、bind は最後、unbind は最初に行う
	static void setHidden(boolean value) {
		if (hidden == value) {
			return;
		}
		hidden = value;
		// DH の初期化が終わるまで null
		IDhApiConfig configs = DhApi.Delayed.configs;
		if (value) {
			if (configs != null) {
				configs.graphics().disableFrustumCulling().setValue(false, "X-Ray");
				configs.graphics().disableShadowFrustumCulling().setValue(false, "X-Ray");
			}
			DhApi.overrides.bind(IDhApiCullingFrustum.class, FRUSTUM);
			DhApi.overrides.bind(IDhApiShadowCullingFrustum.class, SHADOW_FRUSTUM);
		} else {
			DhApi.overrides.unbind(IDhApiCullingFrustum.class, FRUSTUM);
			DhApi.overrides.unbind(IDhApiShadowCullingFrustum.class, SHADOW_FRUSTUM);
			if (configs != null) {
				configs.graphics().disableFrustumCulling().clearValue();
				configs.graphics().disableShadowFrustumCulling().clearValue();
			}
		}
	}

	private static final class CullingFrustum implements IDhApiCullingFrustum {
		@Override
		public int getPriority() {
			return PRIORITY;
		}

		@Override
		public void update(int worldY, int height, DhApiMat4f projectionMatrix) {
		}

		// 1 つも交差しないので、LOD は 1 つも描かれない
		@Override
		public boolean intersects(int minX, int minY, int minZ, int maxX) {
			return false;
		}
	}

	private static final class ShadowCullingFrustum implements IDhApiShadowCullingFrustum {
		@Override
		public int getPriority() {
			return PRIORITY;
		}

		@Override
		public void update(int worldY, int height, DhApiMat4f projectionMatrix) {
		}

		@Override
		public boolean intersects(int minX, int minY, int minZ, int maxX) {
			return false;
		}
	}

	private DistantHorizonsLods() {
	}
}
