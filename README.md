# minecraft_mods

| mod | | クライアント | サーバー |
|---|---|:---:|:---:|
| [fullbright](mods/fullbright) | 明るさ上限突破（Sodium / Iris 対応） | 必須 | 不要 |
| [minimap](mods/minimap) | ミニマップとピン（M のマップ画面で左クリックで刺す、Shift + 左クリックで抜く、ピンの位置に光の柱、他のプレイヤーも表示） | 必須 | 不要 |
| [sorter](mods/sorter) | インベントリ・チェストのソートボタン | 必須 | 必須 |
| [timber](mods/timber) | 一括伐採 | 任意（G キーでオン/オフ） | 必須 |
| [xray](mods/xray) | 鉱石透視（Sodium / Iris 対応） | 必須 | 不要 |
| [zoom](mods/zoom) | 長押しズーム | 必須 | 不要 |

シングルプレイはクライアントに入れるだけでよい。

## 構成

```
minecraft_mods/
├─ gradle.properties    MC バージョンセット（MC・Loader・Loom・Fabric API・Java・Sodium・Iris）
├─ build.gradle         全 mod 共通のビルド設定
├─ settings.gradle      mod の登録
└─ mods/
   ├─ fullbright/
   │  ├─ gradle.properties mod_version
   │  ├─ build.gradle      その mod だけの設定
   │  └─ src/
   ├─ minimap/
   ├─ sorter/
   ├─ timber/
   ├─ xray/
   └─ zoom/
```

成果物は `<mod>-<mod_version>+<minecraft_version>.jar`（例: `sorter-1.0.0+26.3.jar`）。

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

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-25.0.4.101-hotspot"
./gradlew build
./gradlew :xray:runClient
./gradlew :sorter:runClientGameTest
```
