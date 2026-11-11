# fullbright

クライアントのみで動く。サーバー側の mod は不要。

- Z キー（`key.fullbright.toggle`）の単押しでオン/オフ。押している間のホイールで明るさを 200%〜1000%（明るさの設定の最大を 100% として 2〜10 倍）に変える
  - ホイールで変えたらオンにする。変えた後にキーを離しても切り替えない
  - 押している間のホイールはホットバーに回さない
  - 画面を開いている間はキーもホイールも効かない
  - 切り替えと倍率はアクションバーに出す
- 設定: `config/fullbright.txt` に `enabled=` と `level=`（2〜10）。ファイルがないときは有効・10。範囲外は 2〜10 に収め、読めない値は無視する
- jori で無効の間は明るさもキーもホイールもバニラのまま。戻すと元の明るさに戻る

## 仕組み

| 描画 | 差し替えるところ |
|---|---|
| バニラ・Sodium | `LightmapRenderStateExtractor.extract` が読む明るさの設定（gamma）を倍率に差し替える。options.txt は 0〜1 しか読めないので設定値は書き換えない |
| Iris のシェーダーパック | ライトマップを使わないので、`CommonUniforms` の `nightVision`（倍率 / 10 以上）と `screenBrightness`（1）を差し替える |

- ライトマップは元の色に比例して明るくするので、環境光の色（`ambient_light_color`）が黒の次元では暗所が黒いままになる。有効の間は環境光の色をオーバーワールドと同じ 0x0A0A0A 以上にする
- Iris の mixin は Iris の内部（ラムダの名前など）を見ているので `@Pseudo` と `require = 0` にして、Iris の更新で見つからなくなっても起動できるようにする。見つからなくなったことはゲームテストで気づく
- ホイールは `MouseHandler.onScroll` の `ScrollWheelHandler.onMouseScroll` を包み、使った分は (0, 0) を返す

## ゲームテスト

- Sodium + Iris とバニラ描画の両方で走らせる（`gradle.properties` の `vanilla_render_test=true`）。シェーダーパックのテストは Iris があるときだけ走り、ライトマップの差し替えはバニラ描画でも確かめる
- 明るさは、外から光の入らない部屋を足元に作り、スクリーンショットの画面の左上の平均の明るさで比べる
- 環境光の色が黒の次元は、gametest のリソースの `no_ambient`（`dimension_type`・`dimension`）で作る
- シェーダーパックに届くことは、uniform の値をそのまま色にするシェーダーパックをテストの中で作って確かめる。Iris がないときに読み込まれないように、Iris を触るクラスは分ける
