# AGENTS.md

Fabric mod（fullbright / minimap / peek / placer / refill / sorter / timber / xray / zoom）と、それらを 1 つにまとめた jori を `mods/` に置き、1つの Gradle マルチプロジェクトで管理するリポジトリ。

ここには開発環境・手順・決まりごとだけを書く。仕様と仕組みは `docs/` に置く。

## 文書

| | |
|---|---|
| [README.md](README.md) | mod の一覧と、クライアント・サーバーのどちらに入れるか |
| [docs/architecture.md](docs/architecture.md) | ディレクトリとビルドの構成、mod に共通の作り |
| [docs/development.md](docs/development.md) | dev.py の使い方、結果の見方、失敗したときに見るところ、リリースの順番 |
| [docs/minecraft.md](docs/minecraft.md) | いまの MC バージョンの API の癖 |
| `docs/mods/<mod>.md` | mod ごとの仕様と仕組み |

- mod を変える前に、その mod の `docs/mods/<mod>.md` を読む。MC の API を使うなら `docs/minecraft.md` も読む
- 仕様や仕組みを変えたら `docs/mods/<mod>.md` も直す。コードを読まなくても分かるように、決めた理由も添える
- MC の API で引っかかったことは `docs/minecraft.md` に足す
- `docs/` と README だけの変更はゲームテストを走らせなくてよい（`dev.py test` も何も走らせない）

## バージョン

- MC に依存するバージョン（MC・Loader・Loom・Fabric API・Java・Sodium・Iris など）はルートの `gradle.properties` にだけ書く。mod 側の `build.gradle`・`gradle.properties`・`fabric.mod.json` に直接書かない
- `fabric.mod.json` の `depends` は `${minecraft_version}`・`${loader_version}`・`${java_version}` で埋める
- 各 mod のバージョンは `mods/<mod>/gradle.properties` の `mod_version`（SemVer）
  - 修正は patch、機能追加は minor、設定・挙動の互換を壊す変更は major
  - MC への移植だけなら据え置く（`+<MC>` で区別される）
- 全 mod 共通の設定はルートの `build.gradle`、その mod だけの設定は `mods/<mod>/build.gradle`
- MC を上げたら `docs/minecraft.md` を新しいバージョンの内容に書き換える

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

1. 実装する。修正の途中は `python tools/dev.py test <mod> --scenario <名前>` で関わるシナリオだけを回してよい
2. `python tools/dev.py test` で、変更した mod のビルドとゲームテスト（server・client）を通す。シナリオで絞らずに走らせる
3. コミットメッセージのルールに沿ってコミットする
4. リリースするなら `python tools/dev.py release <mod> <patch|minor|major>` で jar を作って確かめ、版を上げてタグを付ける（下の「タグ」）
5. `git push --follow-tags` で、今いるブランチとタグを push する

- テストが通らない、不具合が見つかったときは、報告で止めずに原因を調べて直し、テストが通るまで繰り返す
  - テストを消す・飛ばす・判定をゆるめて通すのは禁止。直すのは実装の側
  - 作業中に見つけた既存の不具合も直す（別のコミットに分ける）
  - 新しい機能や直した不具合には、ゲームテストを追加して動作を確かめる
- ユーザーに頼るのは、エージェントだけでは進められないときに限る（依存 mod の対応版がまだ出ていない、など）。そのときは試したことと止まっている理由を伝える
- force push・履歴の書き換え・ブランチやタグの削除は、ユーザーに確認してから行う

## タグ

