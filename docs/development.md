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
python tools/dev.py test xray --no-dh        # Distant Horizons と併用する mod でも、DH 入りの回を飛ばす
```

- 対象は、比べる先からの差分・コミットしていない変更・追跡していないファイルで決める
  - `mods/<mod>/` が変わった mod。中の mod が変われば jori も
  - ルートのビルド設定（`build.gradle`・`settings.gradle`・`gradle.properties`・`gradle/`）と `testkit/` が変わったら全 mod
  - `docs/`・README だけの変更では何も走らせない
  - `tools/` が変わったら、dev.py のテスト（下の「dev.py のテスト」）だけを走らせる。mod の jar とゲームテストには響かないので mod は走らせない。`--all` でも走らせる
  - 比べる先が見つからないときは止まる（差分なしとして成功にはしない）
- 合否は Gradle の終了コードとタスクの結果で決める。ログは失敗の中身を出すのに使う
  - mod のビルドは `:<mod>:build` が走って失敗していないときだけ OK。走らなかったら「走らず」で NG
  - Gradle がタスクの外（設定の段階・デーモンの起動など）で失敗したら、「What went wrong」を出して NG
  - 時間切れのクライアントだけを 1 つずつ走らせ直し、その回の結果だけで決める。テストの失敗は走らせ直さない
  - 最後に、check と同じソースどうしの食い違い（言語ファイルのキー・jori の一覧など）も確かめ、あれば NG
- `--scenario` は `-Pscenarios=...` になり、testkit の `Scenario` が名前の合わないシナリオを飛ばす。修正の途中は関わるシナリオだけを回し、コミットの前にはシナリオを付けずに走らせる。シナリオの名前は `docs/mods/<mod>.md` のゲームテストの節にある
- 描画にフックする mod（`mods/<mod>/gradle.properties` に `vanilla_render_test=true`。いまは digger・fullbright・minimap・xray）は、Sodium + Iris でビルドとすべてのテストを走らせた後に、バニラ描画（`-PnoSodium`）でもう一度クライアントのテストを走らせる。両方が通ってはじめて OK
  - Sodium が置き換える経路とバニラの経路の両方にフックしているので、片方だけでは、もう片方で壊れたことに気づけない。ふだん遊ぶのは Sodium + Iris なので、そちらを先に走らせる
  - `--no-sodium` ならバニラ描画だけ（2 回目はない）。`--no-vanilla` で 2 回目を飛ばす（修正の途中に。コミットの前は飛ばさない）
  - 2 回目は Gradle を別に走らせる（`-PnoSodium` はビルド全体の設定なので、1 回の Gradle で両方は走らせられない）。時間切れの走らせ直しは回ごとに行う
  - ゲームのログとスクリーンショットは 2 回目で上書きされるので、1 回目の失敗の中身とスクリーンショットは 1 回目が終わったときに読んで写しておく。2 回目の前には、ゲームの実行フォルダのスクリーンショットを消す（2 回目が走らなかったときに 1 回目のものが混ざらないように）
- Distant Horizons と併用する mod（`distant_horizons_test=true`。いまは digger・fullbright・jori・minimap・xray・zoom）は、バニラ描画の後に DH 入り（`-PdistantHorizons`）でもう一度クライアントのテストを走らせる。順番は Sodium + Iris → バニラ描画 → DH。3 つとも通ってはじめて OK
  - DH の回はシナリオ `distant-horizons` だけを走らせる（`-Pscenarios=distant-horizons`）。DH は遠くの地形を生成し続けるので、生成の速さを測る minimap のテストなど、ほかのシナリオの時間や画素が揺れるため。DH と併用する mod が崩れていないかは、その mod の `distant-horizons` シナリオで確かめる
  - `--no-dh` で DH の回を飛ばす。`--no-sodium` と `--no-client` のときは走らせない。`--scenario` で絞ったときは、`distant-horizons` を指定したときだけ走らせる（シナリオを絞らずに走らせるコミットの前は、DH の回も走る）
  - DH の回も Gradle を別に走らせる（`-PdistantHorizons` もビルド全体の設定）。スクリーンショットとゲームのログはバニラ描画の回と同じく上書きされるので、回ごとに写して消す

### 結果の見方

| ファイル | 中身 |
|---|---|
| 画面の最後 | mod ごとに 1 行。2 回目以降にバニラ描画・Distant Horizons で走らせた mod は、その結果も同じ行に出す。クライアントの結果の後ろに、かかった時間（`OK（372 秒: hud 41・map 95・…）`）。失敗なら例外とこのリポジトリのスタックの行（2 回目以降の分は頭に「（バニラ描画）」「（Distant Horizons）」） |
| `build/dev/last-run.json`（`build/dev/runs/<日時>.json`） | 実行の記録。コミット・コミットしていない変更・選んだわけ・描画の構成（`plan.passes`）・Gradle の各回の終了コードとタスクの結果と描画の構成（`attempts`）・mod ごとの結果（`client` は 1 回目、`vanilla` はバニラ描画の 2 回目、`dh` は Distant Horizons 入りの回、`times` は構成ごとのかかった時間）・成果物の場所。直近の 30 回を残す |
| `build/dev/report.html` | 結果の表とすべてのスクリーンショット（人が見る用） |
| `build/dev/sheets/<mod>-<n>.png` | スクリーンショットを 20 枚ずつ並べた画像。見た目はまずこれを Read する。バニラ描画の回の分は `<mod>-vanilla-<n>.png`、Distant Horizons 入りの回の分は `<mod>-dh-<n>.png`（写しは `build/dev/screenshots/<mod>-vanilla/`・`<mod>-dh/`） |
| `build/dev/gradle.log` | Gradle の出力すべて（各回の終わりに終了コード） |
| `mods/<mod>/build/run/clientGameTest/logs/latest.log` | クライアントのゲームテストのゲームのログ |
| `mods/<mod>/build/run/gameTest/logs/latest.log` | サーバーのゲームテストのゲームのログ |

前の結果を引き継ぐときは、`last-run.json` の `commit` と `uncommitted` がいまの作業と同じかを先に見る。記録には、走らせ始めたときの中身（`content`）・シナリオで絞ったか（`filtered`）・描画の構成（`renders`）も残る。release はこれを見る（「テストの記録」）。

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
  2. 上げる mod（一緒に上げる jori も）ごとに、いまと同じ中身で絞らずに通した test の記録（`build/dev/runs/`）があるかを確かめる。なければ止まる（下の「テストの記録」）
  3. 版を書き換えた状態で jar を作り（`:<mod>:assemble`）、check の jar の検査をする
  4. 通ったら mod ごとにコミットしてタグを付ける。port の mod はいまのコミットにタグだけ付ける
- 3 で失敗したら版を元に戻して止まる。コミットもタグも残らないので、直してから同じコマンドをもう一度走らせればよい
- ローカルには `mods/<mod>/build/libs/<mod>-<版>+<MC>.jar` ができる。`--push` でブランチを先に push し、作ったタグを 1 件ずつ送る。GitHub は 4 件以上のタグをまとめて push すると Actions を起動しないため、後から push するときも `release` が表示するコマンドを順に使う
- GitHub Actions の `Release jar` はタグのコミットで `python3 tools/dev.py check --build <mod>` を実行し、検査が通った jar をそのタグの GitHub Release に添付する。jori の中身もタグの時点の版で検査する
- ビルドや公開に失敗したら、Actions で失敗したジョブを再実行する。タグがすでに GitHub にあり、実行が作られていない場合は `Release jar` の **Run workflow** でタグを指定する。`publish` をオンにすると、検査の後に公開する

### テストの記録

テストを通していない中身をリリースしないよう、release は test の記録を確かめる。`--dry-run` でも結果を出す。確かめずに進めるなら `--no-test-check`。

- test は走らせ始めたときに、作業ツリーの中身を表す値を記録に残す（`last-run.json` の `content`）。git の tree（コミットしていない変更・追跡していないファイルも含め、同じ中身なら同じ値。本物のインデックスには触らず、写しに `git add -A` して作る）と、mod ごとの値（`content.keys`）
  - mod ごとの値は、`mods/<mod>`（jori は中の mod も）・ルートのビルド設定（`build.gradle`・`settings.gradle`・`gradle.properties`・`gradle/`）・`testkit/`・`LICENSE` の git のハッシュから作る
  - docs・README・AGENTS.md・`tools/` は含めない。jar にもゲームテストにも入らず、変わってもテストの結果は変わらないので、test の後に docs だけのコミットが入っても通す。jar とゲームテストに入るものが 1 つでも変われば値が変わり、test を走らせ直す
- release は、版を書き換える前の中身で mod ごとの値を作り、同じ値で次の条件を満たす記録を新しい順に探す
  - シナリオで絞っていない（`--scenario` なし）、クライアントのテストを飛ばしていない（`--no-client` なし）。記録の `filtered` が false
  - その mod の結果が OK。クライアントのテストがある mod は Sodium + Iris で通っている（`--no-sodium` だけの記録は使わない）。描画にフックする mod は、バニラ描画の 2 回目も通っている。Distant Horizons と併用する mod は、DH の回も通っている（`dh` が OK。DH の回がない記録は使わない）
- 中の mod だけを `--no-bundle` でリリースした後に jori を別にリリースすると、jori の値（中の mod の `gradle.properties`）が変わっているので、jori の test を走らせ直す。ふつうは jori も一緒に上げるので、走らせ直さなくてよい
- 記録は直近の 30 回しか残らないので、test の後に何度も test を走らせると探せなくなることがある

## クライアントのゲームテストの分け方

- 1 つの mod のテストが長くなったら、ほかと独立して走らせられるシナリオ（testkit の `Scenario`）に分ける。シナリオは自分でワールドを作り、前のシナリオの状態に頼らない
- gametest の `fabric.mod.json` の `fabric-client-gametest` にシナリオのクラスを並べる。Fabric はシナリオごとに、ゲームの設定・押しているキー・ウィンドウの大きさを戻し、タイトル画面に戻っているかを確かめる。どれかが失敗したらそこで止まる
- シナリオに共通の道具はその mod のテストのパッケージに置く（minimap は `MapTests`）。ほかの mod でも使うものは testkit に置く

## かかった時間

- `Scenario` は、始まりと終わりとかかった時間（`scenario <名前>: done in <秒> s`。失敗したら `failed`）をロガー `testkit` でゲームのログに出す。`System.out` はゲームのログ（`latest.log`）に入らず、Gradle の出力ではどの mod のものか分からないため
- test はクライアントのテストが終わるたびにゲームのログを読み、クライアントにかかった時間（ログの最初と最後の行の時刻の差。JVM と Gradle の起動は含まない）とシナリオごとの時間を、結果の行と `last-run.json` の `times` に載せる
- 遅いテストを分けたり速くしたりするときは、まずこの数字を見る。全体の時間は一番長いクライアント（いまは minimap）で決まる

## dev.py のテスト

```bash
python -m unittest discover -s tools       # tools/test_dev.py。数秒で終わる
```

- 標準の unittest で、git や Gradle は動かさない。`mods/` と記録は一時フォルダに作り、`dev.py` の場所（`ROOT`・`MODS_DIR`・`OUT`・`RUNS`）と git を呼ぶ関数を差し替える
- 確かめること: 変わったファイルから選ぶ mod（`select_mods`・`tools_changed`）、`gradle.properties` の読み方（`client_gametest`・`vanilla_render_test`・`distant_horizons_test`）、版の上げ方（`bump`）、jori の連動（`plan_release`）、Gradle のログの読み方（`Attempt`。失敗・時間切れ・タスクの外の失敗）、描画の構成ごとの計画と結果（`plan_test`・`mod_result`。バニラ描画と Distant Horizons の回、`--no-dh`・`--no-sodium`・`--scenario`）、名前で組み立てるオプション（`gradle_options`）、かかった時間（`client_timings`）、テストの記録の探し方（`content_keys`・`passing_record`。DH の回の有無）と release が版を書き換える前に止まること、jar の mixins.json の検査
- dev.py の判定を変えたら、ここにもテストを足す。ゲームテストと違って Minecraft を起動しないので、判定の細かい場合分けはこちらで確かめる

## 文字コードと改行

- ファイルは UTF-8 で、改行は LF。`.gitattributes`（`* text=auto eol=lf`。`*.bat` は CRLF、png と jar は binary）で、リポジトリにも作業ツリーにも LF で入る。Windows の `core.autocrlf=true` のままでも CRLF にならない
  - 以前は `.gitattributes` が `gradlew` と `*.bat` しか決めておらず、`core.autocrlf` で作業ツリーの一部のファイルだけが CRLF になっていた（リポジトリの中はすべて LF）。jar の中のリソースの改行が、チェックアウトのしかたで変わっていた
- エディタの設定は `.editorconfig`（UTF-8・LF・最後に改行。Java・Gradle・JSON はタブ、Python は空白 4）
- `JAVA_HOME` が JDK 8 などだと、Gradle はデーモンにも同じ既定の文字コード（Windows では MS932）を渡す。Loom が jori の `fabric.mod.json` に同梱の一覧を書き足すときにこれを使い、日本語の説明が化けた（jori 1.9.0 の jar で見つかった）。`gradle.properties` の `org.gradle.jvmargs` に `-Dfile.encoding=UTF-8` を入れて防ぐ
- javac やゲームの出力は、それでも OS の文字コードのことがある。dev.py は UTF-8 で読めない行を cp932 で読む
