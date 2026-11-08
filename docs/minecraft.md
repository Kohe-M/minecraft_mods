# Minecraft 26.3 の注意点

- 非難読化・Mojang 名
- 描画は `extract*` / `GuiGraphicsExtractor`、画面は `Minecraft.gui.screen()` / `gui.setScreen()`
- HUD を隠す（F1）のは `Minecraft.gui.hud.toggle()` / `isHidden()`。`Options.hideGui` はない
- `InputConstants.MOUSE_BUTTON_LEFT` は 1（GLFW の 0 ではない）。クライアントゲームテストの `pressMouse` もこれを使う
- チェストなどのブロックエンティティはチャンクのメッシュに入らず、`BlockEntityRenderDispatcher.tryExtractRenderState` で描画の状態を作る（Sodium も同じ）。明るさ（`lightCoords`）は本物のワールドから読み、`BlockEntityRenderer.shouldRender` は既定で 64 ブロックより遠いものを描かない
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
