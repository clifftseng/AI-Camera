# 開發路線圖

每一版都是能實際使用的 APK。

## v0.1 — 相機地基 ✅

- CameraX 取景、拍照存到相簿（Pictures/AICamera）
- 三分構圖虛線
- 水平儀（±1.5° 內變綠）
- 前後鏡頭切換

## v0.2 — 姿勢虛線

- MediaPipe Pose Landmarker 即時骨架偵測（on-device）
- 內建姿勢庫：依人數、直橫幅選姿勢
- 推薦姿勢畫成半透明虛線人形疊在取景器上，使用者「走進去對齊」

## v0.3 — 構圖引導

- 用姿勢／人臉偵測取得主體位置
- 規則引擎：主體對三分點、頭部留白、關節不切邊
- 引導箭頭與文字提示（「往左移半步」「退後一點」）

## v0.4 — 色彩自動套用

- GPU 即時 LUT（OpenGL）套在預覽與成品
- 場景分類（人像／食物／風景／夜景）自動選 LUT
- 使用者只按快門，色彩已調好

## v0.5 — AI 構圖模型

- GAIC 式取景推薦模型轉 TFLite：對候選取景框打分
- 最佳框位置 → 轉成移動／縮放引導
- 資料集：GAICD（fine-tune 用，不自行收集）

## v0.6 — AI 色彩模型

- Image-adaptive 3D LUT 模型（Zeng et al.）取代固定 LUT
- 模型即時依畫面內容生成 LUT
- fine-tune 資料集候選：PPR10K（人像）、AVA（美學）

## 設計原則

1. **全 on-device**：不接雲端 API，離線可用、零延遲、隱私乾淨
2. **不從零訓練**：優先用預訓練模型；不夠好才拿公開資料集 fine-tune
   （AVA / GAICD / PPR10K），最後才考慮自建風格資料集
3. **打分模型沒辦法變成引導，取景推薦模型才可以**：
   構圖 AI 一律做成「對候選框打分→最佳框→引導方向」的形態
4. **風景 vs 人像用場景分類路由**，共用同一個構圖模型，不分開訓練

## 參考

- [MediaPipe Pose Landmarker](https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker/android)
- [GAIC: Grid Anchor based Image Cropping](https://github.com/bcmi/Awesome-Aesthetic-Evaluation-and-Cropping)
- [NIMA: Neural Image Assessment](https://github.com/idealo/image-quality-assessment)
- [Learning Image-Adaptive 3D Lookup Tables](https://github.com/HuiZeng/Image-Adaptive-3DLUT)
- [android-gpuimage-plus](https://github.com/wysaid/android-gpuimage-plus)
