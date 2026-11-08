# peek

クライアントのみで動く。サーバー側の mod は不要で、バニラのサーバーでも使える（中身はアイテムの `DataComponents.CONTAINER` としてクライアントに届く）。キー割り当てや設定はない。

- 対象: `ShulkerBoxBlock` のブロックアイテム（無色と 16 色）。チェストなど他の入れ物はバニラのまま
- 出す条件: 中身が 1 つ以上あり、`TooltipDisplay` で `CONTAINER` を隠していないとき。空の箱には何も足さない
- 表示: ツールチップの名前のすぐ下に、インベントリと同じスロット（`container/slot`）を 9 列 × 3 段で並べ、開いたときと同じ位置にアイコン・個数・耐久値バーを描く
  - 1 個だけでも 3 段すべてを出す（どこに入っているかが分かるように）
  - バニラが中身を文字で並べる行（「名前 x個」「ほか N 個」）は出さない

## 仕組み

`ItemStack.getTooltipImage` を mixin で差し替えて `ShulkerPreview`（`TooltipComponent`）を返し、Fabric の `ClientTooltipComponentCallback` で描画用の `ClientShulkerPreview` に変える。文字の行は `ItemStack.addToTooltip` で `CONTAINER` の分だけ止める。

## ゲームテスト

実際にカーソルをスロットに置き、描かれたツールチップを確かめる。

- 描かれた中身は、テスト側の mixin で `GuiGraphicsExtractor.tooltip` に渡された部品を記録して見る
- インベントリ・チェスト・クリエイティブ・専用サーバーで動かす。チェストは `closeContainer` で閉じないとサーバーが開いたままの扱いになる
