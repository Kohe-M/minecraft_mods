# AGENTS.md

Fabric mod（fullbright / minimap / peek / placer / refill / sorter / timber / xray / zoom）と、それらを 1 つにまとめた jori を `mods/` に置き、1つの Gradle マルチプロジェクトで管理するリポジトリ。構成は [README.md](README.md)。

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
2. `python tools/dev.py test` で、変更した mod のビルドとゲームテスト（server・client）を通す
3. コミットメッセージのルールに沿ってコミットする
4. リリースするなら `python tools/dev.py release <mod> <patch|minor|major>` で版を上げてタグを付ける（下の「タグ」）
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
  - jori に入っている mod を上げると、jori も一番大きい上げ方で一緒に上げる（`--no-bundle` で止める）
  - エージェントは `--trailer "Co-Authored-By: ..."` でコミットの末尾の行を足す。`--push` で push まで、`--dry-run` で何が上がるかだけ見る
- `python tools/dev.py status` で、最後のタグの後にリリースしていない変更がある mod が分かる

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
python tools/dev.py test                 # 上流のブランチから変わった mod を、ビルド + server・client のゲームテスト
python tools/dev.py test sorter jori     # mod を指定する
python tools/dev.py test --all           # 全 mod
python tools/dev.py test --no-sodium     # Sodium / Iris を入れずにバニラ描画で
python tools/dev.py shots                # いまあるスクリーンショットを一覧にまとめ直す
./gradlew build                          # ビルド + server ゲームテスト（sorter, timber）
./gradlew runClientGameTest              # 全 mod の client ゲームテスト
./gradlew :xray:runClient                # -PnoSodium でバニラ描画
```

- `dev.py test` は、jori に入っている mod が変わったら jori も走らせる。ルートのビルド設定（`build.gradle` など）が変わったら全 mod
- 結果は最後に mod ごとに 1 行で出る。失敗したら、例外とこのリポジトリのスタックの行も出す
  - `build/dev/report.html`: 結果の表とすべてのスクリーンショット（人が見る用）
  - `build/dev/sheets/<mod>-<n>.png`: スクリーンショットを 20 枚ずつ 1 枚に並べた画像。見た目はまずこれを Read して確かめる
  - `build/dev/gradle.log`: gradle の出力すべて
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

## mod の作り方

- パッケージは `com.omuraisu.<mod>`、mod ID はディレクトリ名と同じにする
- 新しい mod は `mods/<mod>` に作って `settings.gradle` の `include` に追加し、`mods/<mod>/gradle.properties` に `mod_version` を置く
- 言語ファイルは `en_us.json` と `ja_jp.json` の両方を用意する
- mod を追加したときや、クライアント・サーバーのどちらに入れるかが変わったときは、README の mod 表（クライアント / サーバー列）を更新する
  - 列の値は `必須` / `任意（入れると何ができるか）` / `不要`
  - `fabric.mod.json` の `environment` と食い違わないようにする（クライアントのみなら `client`）
- 片側にだけ入っていても落ちないようにする。相手側へのパケットは `ClientPlayNetworking.canSend` などで確かめてから送る
- アイコンは `assets/<mod>/icon.png`（128 × 128）。jori の一覧にも出るので、テンプレートのままにしない
- 新しい mod は jori にも入れる（下の「jori の仕様」）
  - `isActive()` / `setActive(boolean)` を用意し、false の間はバニラと同じにふるまう。ゲームテストで `setActive(false)` の間の動きを確かめる
  - `mods/jori/build.gradle` の `modules`、`JoriClient.FEATURES`（サーバーでも動くなら `Feature.SERVER` にも）、jori の言語ファイルの `jori.description.<mod>` に足す
- 中の mod を変えたら、その mod のリリースの後に jori の `mod_version` も上げてリリースする（jori の jar に入る版が変わるため）。`dev.py release` は自動で上げる

## Iris + Sodium 対応

Iris + Sodium の環境で遊ぶ前提。すべての mod をこの環境で動かす。

- Iris + Sodium あり・なしの両方で動くようにする。`fabric.mod.json` の `depends` には入れない
- 描画に関わる処理は、Sodium が置き換える経路にもフックする（例: チャンク構築は Sodium の `LevelSlice`）
  - Sodium のクラスへの mixin には `@Pseudo` を付けて、Sodium がなくても起動できるようにする
  - Sodium の API は `compileOnly "maven.modrinth:sodium:${sodium_version}"` で参照する
- Iris でシェーダーパックを有効にしても、表示が崩れたり効果が消えたりしないようにする
- 動作確認では `localRuntime` で Sodium と Iris を入れて起動し、`-PnoSodium` でバニラ描画も確かめる（`mods/xray/build.gradle`・`mods/zoom/build.gradle` 参照）
- Sodium・Iris のバージョンはルートの `gradle.properties` の `sodium_version`・`iris_version`。MC を上げたら一緒に上げる

## jori の仕様

ほかの mod をすべて 1 つにまとめ、mod ごとに有効・無効を切り替えられるようにした mod。クライアントに必須、サーバーは任意。

- 中身: 各 mod の jar をそのまま Jar-in-Jar（`include project(...)`）で入れる。mod ID・パケット・設定ファイル・キーは単体のときと同じ
  - 個別の mod と一緒に入れると、Fabric がバージョンの新しい方を使う。中の mod を変えたら、その mod の `mod_version` を上げてから jori をリリースする
  - jori の client のコードから各 mod の client のクラスを見るため、`mods/jori/build.gradle` で compile classpath を jar にしている（クラスのディレクトリだと main しか入らない）。gametest のソースセットを作った後に置かないと上書きされる
- 切り替え: 各 mod は `isActive()` / `setActive(boolean)` を持つ。false の間はバニラと同じにふるまい、その mod のキーも効かない（押した分は捨てる）。true に戻すと、キーで決めた状態（明るさや透過の ON/OFF など）に戻る
  - サーバーでも動く mod（minimap・sorter・timber）は main の `Minimap` / `Sorter` / `Timber` に、クライアントだけの mod は client の `<Mod>Client` に置く。シングルプレイではクライアントと内蔵サーバーが同じ値を見る
  - 切り替えはすぐ効く。xray は透過中なら全チャンクを描き直し、timber はサーバーに OFF と伝える
- 設定: `config/jori.txt`。1 行に「mod ID=true」か「mod ID=false」、書いていない mod は有効。知らないキーも消さずに書き戻す
- 一覧画面（`JoriScreen`）: タイルを 3 列に並べる。タイルはアイコン（各 mod の `assets/<mod>/icon.png`）・名前（`fabric.mod.json` の name）・スイッチだけ。押すとすぐ切り替えて保存する
  - 説明（jori の言語ファイルの `jori.description.<mod>`）と、その mod のキー（カテゴリの名前空間が mod ID のもの）はツールチップに出す
  - サーバーにも要る mod（sorter・timber）は、いまのサーバーが受け付けないときツールチップに「このサーバーでは使えません」を足す
  - 画面が狭いときは列を減らし、タイルを低くして収める。マウスで押したタイルにはフォーカスを残さない
- 入口: タイトル画面とポーズ画面の「設定」ボタンのすぐ上にあるアイコンの列（フレンド・言語など）の右端に、Jori のボタンを足して列を中央にそろえ直す。列がなければ「設定」の左に置く。Mod Menu には頼らない
- サーバー: 専用サーバーは起動するとき（`SERVER_STARTING`）に `config/jori.txt` を読み、minimap・sorter・timber を切り替える。3 つの行がなければ書き足す
  - 無効にした mod は、接続ごとの受け口（`ServerPlayConnectionEvents.INIT` で `registerReceiver`）を作らない。クライアントには mod のないサーバーと同じに見える（ソートボタンが出ない、など）
  - シングルプレイの内蔵サーバーは途中で切り替えられるので、受け口は常に作り、届いた時点で `isActive()` を確かめる
- ゲームテスト: 各 mod の止まり方（`setActive(false)` の間）は各 mod のテストで、画面・設定ファイル・専用サーバーは jori のテストで確かめる
  - クライアントのゲームテストの専用サーバーは同じ JVM で動くので、サーバーが読んだ設定はクライアントの `isActive()` にも及ぶ
  - テストから `setActive` を呼ぶときは `runOnClient` の中で呼ぶ（xray は `Minecraft.getInstance()` を使い、テストのスレッドからは呼べない）

## minimap の仕様

クライアントに必須、サーバーは任意。サーバーに入っていれば（シングルプレイは常に）マップ画面に描画距離の外の地形も出す。

- ミニマップ: 画面の右上に、北を上に固定して 160 ブロック四方を出す
  - 1 ブロックの大きさは実ピクセルで `round(ウィンドウの短辺 / 540)`（最小 1）。GUI スケールの設定には左右されない
  - ステータス効果のアイコンがあるときはその下に出す。F1 と F3 では隠す
  - 地形はクライアントが持っているチャンクだけ
- マップ画面（M キー）: 全画面の地図。ドラッグで移動、ホイールで拡大縮小
  - 倍率はゲームを終えるまで保ち、位置は開くたびに自分に戻す
- マップ画面の地形: 手元のチャンク（`MapTerrain`）の下に、サーバーから届いた遠くの地形（`FarTerrain`）を重ねる
  - 列の色の決め方は `TerrainColors` だけに書き、手元・サーバーが読み込んでいるチャンク・セーブから読んだチャンクで同じ色にする
  - クライアントは見えている範囲の届いていないチャンクを、画面の中心に近い順に 0.25 秒ごとに全部頼み直す（`TerrainRequest`）。未生成と答えられたもの（`TerrainMissing`）は `generate`（最大 256 個）に、ほかは `read`（最大 2048 個。パケットを 32 KiB 未満に収める）に入れ、生成を待つ間もセーブにある分が先に届くようにする。新しい依頼はサーバーの前の依頼を置き換え、閉じたら空の依頼で取り消す
  - 通信の形を変えたらパケットの ID（`far_terrain_v2/...`）も変える。版の違う相手には `canSend` が false になり、手元のチャンクだけになる（同じ ID で形が違うと切断される）
  - サーバー（`TerrainServer`）はプレイヤーのいるチャンクから 192 チャンク以内だけ答える。地図のためにチャンクをワールドに読み込まない（以前は 1 つずつ FULL まで読み込み、4K で一番引くとサーバーが数百秒遅れ、ヒープを使い切った）
    - メモリにあるチャンク: メインスレッドで色にする。1 tick に 2 ms まで。ワールドから外れて保存を待っているチャンク（`ChunkMap.pendingUnloads`）もセーブより新しいのでこちらで読み、生成の途中のチャンクも FEATURES 以降なら使う
    - セーブにあるチャンク: リージョンファイル（.mca）を、vanilla の IO スレッドを通さずに自前のスレッド（CPU の論理コア数 / 4、2〜8 本）で直接読み（`RegionReader`）、展開・読み解き・色づけまで同じスレッドで行う。読み解くのは DataVersion・xPos・zPos・Status と各セクションの Y・block_states だけ（光・バイオーム・エンティティは作らない）。色に要るセクションだけ解く（`StoredChunk`）。FEATURES より前の状態は未生成と同じ扱い
    - vanilla が同時に書いていることがあるので、読めない・xPos と zPos が合わない・別の版のデータ・外部ファイル（.mcc）のときは `ChunkMap.readChunk`（mixin の invoker。古い版のデータも新しい形に直る）で読み直す。ワールドから外れて 30 秒以内のチャンク（`ServerChunkEvents.CHUNK_UNLOAD`）は書く前のデータが IO スレッドにあるので、初めから `readChunk` で読む
    - 読み始めるのは 1 tick 512 個・同時に 1 スレッドあたり 128 個まで
    - 未生成のチャンク: `generate` のものだけ、セーブを確かめてから FEATURES まで生成する。60 秒以内に未生成と分かったものは確かめ直さない。光の計算やエンティティの読み込みをする FULL には進めず、FEATURES のまま保存される。札は生成し終えて 1 秒で外れる。生成し終えたチャンクの色づけも自前のスレッド
    - 同時に生成する数は 16 から始め、遅れていなければ 1 秒ごとに 8 ずつ増やし（上限 256）、遅れたら 1 秒ごとに半分にする（下限 4）。遅れの判定は、直近 20 tick にかかった実時間が予定の 1.1 倍を超える（tick の合間の処理も含むので、tick 時間の平均より正しい）・tick の平均が 40 ms を超える・ヒープの 85% を超える、のどれか。遅れている間は生成を始めず、読み始めるのも 1 tick 32 個にする
    - 答えたチャンクは、クライアントの次の依頼に反映されるまでの 2 秒は答え直さない。色は 1 tick ごとにまとめて送る（`TerrainTiles`、1 パケット 64 個まで）
  - `TerrainTile` は色 256 バイトと、北の端の行の比べる高さ・南の端の行の高さ（short 16 個ずつ）。北の端の行の明るさはクライアントが北のチャンク（届いた色か、手元に読み込まれたチャンク）の高さと比べて決め、北が後から届いたら決め直す。サーバーは北のチャンクを読まない
  - 届いた色と未生成の印は接続している間だけ次元ごとに覚え、開いたときに 224 チャンクより遠い分は捨てる。クライアントがチャンクを読み込んだら、そのチャンクの色は古いかもしれないので捨てる（離れたら頼み直す）
  - 届いた色はファイルにも残す（`FarCache`）。シングルプレイはセーブの中の `minimap_far/`、マルチプレイは `minimap_far/<URL エンコードしたアドレス>/`（ゲームのフォルダ）。次元ごとに 32 × 32 チャンクで 1 ファイル（`<名前空間>/<パス>/r.<x>.<z>.bin`、deflate）
    - マップ画面を開くと、224 チャンク以内のファイルを別のスレッドで読み、まだ届いていないチャンクに古い色として出す。古い色は、届いていないチャンクより後に頼み直す。未生成と答えられたら消す（別のワールドかもしれない）
    - 書くのはマップ画面を閉じたときと切断したとき。メモリにはリージョンの一部しかないことがあるので、ファイルにある分に届いた色を重ね、捨てたチャンクを消して書き直す
  - 32 × 32 チャンクを 1 枚のテクスチャにする。引いているときは 1 画素に 2・4 ブロックの間引いたテクスチャにして、画素数を画面の大きさ程度に抑える。テクスチャへの送信は tick ごとに画素数の上限までまとめて行う（チャンクごとに送ると重い）
  - 手元のテクスチャは読み込まれていない列を透明にして、下の遠くの地形を見せる
  - サーバーに mod がないとき（`ClientPlayNetworking.canSend` が false）は手元のチャンクだけ
- ピンの操作はマップ画面でだけ行う。刺すキー（既定は B、`key.minimap.place_pin`）もマップ画面の中でだけ効く

| 操作 | 場所 | 動作 |
|---|---|---|
| 刺すキー | 何もないところ | カーソルの位置に刺して名前の入力へ。Enter か外のクリックで確定、Esc で入力だけ取り消し |
| Shift + 左クリック | ピンの上 | 抜く |
| 右クリック | ピンの上 | 名前の変更 |

  - ただの左クリックはドラッグにだけ使い、刺しも抜きもしない（位置を確かめようとして操作してしまわないため）。ピンの上の刺すキーと、何もないところの Shift + 左クリックでも何もしない
  - 刺すキーは操作設定で変えられ、マウスのボタンにも割り当てられる。押したキーの文字は、キーを離すまで名前に入れない（バニラのクリエイティブの検索と同じ）
  - 刺す・抜くの操作は画面の左下に、いまのキーの名前で出す。名前の入力中は隠す
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
  - 遠くの地形は、前のテストで開いたときの色が残っていることがある。確かめるチャンクには一度近づいてから離れる
  - 地図のためにワールドへ読み込んでいないことは、`ServerChunkEvents.CHUNK_LOAD`（FULL になったチャンク）と `getChunkNow` で確かめる。セーブから読む経路を確かめるときは、置いたチャンクがメモリから外れる（`getVisibleChunkIfPresent` と `pendingUnloads` から消える）のを待つ。プレイヤーの札は描画距離の外にも生成の段階の強さで及び、その間チャンクはメモリに残るので、描画距離 + 24 チャンクより遠くに置く
  - 負荷は、自然な地形の新しいワールドを 3840 × 2160 で一番引いて開き、ワールドに読み込んだチャンクの数とサーバーの tick 時間で確かめる。開き直すと、前に届いた色がファイルからすぐ出て、生成し終えた分がセーブから 10 秒以内に届き直す。セーブはほぼすべて `RegionReader` で読めている（`TerrainServer.readCounts()`）

## peek の仕様

クライアントのみで動く。サーバー側の mod は不要で、バニラのサーバーでも使える（中身はアイテムの `DataComponents.CONTAINER` としてクライアントに届く）。キー割り当てや設定はない。

- 対象: `ShulkerBoxBlock` のブロックアイテム（無色と 16 色）。チェストなど他の入れ物はバニラのまま
- 出す条件: 中身が 1 つ以上あり、`TooltipDisplay` で `CONTAINER` を隠していないとき。空の箱には何も足さない
- 表示: ツールチップの名前のすぐ下に、インベントリと同じスロット（`container/slot`）を 9 列 × 3 段で並べ、開いたときと同じ位置にアイコン・個数・耐久値バーを描く
  - 1 個だけでも 3 段すべてを出す（どこに入っているかが分かるように）
  - バニラが中身を文字で並べる行（「名前 x個」「ほか N 個」）は出さない
- 仕組み: `ItemStack.getTooltipImage` を mixin で差し替えて `ShulkerPreview`（`TooltipComponent`）を返し、Fabric の `ClientTooltipComponentCallback` で描画用の `ClientShulkerPreview` に変える。文字の行は `ItemStack.addToTooltip` で `CONTAINER` の分だけ止める
- ゲームテスト: 実際にカーソルをスロットに置き、描かれたツールチップを確かめる
  - 描かれた中身は、テスト側の mixin で `GuiGraphicsExtractor.tooltip` に渡された部品を記録して見る
  - インベントリ・チェスト・クリエイティブ・専用サーバーで動かす。チェストは `closeContainer` で閉じないとサーバーが開いたままの扱いになる

## placer の仕様

クライアントのみで動く。サーバー側の mod は不要。J キーで有効・無効を切り替え、`config/placer.txt` に保存する。無効ならバニラと同じ。

- 補正するのは 1 回の長押しの間の連続設置だけ。押した瞬間の設置はバニラのまま（押し直せば積める）
- 長押しで最初に置けたブロックの面から層を決める。上面に置いたら同じ高さ、側面に置いたらその面に平行な 1 枚だけに置く。草などを置き換えたときは上面の扱い
- 同じ長押しの中で同じマスには 1 回しか試さない（ハーフブロックが重なってフルブロックになるのも防ぐ）
- 視線が自分の置いたブロックに当たっている間は置かない。例外は、当たった面の向きへ前回の設置から 0.5 ブロック以上動いていて、置き先がプレイヤーから 1 ブロック以内のとき（スニークでの橋渡し、ジャンプでの塔）
- 置けないマス（体と重なるなど）は試さない。置けるマスに狙いが移ったら 4 tick を待たずに置く。ただし押してから 4 tick はバニラと同じに待つ（軽く押しただけで 2 個置かれないように）
- 置くのは視線が実際に当たった面だけで、当たり判定は作らない。自分のブロックに隠れたマスには置けない
- 足場（`ScaffoldingBlockItem`）は上へ伸ばして使うのでバニラのまま。最初の設置がまだなら手に持つものによらずバニラのまま（コンポスターへの連続投入など）

## refill の仕様

クライアントのみで動く。サーバー側の mod は不要で、バニラのサーバーでも使える。キー割り当てや設定はない。

- 動作: 使ったアイテムで手が空になったら、同じ tick のうちに補充する。メインハンドもオフハンドも対象
  - 使ったかどうかは Fabric の `UseBlockCallback`・`UseItemCallback`・`UseEntityCallback` で覚えておき、tick の終わりに手が空かどうかで判定する
  - 骨粉・繁殖の餌・スポーンエッグなどはサーバーだけが減らすので、手が空になるのは少し後になる。そのため覚えた内容に期限は設けない
- 補充元: メインインベントリ 27 マスのうち、同じアイテムで一番少ないスタック。`ItemStack.isSameItemSameComponents` で比べる
  - ホットバーの他のスロットからは取らない（並びを崩さないため）
  - 名前や耐久値が違うものは別物。壊れた道具は補充しない
- 補充しない: 手が空になった原因が使用以外のとき
  - Q・Ctrl + Q・F（`ServerboundPlayerActionPacket` を mixin で見る）、画面を開いている間の移動、持ち替えたスロット
  - シチューや水バケツのように器が手に残るものは、空にならないので補充しない
- 補充はインベントリのスワップ（`ContainerInput.SWAP`）。入れ替えるだけなのでアイテムは増減せず、サーバーと食い違っても stateId のずれから全体が同期し直される
- ゲームテスト: 実際の右クリックで確かめる。シングルプレイと専用サーバーの両方で動かす
  - 置いた直後の tick に Q を押すと、バニラでも手の個数がサーバーとずれる（Q の予測の後に、置いた分の応答が届いて上書きされ、訂正は来ない）。テストでは同期を待ってから次のキーを押す

## sorter の仕様

サーバー側でソートする。クライアントとサーバーの両方に必要で、サーバーに無ければボタンを出さない。

- 対象: チェスト系の中身全体と、プレイヤーのメインインベントリ 27 マス。ホットバー・防具・オフハンドは動かさない
- 手順: 同じアイテムをまとめる → `SortOrder` で並べる → `SortLayout` で置き場所を決める
  - 行そろえは 1.1.0 で入れ、1.2.0 で一度廃止し、ユーザーの判断で 1.4.0 で戻した。外すかどうかはユーザーが決める
- 守ること: 元の並びに結果が左右されない、2 回押しても変わらない、アイテムが増減しない

並び順（`SortOrder`）。グループの順に並べ、同じグループの中は次の表の順にする。

| グループ | 中の順 |
|---|---|
| 道具 | ツルハシ・斧・シャベル・クワ・その他（耐久値のあるもの）。種類ごとに良い素材から |
| 武器 | 剣・槍・メイスとトライデント・弓とクロスボウ・矢・盾・その他。剣と槍は良い素材から |
| 防具 | 良い素材のセットから、セットの中は頭から足へ |
| 食料 | 満腹度 + 隠し満腹度が高い順。50% 以上で有害な効果が付くもの（腐った肉など）は素材の末尾へ |
| ポーション・飲み物 | クリエイティブタブの順 |
| 道具類 | 松明が先、残りはタブの順 |
| エンチャントの本 | エンチャント名、次に高いレベルから |
| 素材 → 機能ブロック → レッドストーン → 建材 → 色付きブロック → 自然ブロック → スポーンエッグ | タブの順 |

- タブに載らない mod のアイテムはブロックなら建材、それ以外は素材の扱い
- 複数のタブに載るアイテムは `TAB_PRIORITY` の先のタブの扱い
- 同じアイテムは エンチャントの合計レベル → 残り耐久 → 名前 → コンポーネント → 個数 の順で、ハッシュ値は使わない
- タブの中身は `tab.buildContents` で最初のソート時に作る（専用サーバーでは他に誰も作らない）。リストの参照を差し替えるだけで、`op_blocks` は触らない

置き場所（`SortLayout`）。空きがあれば行頭にそろえて、グループの切れ目が見て分かるようにする。

- 各グループを直前の続きに置くか次の行頭に置くかを、コストの合計が最小になるように DP で決める
- コスト: 必要以上の行にまたがる 10、関連の薄いグループと同じ行 5、関連の深いグループ（`Group.band` が同じ）と同じ行 2、空きスロット 1 つにつき 0.5
- 行の幅は、範囲の先頭のスロットと同じ高さにあるスロットの数
- 満杯なら空きを作れないので詰めて並ぶ
- 空きがあると、新しく入れたアイテムはその隙間に入る。もう一度押せば直る

評価（`SortEvaluation`）。並びを変えるときは `beatsOtherOrdersOnEvaluation` のログ（`EVAL` の行）で比べ、`runClientGameTest` の `sorter-scenario-*` の画面も見る。

- 点数 = 100 ×（0.30 まとまり + 0.15 隣の関連 + 0.20 手の届きやすさ + 0.15 行の区切り + 0.20 位置の安定）
- 分類にはクリエイティブタブを使う。重みと「手の届きやすさ」のタブごとの使用頻度は判断で決めた値なので、点数は人の感覚の代わりにはならない
- テストでは、新しい並び（行そろえあり）がどの場面でも他の並び（行そろえなしの同じ順を含む）を上回ることを確かめる
- 根拠にしたプレイヤーの声: カテゴリでまとめたい、ホットバーは動かさないでほしい、結果を予測できてほしい（Terraria・7 Days to Die の Steam 掲示板、Minecraft のフィードバックサイト、Inventory Sorter の issue）

## Minecraft 26.3 の注意点

MC を上げたら、この節を新しいバージョンの内容に書き換える。

- 非難読化・Mojang 名
- 描画は `extract*` / `GuiGraphicsExtractor`、画面は `Minecraft.gui.screen()` / `gui.setScreen()`
- `InputConstants.MOUSE_BUTTON_LEFT` は 1（GLFW の 0 ではない）。クライアントゲームテストの `pressMouse` もこれを使う
- 全チャンクの再構築は `Minecraft.levelExtractor.allChanged()`（F3+A と同じ）。`levelRenderer.invalidateCompiledGeometry` を直接呼ぶと、素の描画では再構築が予約されず何も描かれなくなる
- クライアントゲームテストの入力は、マウスのイベントに修飾キーを載せない。Shift の判定は `event.hasShiftDown()` ではなく `Minecraft.hasShiftDown()` を使う
- `GameRenderer.projectPointToScreen` はカメラの後ろの点も画面の中に写す。前後はカメラの向きとの内積で判定する
- `ClientLevel.hasChunk` は常に true を返す。クライアントがチャンクを持っているかは `getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) != null` で見る
- `ServerChunkCache.getChunkFuture(..., true)` が付ける札（`TicketType.UNKNOWN`）は 1 tick で外れ、生成が終わる前に捨てられることがある。非同期に読み込むときは `FLAG_CAN_EXPIRE_IF_UNLOADED` のない `TicketType` の札を付ける。時間は `ChunkHolder.isReadyForSaving`（生成の途中でない）になってから数えるので、FULL より前の状態まで（`ChunkLevel.byStatus`）の札でも外れる
- `IOWorker` は読み込みを保存より先に回す。読み込みを詰め続けると保存が進まず、書く前のチャンクがメモリに溜まる。読み込みは IO スレッド 1 本で展開・読み解きまで行うので、多く読むならリージョンファイルを自分で読む
- `ServerChunkCache.getChunkFuture` をメインスレッドから呼ぶと、そのチャンクができるまでメインスレッドで待つ（`managedBlock`）。待たずに頼むときは別のスレッドから呼ぶ（メインスレッドへのタスクになる）
- サーバーの遅れは `getNextTickTime()` と今の時刻の差では測れない（クライアントのゲームテストではいつも 0.5 秒ほど遅れて見える）。直近の数 tick にかかった実時間で測る
- ワールドから外れたチャンクの保存（`ChunkMap.processUnloads`）は、待ちが 2000 を超えた分と、tick の合間に時間が余っている間だけ進む。クライアントのゲームテストの内蔵サーバーは時間が余らない扱いなので、多くのチャンクを生成した後は 2000 個が保存待ちのまま残る（`pendingUnloads`）。テストではメモリにある方の状態も見る
- `CommandEncoder.writeToTexture` は呼ぶたびに重い。小さい範囲を何度も送らず、まとめて送る
- クラスの確認は `~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/<MC>/*.jar` を javap する
