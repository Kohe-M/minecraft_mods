# アーキテクチャ

## ビルド

```
minecraft_mods/
├─ gradle.properties    MC に依存するバージョン（MC・Loader・Loom・Fabric API・Java・Sodium・Iris）
├─ build.gradle         全 mod 共通のビルド設定（subprojects）
├─ settings.gradle      mod の登録
├─ gradle/gradle-daemon-jvm.properties  Gradle を動かす JDK（25）
├─ tools/dev.py         準備の確認・テスト・食い違いの検査・リリース（docs/development.md）
├─ testkit/java/        全 mod の gametest に一緒にコンパイルする道具
├─ docs/                仕様と仕組み
└─ mods/<mod>/
   ├─ gradle.properties mod_version
   ├─ build.gradle      その mod だけの設定（ゲームテスト・Sodium / Iris）
   └─ src/
```

- `settings.gradle` が `mods/<mod>` を `:<mod>` として登録する。タスク名は `:xray:runClient` のように mod 名だけ
- ルートの `build.gradle` の `subprojects` が、全 mod に Loom・Minecraft・Fabric Loader・Fabric API を入れ、`fabric.mod.json` の `${version}`・`${minecraft_version}`・`${loader_version}`・`${java_version}` を埋め、LICENSE を jar に入れる
- jar は `<mod>-<mod_version>+<minecraft_version>.jar`
- Sodium・Iris は、使う mod の `build.gradle` で Modrinth の maven から入れる。Sodium のクラスに mixin するときだけ `compileOnly`、開発で起動するときは `localRuntime`（`-PnoSodium` で外す）。jar には入れない
- jori は各 mod を `include project(...)` で Jar-in-Jar にする（[mods/jori.md](mods/jori.md)）

## ソースセット

Loom の `splitEnvironmentSourceSets` で分ける。

| | 中身 |
|---|---|
| `src/main` | サーバーでも動くコード。`fabric.mod.json`・アイコン・言語ファイルもここ |
| `src/client` | クライアントだけのコード。`<Mod>Client`・画面・描画と入力の mixin |
| `src/gametest` | ゲームテスト。`mods/<mod>/build.gradle` の `fabricApi.configureTests` で作り、mod ID は `<mod>-gametest` |

- サーバーのゲームテスト（`enableGameTests`）は sorter と timber。`./gradlew build` で走る
- ほかはクライアントのゲームテスト（`enableClientGameTests`、`runClientGameTest`）。ルートの `build.gradle` の BuildService（`minecraftClients`）で同時に起動する数を絞り、時間切れを付ける
- 長いクライアントのゲームテストは、testkit の `Scenario` で独立したシナリオに分ける。`-Pscenarios=a,b` をルートの `build.gradle` がシステムプロパティ `testkit.scenarios` にして渡し、名前の合わないシナリオは飛ばす（[development.md](development.md)）

## mod の形

| | `environment` | 入口 |
|---|---|---|
| クライアントだけ（fullbright・peek・placer・refill・xray・zoom） | `client` | client の `<Mod>Client` |
| サーバーでも動く（minimap・sorter・timber） | `*` | main の `<Mod>` と client の `<Mod>Client` |
| jori | `*` | main の `Jori` と client の `JoriClient` |

## 共通の作り

- 有効・無効: 各 mod は `isActive()` / `setActive(boolean)` を持ち、jori が呼ぶ。サーバーでも動く mod は main の `<Mod>`、クライアントだけの mod は `<Mod>Client` に置く。false の間はバニラと同じにふるまい、キーの入力は読んで捨てる
- キー: `KeyMappingHelper.registerKeyMapping` で登録し、カテゴリは `KeyMapping.Category.register(Identifier.fromNamespaceAndPath("<mod>", "main"))`。jori はカテゴリの名前空間でその mod のキーを見つけてツールチップに出す
  - 入力は `ClientTickEvents.END_CLIENT_TICK` で見る。1 tick より短い単押しは `isDown` に出ないので `consumeClick` でも拾う
  - 単押しと長押しで役割が違うキー（fullbright・xray）は、離したときに単押しかどうかを決める
- 状態の表示: キーで切り替えたらアクションバー（`sendOverlayMessage`）に出す
- 設定: `config/<mod>.txt` に 1 行 1 つ `キー=値`。読めないときは既定の値で動く
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