- `<mod>/<mod_version>+<MC>`（例: `sorter/1.0.0+26.3`）の注釈付きタグ
- `mod_version` を上げたコミット、または MC 移植を終えたコミットに付ける
- `python tools/dev.py release sorter minor timber patch` で、版を上げるコミット（`chore(<mod>): <版> をリリース`）とタグをまとめて作る
  - 先に版を書き換えた状態で jar を作り（`:<mod>:assemble`。テストは `dev.py test` で済ませているので走らせない）、`dev.py check` と同じ検査をしてから、コミットしてタグを付ける。失敗したら版を元に戻すので、直して同じコマンドを走らせ直せばよい
  - CI はないので、配る jar は `mods/<mod>/build/libs/<mod>-<版>+<MC>.jar` のこれ
  - jori に入っている mod を上げると、jori も一番大きい上げ方で一緒に上げる（`--no-bundle` で止める）
  - エージェントは `--trailer "Co-Authored-By: ..."` でコミットの末尾の行を足す。`--push` で push まで、`--dry-run` で何が上がるかだけ見る
  - jori は中の mod を上げた後のコミットにタグを付ける。jori のタグと jar に、上げた後の中の mod が入るようにするため
  - 追跡していないファイルが `mods/` や共通の設定にあると、jar に入ってタグと食い違うので止まる（ゲームテストのソースは jar に入らないので除く）
- MC の移植は版を変えずに `python tools/dev.py release <mod> port ...` でタグを付ける（jar も作る）
- `python tools/dev.py status` で、最後のタグ（HEAD の祖先にある、いまの MC のもの）の後にリリースしていない変更がある mod が分かる

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

- scope は mod 名（`fullbright` / `jori` / `minimap` / `peek` / `placer` / `refill` / `sorter` / `timber` / `xray` / `zoom`）。複数の mod やリポジトリ全体に関わるときは省く
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

`JAVA_HOME` はどの JDK でもよい（いまは JDK 8 を指している）。Gradle のデーモンは `gradle/gradle-daemon-jvm.properties` の JDK 25 を、インストール済みの JDK から自動で探して使う。

```bash
python tools/dev.py doctor               # JDK・Python・Gradle などがそろっているか
python tools/dev.py test --plan          # 何をなぜ走らせるかだけ出す
python tools/dev.py test                 # 上流のブランチから変わった mod を、ビルド + server・client のゲームテスト
python tools/dev.py test sorter jori     # mod を指定する
python tools/dev.py test minimap --scenario map   # クライアントのゲームテストをシナリオで絞る
python tools/dev.py test --all           # 全 mod
python tools/dev.py test --no-sodium     # Sodium / Iris を入れずにバニラ描画で
python tools/dev.py check --build        # jar を作り、ソースと jar の食い違い（言語ファイル・jori の同梱・文字化け）を確かめる
python tools/dev.py shots                # いまあるスクリーンショットを一覧にまとめ直す
./gradlew build                          # ビルド + server ゲームテスト（sorter, timber）
./gradlew runClientGameTest              # 全 mod の client ゲームテスト
./gradlew :xray:runClient                # -PnoSodium でバニラ描画
```

- `dev.py test` は、jori に入っている mod が変わったら jori も走らせる。ルートのビルド設定（`build.gradle` など）と `testkit/` が変わったら全 mod
- 合否は Gradle の終了コードとタスクの結果で決める。結果は最後に mod ごとに 1 行で出る。失敗したら、例外とこのリポジトリのスタックの行も出す
  - `build/dev/last-run.json`: 実行の記録（コミット・対象と選んだわけ・各回の結果・成果物の場所）。前の結果を引き継ぐときは、まずこれのコミットを確かめる
  - `build/dev/report.html`: 結果の表とすべてのスクリーンショット（人が見る用）
  - `build/dev/sheets/<mod>-<n>.png`: スクリーンショットを 20 枚ずつ 1 枚に並べた画像。見た目はまずこれを Read して確かめる
  - `build/dev/gradle.log`: gradle の出力すべて
  - 失敗の種類ごとに見るところは [docs/development.md](docs/development.md)
- client ゲームテストは同時に 3 つまで起動する（`-PclientSlots=N`）。一度に多く起動すると、どれかが起動の途中で止まることがあるため
- 1 つが 10 分を超えたら止める（`-PclientTimeout=分`）。`dev.py test` は時間切れのものだけ 1 つずつ走らせ直す。テストの失敗は走らせ直さない
- Python 3 と、一覧の画像に Pillow を使う（なければ画像は作らず HTML だけ）

