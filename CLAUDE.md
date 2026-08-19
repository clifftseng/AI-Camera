# AI Camera

讓不會拍照的人拍出好照片：姿勢虛線 + 構圖引導 + 色彩自動套用，全 on-device。
分期計畫與設計原則見 `docs/ROADMAP.md`，動工前先讀。

## 建置（Windows）

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"   # JDK 21
.\gradlew.bat assembleDebug
# 產出：app\build\outputs\apk\debug\app-debug.apk
```

- APK 發佈命名：`AICamera-v<versionName>-debug.apk`
- 版本號在 `app/build.gradle.kts`（versionCode / versionName），每次發佈都要同步 bump 兩者

## 硬規則

- 提交身分必須是 `clifftseng <15731242+clifftseng@users.noreply.github.com>`（local config，已設定）
- 這個 repo 是公開的：不要提交任何公司內部資訊、憑證、token
- 全 on-device：不新增任何雲端 API 依賴
