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

## v0.4 — 色彩自動套用 ✅

- CameraX + Media3 GPU 效果 pipeline（`androidx.camera.media3:media3-effect`）：
  同一組色彩效果**同時**套在預覽與拍出的 JPEG，所見即所得
- 6 種風格：自動／人像／風景／食物／夜景／黑白／原色，左上角按鈕輪播
- 自動模式：畫面暗（平均亮度 < 0.22）→ 夜景；有人 → 人像；否則 → 風景。
  連續 30 幀（約 1–2 秒）才切換，避免風格跳動
- 不支援效果 pipeline 的機器自動退回原色（防禦性 fallback）
- 註：目前用 RGB/HSL/對比參數式調色而非 3D LUT；真正的 adaptive 3D LUT 在 v0.6

## v0.5 — AI 構圖模型 ✅

- NIMA（MobileNet aesthetic，idealo 在 AVA 25 萬張人評照片上的預訓練權重）
  轉 float16 TFLite（6.4MB，轉換腳本在 `tools/convert_nima.py`），內建於 assets
- 「取景推薦」形態：對目前取景與 5 個候選（左/右/上/下偏移、拉近）各打美感分數，
  候選贏過現況 ≥0.12 分才建議方向——純風景（沒有人）也有效
- 每 2 秒背景跑一輪（6 次推論），畫面下方顯示「AI 美感 X.X・往左移一點更好」
- 已知怪癖：NIMA 對高頻紋理偏好偏高；只用於同場景候選框相對比較，不受影響
- GAIC 專用裁切模型（更準的位置建議）留待未來，需要自訓轉換（RoIAlign 客製 op）
- v0.5.1：色彩統計自適應——每 15 幀抽樣亮度/飽和/對比/色溫，動態調各風格強度
  （鮮豔場景少加飽和、已偏暖不再加暖、夜景提亮看實際暗度），統計明顯變化才重套

## v0.6 — AI 色彩模型 ✅

- Image-Adaptive 3D LUT（Zeng et al. TPAMI 2020，sRGB paired 預訓練，FiveK 資料集）
- 拆成兩半上機：權重預測 CNN → float16 TFLite（0.56MB，Keras 手工移植，
  與 PyTorch 參考輸出差 <1e-3）；3 個基底 LUT → `luts_basis.bin`（1.3MB）
- 轉換腳本 `tools/convert_lut3d.py`（含與 torch 的自動比對驗證）
- App 端：CNN 每 2 秒預測 3 個混合權重 → CPU 混出 33³ LUT →
  Media3 `SingleColorLut` 套用（預覽＋成品同步）；權重變化 >0.05 才重建
- 色彩模式新增「AI 調色」（自動 → AI 調色 → 人像 → …）
- fine-tune 資料集候選（未做）：PPR10K（人像）、AVA（美學）

## v0.6.1 / v0.7.0 — 實測回饋修正 ✅

- v0.6.1：橫向支援（configChanges 不重啟、targetRotation 跟轉、水平儀補償螢幕方向）
- v0.7.0：兩種引導模式（右上第二顆按鈕切換）——
  **移鏡頭**：虛線人形貼著人，構圖箭頭引導攝影者；
  **移人**：取景當作不動，虛線人形畫在最近的三分線位置（帶遲滯防跳動），
  請被拍的人走進去，對齊 ±4% 內虛線變綠。切到移人模式若沒開姿勢會自動開第一式
- v0.7.0：色彩管線健壯化——綁定失敗改成**分級降級並明說**：
  預覽＋拍照都有色 → 只有預覽有色（提示成品為原色）→ 完全停用（隱藏按鈕＋提示），
  不再默默退回無色彩讓人以為調色沒效果；管線中途出錯也會提示並停用

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
