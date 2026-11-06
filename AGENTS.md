# AGENTS.md

Fabric mod（fullbright / minimap / sorter / timber / xray / zoom）を `mods/` にまとめ、1つの Gradle マルチプロジェクトで管理するリポジトリ。構成は [README.md](README.md)。

## バージョン

- MC に依存するバージョン（MC・Loader・Loom・Fabric API・Java・Sodium・Iris など）はルートの `gradle.properties` にだけ書く。mod 側の `build.gradle`・`gradle.properties`・`fabric.mod.json` に直接書かない
- `fabric.mod.json` の `depends` は `${minecraft_version}`・`${loader_version}`・`${java_version}` で埋める
- 各 mod のバージョンは `mods/<mod>/gradle.properties` の `mod_version`（SemVer）
  - 修正は patch、機能追加は minor、設定・挙動の互換を壊す変更は major
  - MC への移植だけなら据え置く（`+<MC>` で区別される）
- 全 mod 共通の設定はルートの `build.gradle`、その mod だけの設定は `mods/<mod>/build.gradle`

## ブランチ

| ブランチ | 用途 |
|---|---|
| `main` | 最新の MC バージョン。機能追加はここ |
| `mc/<MC>` | 旧 MC バージョンの保守。修正のみ |

- MC を上げる前に、今の main から `mc/<今のMC>` を切って push する
- 旧バージョンの修正は `mc/<MC>` でコミットし、main にも必要なら `git cherry-pick -x` する
- main に直接コミットしてよい。大きな変更は作業ブランチ（`feat/<内容>` など）を切る

## 作業の流れ

エージェントが自律的に作業し、動くものを仕上げて push まで行う。途中でユーザーに確認しない。

1. 実装する
2. 変更した mod のビルドとゲームテストを通す（client 側のゲームテストがある mod はそれも）
3. コミットメッセージのルールに沿ってコミットする
4. `git push --follow-tags` で、今いるブランチとタグを push する

- テストが通らない、不具合が見つかったときは、報告で止めずに原因を調べて直し、テストが通るまで繰り返す
  - テストを消す・飛ばす・判定をゆるめて通すのは禁止。直すのは実装の側
  - 作業中に見つけた既存の不具合も直す（別のコミットに分ける）
  - 新しい機能や直した不具合には、ゲームテストを追加して動作を確かめる
- ユーザーに頼るのは、エージェントだけでは進められないときに限る（依存 mod の対応版がまだ出ていない、など）。そのときは試したことと止まっている理由を伝える
- force push・履歴の書き換え・ブランチやタグの削除は、ユーザーに確認してから行う

## タグ

- `<mod>/<mod_version>+<MC>`（例: `sorter/1.0.0+26.3`）の注釈付きタグ
- `mod_version` を上げたコミット、または MC 移植を終えたコミットに付ける

## コミットメッセージ

Conventional Commits 形式で、日本語で書く。

```
<type>(<scope>): <件名>

<本文: なぜ変えたか。必要なときだけ>
```

| type | |
|---|---|
| `feat` | 機能追加 |
| `fix` | 不具合修正 |
| `refactor` | 挙動を変えない整理 |
| `perf` | 性能改善 |
| `test` | ゲームテストの追加・修正 |
| `docs` | ドキュメント |
| `build` | Gradle・依存・MC バージョンの更新 |
| `chore` | その他 |

- scope は mod 名（`fullbright` / `minimap` / `sorter` / `timber` / `xray` / `zoom`）。複数の mod やリポジトリ全体に関わるときは省く
- 1コミットで扱う mod は1つ。共通設定の変更と mod の変更は分ける
- 件名は体言止めで50字程度まで。句点は付けない
- MC 更新は `build: Minecraft 26.4 に対応`、リリースは `chore(sorter): 1.1.0 をリリース`

例:

```
feat(xray): ホイールに古代の残骸を追加
fix(sorter): 空のスロットがあるとソート順が崩れる問題を修正
build: Fabric API を 0.166.0+26.3 に更新
```

## ビルドとテスト

