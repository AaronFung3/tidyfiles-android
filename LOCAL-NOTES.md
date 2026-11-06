# 畫格（本地 fork 工作目錄）

呢個目錄係 **漫畫閱讀 app「畫格」** 嘅 Android 原始碼，唔係普通資料夾。

## 身份

| 項目 | 值 |
|---|---|
| App 名 | **畫格**（原本 fork 叫「相簿」） |
| 目錄名 | **`komikku_upscale`**（刻意唔改名：三份 handoff 硬寫咗呢條路徑，改名會撞爛其他 session） |
| 上游 | `Viel0320/komikku_img_upscale`（本身係 `komikku-app/komikku` 嘅 fork） |
| 只 clone 咗 | `--depth 1`、branch `master` |
| 語言／技術 | Kotlin + Jetpack Compose，Gradle，NDK（ncnn / QNN / LiteRT） |

## 本機已改嘅嘢（未 build）

1. **App 名**：`i18n/src/commonMain/moko-resources/base/strings.xml:3`
   `app_name` 由「相簿」→「畫格」（`translatable="false"`，所以全部語言都係呢個名）。
2. **超分完成後唔喺左下角顯示後端名**：
   `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt:317`
   `enhancementBackendName()` 直接 `return ""`（原本會回 `QNN` / `Vulkan` / `LiteRT`）。
   只影響狀態字，唔影響超分同 cache key；處理中／排隊／錯誤字照舊。
3. **Launcher icon**：`app/src/main/res/drawable/ic_launcher_foreground.xml` 同
   `ic_launcher_monochrome.xml` —— 一格漫畫分格對角切開，左上青綠 `#6F9C95`／右下米白 `#E8E2D6`。
   背景檔 `ic_launcher_background.xml` 冇改。
4. **頁碼旁「已 HD」小點**：`ImageEnhancementCache.kt` 加 `HdMarks`／`hdMarks`／`markHd()`；
   `ReaderPageImageView.kt` 3 處 `markHd`；`ReaderActivity.kt:337,355` 收集並傳 `isHd`；
   `ReaderPageIndicator.kt` 加 `isHd` 參數同 6dp 青綠圓點。純記憶體狀態，換章節自動重置。

## 重要

- **冇 build、冇簽名、冇 push**。要出 APK 一定要用 GitHub Actions（本地冇 Android SDK：
  `dl.google.com` 被政策封 403、C: 剩 5.8 GB）。
- 用 git 之前要加 safe.directory（exFAT 唔記 owner）：
  ```
  git -c safe.directory='E:/_cleanup/komikku_upscale' -C 'E:\_cleanup\komikku_upscale' status
  ```
- App 內建 updater 指住上游 `VIEL_REPO`，所以**唔好撳「檢查更新」**，否則會裝返官方版（又變「相簿」）。
  要徹底就改 `app/src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateChecker.kt` 或關掉 updater。

## 相關文件

- 規格：`C:\Users\fung3\Desktop\tidyFiles\docs\android\HANDOFF-L2-ANDROID.md`
- PC 端 server：`C:\Users\fung3\Desktop\tidyFiles\cleanup-app\`