### ゲームテストの道具（testkit）

`testkit/java/com/omuraisu/testkit` は、全 mod の gametest のソースセットに一緒にコンパイルされる。ほかの mod のテストからコピーせずにこれを使う。

| クラス | |
|---|---|
| `TestServers.create(context[, properties])` | EULA に同意し、空いているポートで専用サーバーを立てる |
| `Widgets.click(context, "文字")` / `hover` | 画面のボタンなどを表示している文字で探し、実際のマウスで押す・カーソルを置く |
| `Screenshots.read(path, image -> ...)` | `takeScreenshot` の画像の画素を読む |
| `Asserts.assertEquals(名前, 期待, 実際)` | 何を比べていたかが分かる失敗にする |
| `Scenario` | クライアントのゲームテストを、独立して走らせられるシナリオに分ける（`--scenario` で絞る） |

## mod の作り方

- パッケージは `com.omuraisu.<mod>`、mod ID はディレクトリ名と同じにする
- 新しい mod は `mods/<mod>` に作って `settings.gradle` の `include` に追加し、`mods/<mod>/gradle.properties` に `mod_version` を置く
- 言語ファイルは `en_us.json` と `ja_jp.json` の両方を用意する
- mod を追加したときや、クライアント・サーバーのどちらに入れるかが変わったときは、README の mod 表（クライアント / サーバー列）を更新する
  - 列の値は `必須` / `任意（入れると何ができるか）` / `不要`
  - `fabric.mod.json` の `environment` と食い違わないようにする（クライアントのみなら `client`）
- 片側にだけ入っていても落ちないようにする。相手側へのパケットは `ClientPlayNetworking.canSend` などで確かめてから送る
- アイコンは `assets/<mod>/icon.png`（128 × 128）。jori の一覧にも出るので、テンプレートのままにしない
- 仕様は `docs/mods/<mod>.md` に書く。どちらに入れるか・キーと設定ファイル・仕組み・ゲームテストで気をつけることを、ほかの mod の文書にそろえて書く
- 新しい mod は jori にも入れる（[docs/mods/jori.md](docs/mods/jori.md)）
  - `isActive()` / `setActive(boolean)` を用意し、false の間はバニラと同じにふるまう。ゲームテストで `setActive(false)` の間の動きを確かめる
  - `mods/jori/build.gradle` の `modules`、`JoriClient.FEATURES`（サーバーでも動くなら `Feature.SERVER` にも）、jori の言語ファイルの `jori.description.<mod>` に足す
- 中の mod を変えたら、その mod のリリースの後に jori の `mod_version` も上げてリリースする（jori の jar に入る版が変わるため）。`dev.py release` は自動で上げる

## Iris + Sodium 対応

Iris + Sodium の環境で遊ぶ前提。すべての mod をこの環境で動かす。

- Iris + Sodium あり・なしの両方で動くようにする。`fabric.mod.json` の `depends` には入れない
- 描画に関わる処理は、Sodium が置き換える経路にもフックする（例: チャンク構築は Sodium の `LevelSlice`。いまのフック先は [docs/architecture.md](docs/architecture.md)）
  - Sodium のクラスへの mixin には `@Pseudo` を付けて、Sodium がなくても起動できるようにする
  - Sodium の API は `compileOnly "maven.modrinth:sodium:${sodium_version}"` で参照する
- Iris でシェーダーパックを有効にしても、表示が崩れたり効果が消えたりしないようにする
- 動作確認では `localRuntime` で Sodium と Iris を入れて起動し、`-PnoSodium` でバニラ描画も確かめる（`mods/xray/build.gradle`・`mods/zoom/build.gradle` 参照）
- Sodium・Iris のバージョンはルートの `gradle.properties` の `sodium_version`・`iris_version`。MC を上げたら一緒に上げる
