# minecraft_mods

| mod | | クライアント | サーバー |
|---|---|:---:|:---:|
| [fullbright](mods/fullbright) | 明るさ上限突破（Sodium / Iris 対応） | 必須 | 不要 |
| [jori](mods/jori) | ここにある mod をすべてまとめたもの。タイトル画面とポーズ画面のボタンから mod ごとに有効・無効を切り替え | 必須 | 任意（minimap・sorter・timber のサーバー側が入る） |
| [minimap](mods/minimap) | ミニマップとピン（M のマップ画面で B キーで刺す、Shift + 左クリックで抜く、ピンの位置に光の柱、他のプレイヤーも表示） | 必須 | 任意（マップ画面に描画距離の外も出る） |
| [peek](mods/peek) | シュルカーボックスにカーソルを合わせると、中身をアイテムのアイコンで表示 | 必須 | 不要 |
| [placer](mods/placer) | 長押しの連続設置で積み上がりやズレを防ぐ（J キーでオン/オフ） | 必須 | 不要 |
| [refill](mods/refill) | 手に持ったアイテムを使い切ると、インベントリの同じアイテムをすぐに補充 | 必須 | 不要 |
| [sorter](mods/sorter) | インベントリ・チェストのソートボタン | 必須 | 必須 |
| [timber](mods/timber) | 一括伐採 | 任意（G キーでオン/オフ） | 必須 |
| [xray](mods/xray) | 鉱石透視（Sodium / Iris 対応） | 必須 | 不要 |
| [zoom](mods/zoom) | 長押しズーム | 必須 | 不要 |

シングルプレイはクライアントに入れるだけでよい。jori を入れれば、ほかの mod は入れなくてよい（個別の mod と一緒に入れたときは、バージョンの新しい方が使われる）。

## 構成

`mods/<mod>` に mod ごとの Gradle プロジェクトを置く。構成と仕組みは [docs/architecture.md](docs/architecture.md)、mod ごとの仕様は [docs/mods/](docs/mods/)。開発の決まりごとは [AGENTS.md](AGENTS.md)。

成果物は `<mod>-<mod_version>+<minecraft_version>.jar`（例: `sorter-1.0.0+26.3.jar`）。

## Jori の設定

有効・無効は `config/jori.txt` に 1 行ずつ「mod ID=true」か「mod ID=false」で残る。書いていない mod は有効。

専用サーバーでは、起動するときにこのファイルを読む（minimap・sorter・timber の 3 つだけ）。無効にした mod はサーバー側の受け口を作らないので、クライアントにはその mod がないサーバーと同じに見える（ソートボタンが出ない、など）。

## ブランチとタグ

| | 内容 |
|---|---|
| `main` | 最新の MC バージョン |
| `mc/<MC>` | 旧 MC バージョンの保守（例: `mc/26.3`） |
| `<mod>/<mod_version>+<MC>` | リリースタグ（例: `sorter/1.0.0+26.3`） |

## MC バージョンを上げる

```bash
git switch main
git branch mc/26.3 && git push -u origin mc/26.3
```

1. ルートの `gradle.properties` を新しいバージョンに書き換える
2. `./gradlew build` が通るまで各 mod を直す
3. 各 mod の `mod_version` を上げてコミット・タグ付け

旧バージョンの修正は `mc/<MC>` で行い、必要なら `main` に cherry-pick する。

## ビルド

JDK 25 が入っていれば、`JAVA_HOME` はどの JDK でもよい。

```bash
python tools/dev.py test            # 変えた mod のビルドとゲームテスト。結果は build/dev/report.html
python tools/dev.py status          # リリースしていない変更
python tools/dev.py release sorter minor
./gradlew :xray:runClient
```