`JAVA_HOME` は JDK 8 を指しているので、JDK 25 を指定してから実行する。

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-25.0.4.101-hotspot"
./gradlew build                                         # ビルド + server ゲームテスト（sorter, timber）
./gradlew :fullbright:runClientGameTest :minimap:runClientGameTest :sorter:runClientGameTest :xray:runClientGameTest :zoom:runClientGameTest
./gradlew :xray:runClient                                # -PnoSodium でバニラ描画
```

- 見た目は `runClientGameTest` のスクリーンショット（`mods/<mod>/build/run/clientGameTest/screenshots`）で確認する

## mod の作り方

- パッケージは `com.omuraisu.<mod>`、mod ID はディレクトリ名と同じにする
- 新しい mod は `mods/<mod>` に作って `settings.gradle` の `include` に追加し、`mods/<mod>/gradle.properties` に `mod_version` を置く
- 言語ファイルは `en_us.json` と `ja_jp.json` の両方を用意する
- mod を追加したときや、クライアント・サーバーのどちらに入れるかが変わったときは、README の mod 表（クライアント / サーバー列）を更新する
  - 列の値は `必須` / `任意（入れると何ができるか）` / `不要`
  - `fabric.mod.json` の `environment` と食い違わないようにする（クライアントのみなら `client`）
- 片側にだけ入っていても落ちないようにする。相手側へのパケットは `ClientPlayNetworking.canSend` などで確かめてから送る

## Iris + Sodium 対応

Iris + Sodium の環境で遊ぶ前提。すべての mod をこの環境で動かす。

- Iris + Sodium あり・なしの両方で動くようにする。`fabric.mod.json` の `depends` には入れない
- 描画に関わる処理は、Sodium が置き換える経路にもフックする（例: チャンク構築は Sodium の `LevelSlice`）
  - Sodium のクラスへの mixin には `@Pseudo` を付けて、Sodium がなくても起動できるようにする
  - Sodium の API は `compileOnly "maven.modrinth:sodium:${sodium_version}"` で参照する
- Iris でシェーダーパックを有効にしても、表示が崩れたり効果が消えたりしないようにする
- 動作確認では `localRuntime` で Sodium と Iris を入れて起動し、`-PnoSodium` でバニラ描画も確かめる（`mods/xray/build.gradle`・`mods/zoom/build.gradle` 参照）
- Sodium・Iris のバージョンはルートの `gradle.properties` の `sodium_version`・`iris_version`。MC を上げたら一緒に上げる

## minimap の仕様

クライアントのみで動く。サーバー側の mod は不要。

- ミニマップ: 画面の右上に、北を上に固定して 160 ブロック四方を出す
  - 1 ブロックの大きさは実ピクセルで `round(ウィンドウの短辺 / 540)`（最小 1）。GUI スケールの設定には左右されない
  - ステータス効果のアイコンがあるときはその下に出す。F1 と F3 では隠す
- マップ画面（M キー）: 全画面の地図。ドラッグで移動、ホイールで拡大縮小
  - 地形が出るのは読み込み済みのチャンクだけ。歩いた場所の記憶はしない
  - 倍率はゲームを終えるまで保ち、位置は開くたびに自分に戻す
- ピンの操作はマップ画面でだけ行う。ゲーム中のキーは M だけ

| 操作 | 場所 | 動作 |
|---|---|---|
| 左クリック | 何もないところ | 刺して名前の入力へ。Enter か外のクリックで確定、Esc で入力だけ取り消し |
| Shift + 左クリック | ピンの上 | 抜く |
| 右クリック | ピンの上 | 名前の変更 |

  - ピンの上の左クリックと、何もないところの Shift + 左クリックでは何もしない（位置を確かめようとして抜かないため）
  - 刺す・抜くの操作は画面の左下に出す。名前の入力中は隠す
- ワールドでの表示: ピンの位置に薄い光の柱を立てる（ビーコンのレンダータイプ）。F1 で隠す
  - 描画距離の 75% より遠いピンは、方角を保って手前に描く
  - 名前は 32 ブロック以内でだけ、柱に添えて出す
- ピンの保存: 1 行に「次元 x z 名前」（名前は省略可）
  - シングルプレイはセーブの中の `minimap_pins.txt`、マルチプレイは `config/minimap/<URL エンコードしたアドレス>.txt`
- 他のプレイヤー: ミニマップとマップ画面に顔で出す。透明とスペクテイターは出さない
  - エンティティとして届いている相手は正確な位置に、届かない相手はロケーターバーのデータ（`ClientPacketListener.getWaypointManager()`）から位置か方角を出す
- ゲームテスト
  - 他のプレイヤーは `FakePlayer` で作る。tick されないので、ロケーターバーのデータを送らせるには `baseTick()` と `trackWaypoint` を呼ぶ
  - 専用サーバーのテストは、テスト環境の `eula.txt` に同意を書き込み、空いているポートで起動する

## Minecraft 26.3 の注意点

MC を上げたら、この節を新しいバージョンの内容に書き換える。

- 非難読化・Mojang 名
- 描画は `extract*` / `GuiGraphicsExtractor`、画面は `Minecraft.gui.screen()` / `gui.setScreen()`
- `InputConstants.MOUSE_BUTTON_LEFT` は 1（GLFW の 0 ではない）。クライアントゲームテストの `pressMouse` もこれを使う
- 全チャンクの再構築は `Minecraft.levelExtractor.allChanged()`（F3+A と同じ）。`levelRenderer.invalidateCompiledGeometry` を直接呼ぶと、素の描画では再構築が予約されず何も描かれなくなる
- クライアントゲームテストの入力は、マウスのイベントに修飾キーを載せない。Shift の判定は `event.hasShiftDown()` ではなく `Minecraft.hasShiftDown()` を使う
- `GameRenderer.projectPointToScreen` はカメラの後ろの点も画面の中に写す。前後はカメラの向きとの内積で判定する
- クラスの確認は `~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/<MC>/*.jar` を javap する
