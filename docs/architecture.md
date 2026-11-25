# アーキテクチャ

## ビルド

```
minecraft_mods/
├─ gradle.properties    MC に依存するバージョン（MC・Loader・Loom・Fabric API・Java・Sodium・Iris・Distant Horizons）
├─ build.gradle         全 mod 共通のビルド設定（subprojects）
├─ settings.gradle      mod の登録
├─ gradle/gradle-daemon-jvm.properties  Gradle を動かす JDK（25）
├─ tools/dev.py         準備の確認・テスト・食い違いの検査・リリース（docs/development.md）
├─ testkit/java/        全 mod の gametest に一緒にコンパイルする道具
├─ docs/                仕様と仕組み
└─ mods/<mod>/
   ├─ gradle.properties mod_version と、走らせるゲームテスト（server_gametest・client_gametest・vanilla_render_test・distant_horizons_test）
   ├─ build.gradle      その mod だけの設定（あれば。Sodium・Iris の API の参照、jori の同梱）
   └─ src/
```

- `settings.gradle` が `mods/<mod>` を `:<mod>` として登録する。タスク名は `:xray:runClient` のように mod 名だけ
- ルートの `build.gradle` の `subprojects` が、全 mod に Loom・Minecraft・Fabric Loader・Fabric API を入れ、`fabric.mod.json` と `*.mixins.json` の `${version}`・`${minecraft_version}`・`${loader_version}`・`${java_version}` を埋め、LICENSE を jar に入れる
  - 埋めるのは main・client・gametest のすべてのソースセット（`ProcessResources` のタスクすべて）。mixins.json の `compatibilityLevel` は `JAVA_${java_version}` と書く。Java を上げるときに mod ごとに直して回らずに済むようにするため
- jar は `<mod>-<mod_version>+<minecraft_version>.jar`
- Sodium・Iris は Modrinth の maven から入れる。開発で起動するとき（runClient・ゲームテスト）は、ルートの `build.gradle` がどの mod にも `localRuntime` で入れる（`-PnoSodium` で外す）。jar には入れない
  - 以前は mod ごとの `build.gradle` に同じ設定を書いていて、sorter と timber には入っていなかった（sorter のクライアントのテストがバニラ描画でしか走っていなかった）
  - Sodium・Iris のクラスをコンパイルで参照する mod だけ、自分の `build.gradle` に書く（xray の `compileOnly` の Sodium、minimap の `compileOnly` の Iris、fullbright・minimap の `gametestCompileOnly` の Iris）。Distant Horizons の API は xray の `compileOnly` と、ゲームテストがある mod の `gametestCompileOnly`（ルートの `build.gradle`）
- `mods/<mod>/build.gradle` は、その mod だけの設定があるときだけ置く。共通の設定はルートの `subprojects` に置き、mod 側には差分だけを残す
- jori は各 mod を `include project(...)` で Jar-in-Jar にする（[mods/jori.md](mods/jori.md)）

## ソースセット

Loom の `splitEnvironmentSourceSets` で分ける。

| | 中身 |
|---|---|
| `src/main` | サーバーでも動くコード。`fabric.mod.json`・アイコン・言語ファイルもここ |
| `src/client` | クライアントだけのコード。`<Mod>Client`・画面・描画と入力の mixin |
| `src/gametest` | ゲームテスト。ルートの `build.gradle` が `fabricApi.configureTests` で作り、mod ID は `<mod>-gametest` |

- どちらのゲームテストを走らせるかは `mods/<mod>/gradle.properties` の `server_gametest`・`client_gametest`（`true` か `false`）。ルートの `build.gradle` と `dev.py test` が同じものを読む。どちらも `true` でなければ gametest のソースセットは作らない
  - 以前は mod ごとの `build.gradle` の `enableClientGameTests` を dev.py が正規表現で読んでいた
- サーバーのゲームテスト（`server_gametest=true`）は digger・sorter・timber。`./gradlew build` で走る
- ほかはクライアントのゲームテスト（`client_gametest=true`、`runClientGameTest`）。ルートの `build.gradle` の BuildService（`minecraftClients`）で同時に起動する数を絞り、時間切れを付ける
- 長いクライアントのゲームテストは、testkit の `Scenario` で独立したシナリオに分ける。`-Pscenarios=a,b` をルートの `build.gradle` がシステムプロパティ `testkit.scenarios` にして渡し、名前の合わないシナリオは飛ばす（[development.md](development.md)）

## mod の形

| | `environment` | 入口 |
|---|---|---|
| クライアントだけ（fullbright・peek・placer・refill・xray・zoom） | `client` | client の `<Mod>Client` |
| サーバーでも動く（digger・minimap・sorter・timber） | `*` | main の `<Mod>` と client の `<Mod>Client` |
| jori | `*` | main の `Jori` と client の `JoriClient` |

## 共通の作り

- 有効・無効: 各 mod は `isActive()` / `setActive(boolean)` を持ち、jori が呼ぶ。サーバーでも動く mod は main の `<Mod>`、クライアントだけの mod は `<Mod>Client` に置く。false の間はバニラと同じにふるまい、キーの入力は読んで捨てる
- キー: `KeyMappingHelper.registerKeyMapping` で登録し、カテゴリは `KeyMapping.Category.register(Identifier.fromNamespaceAndPath("<mod>", "main"))`。jori はカテゴリの名前空間でその mod のキーを見つけてツールチップに出す
  - 入力は `ClientTickEvents.END_CLIENT_TICK` で見る。1 tick より短い単押しは `isDown` に出ないので `consumeClick` でも拾う
  - 単押しと長押しで役割が違うキー（fullbright・xray）は、離したときに単押しかどうかを決める
