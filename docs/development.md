# 開発の手順

エージェントも人も、入口は `python tools/dev.py` にそろえる。決まりごと（コミット・ブランチ・タグ）は [AGENTS.md](../AGENTS.md)。

## 準備を確かめる（doctor）

```bash
python tools/dev.py doctor
```

| 項目 | なければ |
|---|---|
| Python 3.8 以上 | 入れる |
| Pillow | 任意。スクリーンショットの一覧の画像を作らない（HTML は作る） |
| git と、test の比べる先（上流のブランチ。なければ `origin/main`） | `test --base <ref>` で比べる先を指定する |
| gradlew を起動する Java（`JAVA_HOME` か PATH の `java`。どの版でもよい） | 入れる |
| JDK 25（Gradle のデーモンとコンパイル。`gradle/gradle-daemon-jvm.properties` と `java_version`） | 入れる。インストール済みのものを Gradle が自動で探す |
| Gradle デーモンの文字コードが UTF-8（`gradle.properties` の `org.gradle.jvmargs`） | 足す。下の「文字コード」 |

## テスト（test）

```bash
python tools/dev.py test --plan              # 何をなぜ走らせるかだけ出す
python tools/dev.py test                     # 上流のブランチから変わった mod
python tools/dev.py test minimap --scenario map   # クライアントのゲームテストをシナリオで絞る
python tools/dev.py test --all --no-sodium   # 全 mod をバニラ描画だけで
python tools/dev.py test xray --no-vanilla   # 描画にフックする mod でも、バニラ描画の 2 回目を飛ばす
```

- 対象は、比べる先からの差分・コミットしていない変更・追跡していないファイルで決める
  - `mods/<mod>/` が変わった mod。中の mod が変われば jori も
  - ルートのビルド設定（`build.gradle`・`settings.gradle`・`gradle.properties`・`gradle/`）と `testkit/` が変わったら全 mod
  - `docs/`・README・`tools/` だけの変更では何も走らせない
  - 比べる先が見つからないときは止まる（差分なしとして成功にはしない）
- 合否は Gradle の終了コードとタスクの結果で決める。ログは失敗の中身を出すのに使う
  - mod のビルドは `:<mod>:build` が走って失敗していないときだけ OK。走らなかったら「走らず」で NG
  - Gradle がタスクの外（設定の段階・デーモンの起動など）で失敗したら、「What went wrong」を出して NG
  - 時間切れのクライアントだけを 1 つずつ走らせ直し、その回の結果だけで決める。テストの失敗は走らせ直さない
  - 最後に、check と同じソースどうしの食い違い（言語ファイルのキー・jori の一覧など）も確かめ、あれば NG
- `--scenario` は `-Pscenarios=...` になり、testkit の `Scenario` が名前の合わないシナリオを飛ばす。修正の途中は関わるシナリオだけを回し、コミットの前にはシナリオを付けずに走らせる。シナリオの名前は `docs/mods/<mod>.md` のゲームテストの節にある
- 描画にフックする mod（`mods/<mod>/gradle.properties` に `vanilla_render_test=true`。いまは xray・fullbright・minimap）は、Sodium + Iris でビルドとすべてのテストを走らせた後に、バニラ描画（`-PnoSodium`）でもう一度クライアントのテストを走らせる。両方が通ってはじめて OK
  - Sodium が置き換える経路とバニラの経路の両方にフックしているので、片方だけでは、もう片方で壊れたことに気づけない。ふだん遊ぶのは Sodium + Iris なので、そちらを先に走らせる
  - `--no-sodium` ならバニラ描画だけ（2 回目はない）。`--no-vanilla` で 2 回目を飛ばす（修正の途中に。コミットの前は飛ばさない）
  - 2 回目は Gradle を別に走らせる（`-PnoSodium` はビルド全体の設定なので、1 回の Gradle で両方は走らせられない）。時間切れの走らせ直しは回ごとに行う
  - ゲームのログとスクリーンショットは 2 回目で上書きされるので、1 回目の失敗の中身とスクリーンショットは 1 回目が終わったときに読んで写しておく。2 回目の前には、ゲームの実行フォルダのスクリーンショットを消す（2 回目が走らなかったときに 1 回目のものが混ざらないように）

### 結果の見方

| ファイル | 中身 |
|---|---|
| 画面の最後 | mod ごとに 1 行。2 回目にバニラ描画で走らせた mod は「バニラ描画」の結果も同じ行に出す。クライアントの結果の後ろに、かかった時間（`OK（372 秒: hud 41・map 95・…）`）。失敗なら例外とこのリポジトリのスタックの行（2 回目の分は頭に「（バニラ描画）」） |
| `build/dev/last-run.json`（`build/dev/runs/<日時>.json`） | 実行の記録。コミット・コミットしていない変更・選んだわけ・描画の構成（`plan.passes`）・Gradle の各回の終了コードとタスクの結果と描画の構成（`attempts`）・mod ごとの結果（`client` は 1 回目、`vanilla` はバニラ描画の 2 回目、`times` は構成ごとのかかった時間）・成果物の場所。直近の 30 回を残す |
| `build/dev/report.html` | 結果の表とすべてのスクリーンショット（人が見る用） |
| `build/dev/sheets/<mod>-<n>.png` | スクリーンショットを 20 枚ずつ並べた画像。見た目はまずこれを Read する。バニラ描画の 2 回目の分は `<mod>-vanilla-<n>.png`（写しは `build/dev/screenshots/<mod>-vanilla/`） |
| `build/dev/gradle.log` | Gradle の出力すべて（各回の終わりに終了コード） |
| `mods/<mod>/build/run/clientGameTest/logs/latest.log` | クライアントのゲームテストのゲームのログ |
| `mods/<mod>/build/run/gameTest/logs/latest.log` | サーバーのゲームテストのゲームのログ |

