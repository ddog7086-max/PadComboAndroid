# PadComboAndroid

原生 Android（Kotlin）轉珠路徑輔助 App，由 `padcombo-web-mvp` 核心演算法完整移植而來。

## 架構

- `core/`（純 Kotlin 邏輯）
  - `Orb.kt`：六色列舉 火/水/木/光/暗/心
  - `BoardModels.kt`：6×5 盤面、格座標、連鎖結算模型
  - `BoardEngine.kt`：拖曳軌跡平移（手持珠推進）、橫縱 ≥3 消除、重力下落補珠、連鎖 Combo
  - `SearchModels.kt`：搜尋設定 / 結果模型、取消旗標
  - `BeamSearchSolver.kt`：啟發式剪枝 Beam Search、六色權重、Calc/Moves/高Combo/多色條件
  - `BoardRectHelper.kt`：統一盤面矩形（滿寬正方形格網、貼底），辨識與繪圖共用同一組座標
  - `BoardRecognizer.kt`：依共用矩形裁切 6×5、每格中央 36% 3×3 中位數 RGB→HSV 分類、信心度與警告
- `overlay/RouteBoardView.kt`：細白二次貝塞爾平滑曲線 + 起點白點 + 終點箭頭
- `capture/PadService.kt`：MediaProjection 抓幀、辨識、求解、懸浮疊加
- `MainActivity.kt`：權限申請、啟動/停止助手

## 技術規格

- Min SDK 26 / Target SDK 34 / Compile SDK 34
- Kotlin 1.9.24、AGP 8.5.2、Gradle 8.9、JDK 17
- 不依賴第三方商業 SDK

## 盤面座標公式（辨識與疊加共用）

以真實螢幕尺寸（`WindowMetrics` / `getRealMetrics`，含狀態列與挖孔）計算：

```
cellSize  = screenWidth / 6f
boardHeight = cellSize * 5f
boardTop  = screenHeight - boardHeight - bottomInset - bottomOffset
boardLeft = (screenWidth - cellSize * 6f) / 2f   // 滿寬時 = 0

格心：
cx = boardLeft + (col + 0.5f) * cellSize
cy = boardTop  + (row  + 0.5f) * cellSize
```

預設 `scale = 1f`、`bottomOffset = 0f`、`bottomInset = 0f`；盤面貼底、單格正方形。
辨識取樣與路徑繪線讀取同一個 `BoardRect`，不會再出現取樣與畫線錯位。

## 本機建置

需 Android SDK 與 JDK 17：

```
gradle :app:assembleDebug
```

產出：`app/build/outputs/apk/debug/app-debug.apk`

雲端 CI（`.github/workflows/build-apk.yml`）push `main` 後自動打包，artifact 名稱 `PadCombo-debug-apk`。

## 單元測試

```
gradle :app:testDebugUnitTest
```

涵蓋：軌跡平移正確性、橫向消除連鎖、Beam Search 輸出、取消旗標。
