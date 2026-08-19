# 開發路線圖

每一版都是能實際使用的 APK。

## v0.1 — 相機地基 ✅

- CameraX 取景、拍照存到相簿（Pictures/AICamera）
- 三分構圖虛線
- 水平儀（±1.5° 內變綠）
- 前後鏡頭切換

## v0.2 — 姿勢虛線 ✅

- MediaPipe Pose Landmarker 即時骨架偵測（on-device，lite 模型內建於 assets）
- 內建姿勢庫 4 式（自然站姿／單手叉腰／雙手舉V／側身斜倚），按鈕輪播
- 推薦姿勢畫成虛線人形疊在取景器上，會自動貼齊偵測到的人物位置與大小
- 圖庫縮圖按鈕：顯示最新照片，點擊直接開啟（使用者建議新增）
- minSdk 26 → 29（MediaStore 縮圖 API 需要）

## v0.3 — 構圖引導 ✅

- 規則引擎（`CompositionAdvisor`）：一次只提示一件最重要的事，優先序
  切到頭 > 切在小腿 > 主體對三分線 > 頭上空白太多 > 眼睛對上三分線
- 引導箭頭（琥珀色）＋文字泡泡；全過就顯示綠色「構圖 ✓」
- 防抖：新提示連續成立 8 幀（約 0.3–0.5 秒）才顯示，避免閃爍
- 肢體入鏡判斷用 MediaPipe 的 per-landmark visibility
- v0.3.1：支援多人（numPoses=4）＋路人過濾——只把「身高 ≥ 最高者 55%
  且 ≥ 畫面高 12%」的人當主體；構圖規則看群體（切頭／切腿任一人踩到就提醒、
  三分線看群體中心、眼睛線只在單人時啟用）；姿勢虛線錨定最大主體。
  若誤判再加「點擊鎖定主體」。
- v0.3.2：路人過濾升級成 `SubjectTracker`——跨幀追蹤（配 ID、記停留幀數與移動量），
  主體用綜合分數判定：大小 40%＋停留穩定度 30%＋面向鏡頭 20%＋靠近中心 10%，
  拉遠景時「站定面向鏡頭的小人」贏過「走動中的大路人」；絕對門檻放低到畫面高 8%。
  加「點擊鎖定主體」（青色角框標示，再點解除），切鏡頭時追蹤重置。

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
