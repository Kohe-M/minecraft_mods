# AGENTS.md

Fabric mod（sorter / timber / xray / zoom）を1つの Gradle マルチプロジェクトで管理するリポジトリ。構成は [README.md](README.md)。

## バージョン

- MC に依存するバージョン（MC・Loader・Loom・Fabric API・Java・Sodium・Iris など）はルートの `gradle.properties` にだけ書く。mod 側の `build.gradle`・`gradle.properties`・`fabric.mod.json` に直接書かない
- `fabric.mod.json` の `depends` は `${minecraft_version}`・`${loader_version}`・`${java_version}` で埋める
- 各 mod のバージョンは `<mod>/gradle.properties` の `mod_version`（SemVer）
  - 修正は patch、機能追加は minor、設定・挙動の互換を壊す変更は major
  - MC への移植だけなら据え置く（`+<MC>` で区別される）
- 全 mod 共通の設定はルートの `build.gradle`、その mod だけの設定は `<mod>/build.gradle`

## ブランチ

| ブランチ | 用途 |
|---|---|
| `main` | 最新の MC バージョン。機能追加はここ |
| `mc/<MC>` | 旧 MC バージョンの保守。修正のみ |

- MC を上げる前に、今の main から `mc/<今のMC>` を切って push する
- 旧バージョンの修正は `mc/<MC>` でコミットし、main にも必要なら `git cherry-pick -x` する
- main に直接コミットしてよい。大きな変更は作業ブランチ（`feat/<内容>` など）を切る

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

- scope は mod 名（`sorter` / `timber` / `xray` / `zoom`）。複数の mod やリポジトリ全体に関わるときは省く
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
./gradlew :sorter:runClientGameTest :xray:runClientGameTest :zoom:runClientGameTest
./gradlew :xray:runClient                                # -PnoSodium でバニラ描画
```

- 変更した mod のテストを通してからコミットする
- 見た目は `runClientGameTest` のスクリーンショット（`<mod>/build/run/clientGameTest/screenshots`）で確認する

## mod の作り方

- パッケージは `com.omuraisu.<mod>`、mod ID はディレクトリ名と同じにする
- 新しい mod は `settings.gradle` の `include` に追加し、`<mod>/gradle.properties` に `mod_version` を置く
- 言語ファイルは `en_us.json` と `ja_jp.json` の両方を用意する
- Iris + Sodium 環境で遊ぶ前提。描画に関わる mod は Sodium（チャンク構築は `LevelSlice`）にも対応させる
  - `compileOnly` と `localRuntime` で `maven.modrinth:sodium` / `iris` を入れて確認する（`xray/build.gradle` 参照）

## Minecraft 26.3 の注意点

MC を上げたら、この節を新しいバージョンの内容に書き換える。

- 非難読化・Mojang 名
- 描画は `extract*` / `GuiGraphicsExtractor`、画面は `Minecraft.gui.screen()` / `gui.setScreen()`
- `InputConstants.MOUSE_BUTTON_LEFT` は 1（GLFW の 0 ではない）。クライアントゲームテストの `pressMouse` もこれを使う
- 全チャンクの再構築は `Minecraft.levelExtractor.allChanged()`（F3+A と同じ）。`levelRenderer.invalidateCompiledGeometry` を直接呼ぶと、素の描画では再構築が予約されず何も描かれなくなる
- クラスの確認は `~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/<MC>/*.jar` を javap する