- 状態の表示: キーで切り替えたらアクションバー（`sendOverlayMessage`）に出す
- 設定: `config/<mod>.txt` に 1 行 1 つ `キー=値`。読めないときは既定の値で動く
  - 行ごとに最初の `=` で分け、キーと値の前後の空白を除いて読む。知らないキーの行と `=` のない行は無視する。項目を足しても、前の版で書いたファイルの値を読めるようにするため（以前は placer・timber がファイル全体を `enabled=false` と比べ、xray がファイル全体から `mode=` を消して読んでいて、行が増えると値を失った）
  - 読み方は各 mod の中に書く（共有のライブラリは作らない。jori の中でも各 mod は単体のときと同じ jar のまま動くため）
  - 保存に失敗したら、ログに 1 行（ファイルと理由）出す。遊ぶのには困らないので止めないが、黙って捨てない
- ホイール: キーを押している間のホイールを使う mod（fullbright・zoom）は `MouseHandler.onScroll` の `ScrollWheelHandler.onMouseScroll` を包み、使った分は (0, 0) を返してホットバーに回さない
- 通信: payload は main に `CustomPacketPayload` の record で置き、`PayloadTypeRegistry` に登録する
  - サーバーの受け口は `ServerPlayConnectionEvents.INIT` で接続ごとに作る。jori で無効にした専用サーバーでは作らず、クライアントには mod のないサーバーと同じに見せる
  - クライアントは `ClientPlayNetworking.canSend` で確かめてから送る。送れないときは、クライアントだけでできることに留める
  - 形を変えたらパケットの ID も変える。同じ ID で形が違う相手とは切断される

## Iris + Sodium

| mod | バニラ | Sodium / Iris |
|---|---|---|
| xray | `RenderSectionRegion`（チャンク構築が読むワールドのコピー） | Sodium の `LevelSlice` |
| xray のチェスト | `BlockEntityRenderDispatcher`（ブロックエンティティの描画の状態） | 同じ |
| xray のエンティティ | `EntityRenderer`（明るさと描く距離） | 同じ |
| fullbright | `LightmapRenderStateExtractor`（ライトマップ） | Iris の `CommonUniforms`（シェーダーパックの uniform） |

- Sodium のチャンクの作り直しは、バニラと同じ `Minecraft.levelExtractor.allChanged()` で起きる
- ほかの mod は Sodium が置き換える経路に触らないので、Sodium・Iris 用のフックはない。minimap の地図は HUD と画面、光の柱はビーコンのレンダータイプ、digger の枠線はバニラの枠線と同じレンダータイプ、peek は GUI の描画、zoom の FOV はバニラの `Camera` から Sodium・Iris にも渡る。placer・refill・sorter・timber は描画に関わらない

## Distant Horizons

Distant Horizons（DH）はバニラの描画距離の外の地形を LOD として描き、最後に「フェード」でバニラの画素を遠いものほど LOD の色に混ぜる。`fabric.mod.json` の `depends` には入れず、DH のクラスを参照するのは、DH があるときだけ読み込まれるクラスの中だけにする。

| mod | DH との関わり |
|---|---|
| xray | 透視中は LOD を 1 つも描かせない（`DistantHorizonsLods`）。視錐台の上書きを DH の API（`DhApi.overrides`）に bind し、隠す間だけ `disableFrustumCulling` / `disableShadowFrustumCulling` を false に上書きする。`renderingEnabled` を切ると Iris がパックを読み直して止まり、`DhApiBeforeRenderEvent` の cancel では前のフレームの LOD の色がフェードで混ざるため |
| minimap | ピンの柱（`PinBeams`）は深度を書かない半透明なので、DH のフェードが柱の後ろの地形の距離で掛かり、遠い壁の前の柱が LOD の色に上書きされる。DH があってシェーダーパックを使っていないときだけ、`LevelRenderEvents.END_MAIN`（フェードの後）に自前の `StagedVertexBuffer` と `RenderPass` で描く。シェーダーパック使用中はフェードが掛からないので `COLLECT_SUBMITS` のまま |
| fullbright | DH は LOD の色にバニラのライトマップをそのまま使うので、ライトマップの明るさを差し替えるだけで LOD も明るくなる。DH 用のフックはない |
| zoom | DH は LOD を描くのに、バニラがワールドの描画で使う射影行列（`ProjectionMatrixBuffer` に書かれる値）を読むので、`Camera.calculateFov` の差し替えが LOD にも届く。DH 用のフックはない |
| minimap の遠くの地形 | 地図の地形（`FarTerrain`）は自前のスレッドでリージョンファイルを読み、vanilla の生成の列で FEATURES まで生成する。DH は自分のスレッドで生成し、LOD のデータも自分の DB に持つので、列もデータも共有しない（DH の設定で生成の方法を「サーバー」にしたときだけ vanilla の列を使うが、vanilla の仕事として順に並ぶだけ） |
| digger | 枠線はバニラの枠線と同じレンダータイプで、手の届く距離のブロックの表面に描く。フェードの掛かる距離に入らないので、DH 用のフックはない |
| peek・placer・refill・sorter・timber | 描画に関わらない |

- DH は `java.awt.headless` を false にするので、ゲームテストが失敗すると Fabric Loader のエラーの画面が開いて終わらなくなる。ルートの `build.gradle` が `runClientGameTest` に `fabric.noGui` を渡す
- ゲームテストでは `-PdistantHorizons` で DH を `localRuntime` に入れる。DH のクラスは Modrinth の jar に API ごと入っているので、`compileOnly` / `gametestCompileOnly` で参照する
- DH の雲はバニラの設定では消えず、動いて画素の比較が揺れる。画素を比べる前に testkit の `Lods.disableClouds` で DH の設定から消す
