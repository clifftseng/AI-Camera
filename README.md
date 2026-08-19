# AI Camera

讓不太會拍照的人也能拍出好看照片的 Android 相機 App。

拿起相機，App 會即時給你：

- **姿勢建議**：AI 推薦的人物姿勢，以虛線人形疊在取景器上，走進去對齊就好
- **構圖引導**：三分線、水平儀，加上「往左移半步、退後一點」的箭頭提示
- **色彩建議**：依場景自動套用色彩調整，按下快門就是成品

全部 **on-device** 運行——離線可用、零延遲、照片不離開手機。

## 技術路線

| 功能 | 技術 |
|---|---|
| 相機 | CameraX |
| 姿勢偵測 | MediaPipe Pose Landmarker（33 點骨架） |
| 構圖引導 | 規則引擎（三分線／留白）→ GAIC 式取景推薦模型（TFLite） |
| 色彩 | GPU LUT → image-adaptive 3D LUT 模型 |
| 場景路由 | 場景分類（人像／風景）決定用哪套規則與姿勢庫 |

詳細分期見 [docs/ROADMAP.md](docs/ROADMAP.md)。

## 建置

需求：JDK 17+、Android SDK（compileSdk 36）。

```bash
./gradlew assembleDebug
# APK 產出於 app/build/outputs/apk/debug/
```

最低支援 Android 10（API 29）。

## License

MIT