前の結果を引き継ぐときは、`last-run.json` の `commit` と `uncommitted` がいまの作業と同じかを先に見る。

### 失敗したとき

| 結果 | 見るところ |
|---|---|
| ビルド NG compileJava など | 画面のコンパイルエラーの行。なければ `gradle.log` |
| ビルド NG runGameTest | サーバーのゲームのログの `failed!` の行 |
| クライアント NG | 画面の例外とスタック。足りなければクライアントのゲームのログ |
| クライアント 時間切れ | 走らせ直しでも時間切れなら、ゲームのログの最後の行で止まった場所 |
| 走らず | 依存するタスクの失敗か、Gradle がタスクの外で失敗している。画面の「gradle がタスクの外で失敗した」と `gradle.log` |

## 食い違いの検査（check）

```bash
python tools/dev.py check            # ソースと、いまある jar
python tools/dev.py check --build    # jar を作ってから
```

- ソース: `fabric.mod.json` の id と version、`en_us.json` と `ja_jp.json` のキーがそろっている、`*.mixins.json` の `compatibilityLevel` が `JAVA_${java_version}`、アイコンが 128 × 128、`docs/mods/<mod>.md` と README の表に載っている、jori の `modules`・`JoriClient.FEATURES`・`jori.description.<mod>` が mod の一覧と合う
- jar: `fabric.mod.json` が UTF-8 として読め、置換文字や埋めていない `${...}` がなく、id・版・名前・説明が元と同じ。`fabric.mod.json` の `mixins` に並ぶ設定ファイルが入っていて、埋めていない `${...}` がなく、`compatibilityLevel` が `JAVA_<java_version>`。jori は同梱の一覧が `modules` の今の版と合い、同梱した各 jar の中身も同じように見る
- release は jar を作った後にこれを走らせ、食い違いがあればコミットもタグも作らない

## リリース（status・release）

```bash
python tools/dev.py status                              # 最後のタグの後にリリースしていない変更
python tools/dev.py release sorter minor --dry-run       # 何を上げるかだけ
python tools/dev.py release sorter minor --trailer "Co-Authored-By: ..." --push
python tools/dev.py release fullbright port minimap port # MC の移植。版はそのままでタグだけ
```

- status は、HEAD の祖先にある、いまの MC（`gradle.properties` の `minecraft_version`）のタグと比べる。旧 MC の保守ブランチでも、そのブランチの MC のタグを見る。いまの MC のタグがない mod は「移植だけなら release <mod> port」と出す
- release の順番
  1. 上げる mod・版・タグを先に決める（jori は中の mod の後。タグが既にあれば止まる）
  2. 版を書き換えた状態で jar を作り（`:<mod>:assemble`）、check の jar の検査をする
  3. 通ったら mod ごとにコミットしてタグを付ける。port の mod はいまのコミットにタグだけ付ける
- 2 で失敗したら版を元に戻して止まる。コミットもタグも残らないので、直してから同じコマンドをもう一度走らせればよい
- 配る jar は `mods/<mod>/build/libs/<mod>-<版>+<MC>.jar`。版を上げたコミットと jar の中身は同じ（jar を作ってからコミットまでに変わるのは、ほかの mod の `gradle.properties` だけ）

## クライアントのゲームテストの分け方

- 1 つの mod のテストが長くなったら、ほかと独立して走らせられるシナリオ（testkit の `Scenario`）に分ける。シナリオは自分でワールドを作り、前のシナリオの状態に頼らない
- gametest の `fabric.mod.json` の `fabric-client-gametest` にシナリオのクラスを並べる。Fabric はシナリオごとに、ゲームの設定・押しているキー・ウィンドウの大きさを戻し、タイトル画面に戻っているかを確かめる。どれかが失敗したらそこで止まる
- シナリオに共通の道具はその mod のテストのパッケージに置く（minimap は `MapTests`）。ほかの mod でも使うものは testkit に置く

## かかった時間

- `Scenario` は、始まりと終わりとかかった時間（`scenario <名前>: done in <秒> s`。失敗したら `failed`）をロガー `testkit` でゲームのログに出す。`System.out` はゲームのログ（`latest.log`）に入らず、Gradle の出力ではどの mod のものか分からないため
- test はクライアントのテストが終わるたびにゲームのログを読み、クライアントにかかった時間（ログの最初と最後の行の時刻の差。JVM と Gradle の起動は含まない）とシナリオごとの時間を、結果の行と `last-run.json` の `times` に載せる
- 遅いテストを分けたり速くしたりするときは、まずこの数字を見る。全体の時間は一番長いクライアント（いまは minimap）で決まる

## 文字コード

- `JAVA_HOME` が JDK 8 などだと、Gradle はデーモンにも同じ既定の文字コード（Windows では MS932）を渡す。Loom が jori の `fabric.mod.json` に同梱の一覧を書き足すときにこれを使い、日本語の説明が化けた（jori 1.9.0 の jar で見つかった）。`gradle.properties` の `org.gradle.jvmargs` に `-Dfile.encoding=UTF-8` を入れて防ぐ
- javac やゲームの出力は、それでも OS の文字コードのことがある。dev.py は UTF-8 で読めない行を cp932 で読む
