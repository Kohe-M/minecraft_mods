# AGENTS.md

Fabric mod（fullbright / minimap / peek / placer / refill / sorter / timber / xray / zoom）を `mods/` にまとめ、1つの Gradle マルチプロジェクトで管理するリポジトリ。構成は [README.md](README.md)。

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

- scope は mod 名（`fullbright` / `minimap` / `peek` / `placer` / `refill` / `sorter` / `timber` / `xray` / `zoom`）。複数の mod やリポジトリ全体に関わるときは省く
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
./gradlew :fullbright:runClientGameTest :minimap:runClientGameTest :peek:runClientGameTest :placer:runClientGameTest :refill:runClientGameTest :sorter:runClientGameTest :xray:runClientGameTest :zoom:runClientGameTest
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

クライアントに必須、サーバーは任意。サーバーに入っていれば（シングルプレイは常に）マップ画面に描画距離の外の地形も出す。

- ミニマップ: 画面の右上に、北を上に固定して 160 ブロック四方を出す
  - 1 ブロックの大きさは実ピクセルで `round(ウィンドウの短辺 / 540)`（最小 1）。GUI スケールの設定には左右されない
  - ステータス効果のアイコンがあるときはその下に出す。F1 と F3 では隠す
  - 地形はクライアントが持っているチャンクだけ
- マップ画面（M キー）: 全画面の地図。ドラッグで移動、ホイールで拡大縮小
  - 倍率はゲームを終えるまで保ち、位置は開くたびに自分に戻す
- マップ画面の地形: 手元のチャンク（`MapTerrain`）の下に、サーバーから届いた遠くの地形（`FarTerrain`）を重ねる
  - 列の色の決め方は `TerrainColors` だけに書き、手元と遠くで同じ色にする
  - クライアントは見えている範囲のうち、手元で描けないチャンクを画面の中心に近い順に `TerrainRequest`（最大 1024 個）で頼む。0.5 秒ごとに頼み直し、新しい依頼はサーバーの待ち行列を置き換える。閉じたら空の依頼で取り消す
  - サーバーはプレイヤーのいるチャンクから 192 チャンク以内だけ、1 人あたり同時に 256 個まで非同期に読み込む。未生成なら生成してセーブに残る（事前生成と同じ）。読み込みには tick させない独自の札を付け、読み込み終えて 1 秒で外れる
  - 北の端の起伏のため、ひとつ北のチャンクも読み込む。色はメインスレッドで計算して `TerrainTile`（1 チャンク 256 バイト）で返す
  - 届いた色は接続している間だけ次元ごとに覚え、開いたときに 224 チャンクより遠い分は捨てる。クライアントがチャンクを読み込んだら、そのチャンクの色は古いかもしれないので捨てる（離れたら頼み直す）
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
- 手順: 同じアイテムをまとめる → `SortOrder` で並べる → 左上から隙間なく詰める
  - グループごとに行を分ける配置は 1.1.0 で試し、ユーザーの判断で 1.2.0 で廃止した。入れ直さない
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

評価（`SortEvaluation`）。並びを変えるときは `beatsOtherOrdersOnEvaluation` のログ（`EVAL` の行）で比べ、`runClientGameTest` の `sorter-scenario-*` の画面も見る。

- 点数 = 100 ×（0.30 まとまり + 0.15 隣の関連 + 0.20 手の届きやすさ + 0.20 位置の安定）÷ 0.85
- 分類にはクリエイティブタブを使う。重みと「手の届きやすさ」のタブごとの使用頻度は判断で決めた値なので、点数は人の感覚の代わりにはならない
- ブロックだけのチェストはタブ順に負ける。後から道具や食料が入ると先頭に割り込む分だけ位置の安定が下がるためで、よく使うものを先頭に置く以上は避けられない。テストではこの組み合わせだけ、位置の安定以外で下回らないことを確かめる
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
- `ServerChunkCache.getChunkFuture(..., true)` が付ける札（`TicketType.UNKNOWN`）は 1 tick で外れ、生成が終わる前に捨てられることがある。非同期に読み込むときは `FLAG_CAN_EXPIRE_IF_UNLOADED` のない `TicketType` を `addTicketWithRadius` で付ける（読み込み終えてから時間を数える）
- `CommandEncoder.writeToTexture` は呼ぶたびに重い。小さい範囲を何度も送らず、まとめて送る
- クラスの確認は `~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/<MC>/*.jar` を javap する
