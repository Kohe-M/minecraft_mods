# jori

ほかの mod をすべて 1 つにまとめ、mod ごとに有効・無効を切り替えられるようにした mod。クライアントに必須、サーバーは任意。

## 中身

各 mod の jar をそのまま Jar-in-Jar（`include project(...)`）で入れる。mod ID・パケット・設定ファイル・キーは単体のときと同じ。

- 個別の mod と一緒に入れると、Fabric がバージョンの新しい方を使う。中の mod を変えたら、その mod の `mod_version` を上げてから jori をリリースする
- jori の client のコードから各 mod の client のクラスを見るため、`mods/jori/build.gradle` で compile classpath を jar にしている（クラスのディレクトリだと main しか入らない）。gametest のソースセットを作った後に置かないと上書きされる

## 切り替え

各 mod は `isActive()` / `setActive(boolean)` を持つ。false の間はバニラと同じにふるまい、その mod のキーも効かない（押した分は捨てる）。true に戻すと、キーで決めた状態（明るさや透過の ON/OFF など）に戻る。

- サーバーでも動く mod（digger・minimap・sorter・timber）は main の `Digger` / `Minimap` / `Sorter` / `Timber` に、クライアントだけの mod は client の `<Mod>Client` に置く。シングルプレイではクライアントと内蔵サーバーが同じ値を見る
- 切り替えはすぐ効く。xray は透過中なら全チャンクを描き直し、timber はサーバーに OFF と伝える

## 設定

`config/jori.txt`。1 行に「mod ID=true」か「mod ID=false」、書いていない mod は有効。知らないキーも消さずに書き戻す。

- 行ごとに読み、キーと値の前後の空白は無視する。「=」のない行（壊れた行・メモ）は読み飛ばし、書き戻すときにも残さない
- 書けなかったときはログに 1 行出す（切り替えはその起動の間は効くが、黙って捨てない）

## 一覧画面（`JoriScreen`）

タイルを 3 列に並べる。タイルはアイコン（各 mod の `assets/<mod>/icon.png`）・名前（`fabric.mod.json` の name）・スイッチだけ。押すとすぐ切り替えて保存する。

- 説明（jori の言語ファイルの `jori.description.<mod>`）と、その mod のキー（カテゴリの名前空間が mod ID のもの）はツールチップに出す
- サーバーにも要る mod（digger・sorter・timber）は、いまのサーバーが受け付けないときツールチップに「このサーバーでは使えません」を足す
- 画面が狭いときは列を減らし、タイルを低くして収める。マウスで押したタイルにはフォーカスを残さない

## 入口

タイトル画面とポーズ画面の「設定」ボタンのすぐ上にあるアイコンの列（フレンド・言語など）の右端に、Jori のボタンを足して列を中央にそろえ直す。列がなければ「設定」の左に置く。Mod Menu には頼らない。

## サーバー

専用サーバーは起動するとき（`SERVER_STARTING`）に `config/jori.txt` を読み、digger・minimap・sorter・timber を切り替える。4 つの行がなければ書き足す。

- 無効にした mod は、接続ごとの受け口（`ServerPlayConnectionEvents.INIT` で `registerReceiver`）を作らない。クライアントには mod のないサーバーと同じに見える（ソートボタンが出ない、など）
- シングルプレイの内蔵サーバーは途中で切り替えられるので、受け口は常に作り、届いた時点で `isActive()` を確かめる

## ゲームテスト

- 各 mod の止まり方（`setActive(false)` の間）は各 mod のテストで、画面・設定ファイル・専用サーバーは jori のテストで確かめる
- クライアントのゲームテストの専用サーバーは同じ JVM で動くので、サーバーが読んだ設定はクライアントの `isActive()` にも及ぶ
- テストから `setActive` を呼ぶときは `runOnClient` の中で呼ぶ（xray は `Minecraft.getInstance()` を使い、テストのスレッドからは呼べない）
- `distant-horizons` シナリオ（`DistantHorizonsTest`）は、DH が入っていないときは何もせず戻る。スーパーフラットの地面から 60 ブロックほど上に足場を作り、200 ブロック先の LOD の地面を見る
  - 全 mod が有効な状態で LOD が描かれることを `Lods.waitForRendering` で確かめ、ポーズ画面の Jori の一覧から全タイルを押して無効にしても LOD が描かれ続けること、戻しても描かれることを確かめる。jori の切り替えは LOD の描画に関わらない
  - 一覧は `Widgets.click` で mod の名前（`fabric.mod.json` の name）を押す。終わったら全 mod を有効に戻す
