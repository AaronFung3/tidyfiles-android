# 图像增强(upscale)移植审查 — feature/image-upscale

参照系:`HaoweiLi97/mihon_img_upscale`(v1.3.9,master)相对其 mihon 基线(v1.1.5 时代)的 upscale 相关改动。
被审对象:`komikku feature/image-upscale` 分支相对 `master` 的全部变更。
结论先行:**核心链路(NCNN/Vulkan 推理、模型管理、Coil 解码管线、阅读器集成、设置 UI、QNN/NPU)已完整移植并在 SM8750 真机可用;发现并修复 2 个移植缺陷;另有 7 处有意差异(全部有理由),无未解释的遗漏。**

> 后续同步:上游 1.3.11 / 1.3.12 的移植审查见 [§6](#6-1311--1312-同步审查--syncupscale-1-3-11);上游 1.3.15 的移植与审查收尾见 [§7](#7-1315-同步与审查收尾--featupscale-1-3-15--fixupscale-review-followup)。

---

## 1. 逐文件对账

### 1.1 新增文件(上游新增 → 本分支新增)

| 上游文件 | 状态 | 说明 |
|---|---|---|
| `app/src/main/cpp/waifu2x.{h,cpp}`(Vulkan 分块推理、fp16/fp32 融合 compute shader) | ✅ 原样 | 自 v1.3.9 master 拷贝 |
| `app/src/main/cpp/waifu2x_jni.cpp`、`shaders.h`、`waifu2x_fused_*.comp` | ✅ 原样 | JNI 导出面与 Kotlin `external fun` 一一对应 |
| `app/src/main/cpp/anime4k.{h,cpp}`(GLSL Anime4K) | ✅ 原样 | 休眠能力:JNI/Kotlin 已接,UI 无入口(上游亦然) |
| `app/src/main/cpp/qnn_backend.{h,cpp}` | ✅ 原样 + 配置差异 | 见 §3.3,以 `MIHON_ENABLE_QNN=1` 编译 |
| `app/src/main/cpp/CMakeLists.txt` | ✏️ 适配 | 剔除 spatial/depth 源文件;QNN 只查头文件不查 SDK lib(运行库来自 AAR) |
| `third_party/ncnn-20260113-android-vulkan/`(99MB) | ✅ 原样 | 与上游同版本同布局;`local.properties`/`-PncnnSdkDir`/环境变量均可覆盖 |
| `third_party/qnn-include/` + LICENSE + README | 🆕 替代方案 | 上游用本地 QAIRT SDK 头文件;本分支 vendor 自 `qualcomm/geniex-qairt-plugin`(BSD-3-Clause,QNN C API 2.27,运行时 2.49 向后兼容)。**头文件无法从 release APK 获得** |
| `util/waifu2x/Waifu2x.kt` | ✏️ 适配 | 模型生命周期、assets 解压、QNN 初始化;**运行时 GitHub/HF 按需下载回退已移除**,模型全部捆绑、缺失即快速失败(`BUNDLED_MODEL_CACHE_VERSION` 随捆绑资产变更递增) |
| `util/waifu2x/ImageEnhancer.kt` | ✅ 原样 | 优先级队列(可见页 3/副页 2/提升 1/预载 0 + 距离 + FIFO)、抢占、代际取消 |
| `util/waifu2x/ImageEnhancementCache.kt` | ✏️ 适配 | 磁盘缓存(3GB 上限)、configHash、skip 标记、透明图拒收;**新增 `saveToCacheSync`(保留所有权),移除无调用者的异步保存路径**(见 §3.6) |
| `util/qnn/QualcommHtp.kt` | ✅ 原样 | HTP 架构探测(JNI),spatial 注释残留无碍 |
| `util/image/ImageFilter.kt`(ink 滤镜) | ❌ 跳过 | 见 §2 |
| `assets/` 模型(realcugan SE/Pro、realesrgan、waifu2x、nose、upconv7、span、sudo、anime4k) | ✏️ 适配 | 模型 ~87MB(qnn-contexts 另计 437MB,共 526MB);新增 w2xex-esrgan 三模型与 animejanai x2,删除未引用的 `realesrgan-models/anime/x4`;来源/许可证/校验和见 `docs/third-party-upscale-assets.md` 与 `docs/upscale-assets-sha256sums.txt` |
| `assets/qnn-contexts/`(150 个,437MB,v69–v81) | ✅ 原样 | 从上游 release APK 提取,字节级一致;上游是构建期从 `qnn.context.dir` stage,本分支直接入库 |
| `app/src/main/jniLibs/arm64-v8a/libQnnModelDlc.so` | ✅ 原样 | 从上游 release APK 提取;已验证 `libQnnHtp.so` 不依赖它(仅 DLC 模型路径用),对齐上游打包 |
| i18n:37 个 `reader_*` 字符串 | ✏️ 适配 | **KMR / i18n-kmk base**(上游是 MR / i18n),符合本仓库 i18n 规范;Weblate 可译 |

### 1.2 修改的既有文件

| 文件 | 上游改动 | 状态 | 说明 |
|---|---|---|---|
| `app/build.gradle.kts` | cmake 接入、NCNN/QNN 路径解析、QNN staging 任务、AAR 依赖、ndkVersion | ✏️ 适配 | 无 QNN staging 任务(上下文直接入库);`ndkVersion` 不钉死(用 AGP 默认,本机 27 自动装);AAR 依赖同上游 |
| `data/coil/Utils.kt` | +5 个 Coil 参数(enhanced/mangaId/chapterId/pageIndex/pageVariant) | ✅ 原样 | KMK 块 |
| `data/coil/TachiyomiImageDecoder.kt` | 重写:缓存优先 → 预缩放 → 推理 → 纹理上限 → 回写缓存;BitmapFactory 兜底;`DecodeResult?` 软失败 | ✏️ 适配 + 保留 SY | 保留 komikku 的 SY 封面归档(CbzCrypto)两条路径;ink 滤镜调用点未移植。**P1 修复**:移除全局解码串行、推理移至专用调度器、直接返回增强结果(见 §3.5/§3.6) |
| `ui/reader/model/ReaderPage.kt` | +`enhancementStream`/`enhancementKeySuffix` | ✏️ 适配 | 放在**类体**而非构造参数(构造参数会破坏既有尾随 lambda 调用点,上游 mihon 无此调用模式) |
| `ui/reader/setting/ReaderPreferences.kt` | +15 个 `realCugan*` 偏好 | ✅ 原样 | 键名与上游完全一致(升级用户偏好可迁移);未移植其遗留 `waifu2xEnabled`/`inkFilter*`/`realCuganProEnabled`(无 UI 引用) |
| `presentation/reader/settings/ColorFilterPage.kt` | +增强设置段 | ✏️ 适配 | `SettingsChipRow` 传 `StringResource`(komikku 组件签名与上游不同);字符串 KMR |
| `ui/reader/ReaderActivity.kt` | setUiBusy 全套(菜单/触摸/按键/销毁)、底栏增强开关按钮、spatial 场景 | ✏️ 部分移植 | setUiBusy ✅(`UI_BUSY_COOLDOWN_MS=1s`);底栏按钮 ❌(§2);spatial ❌ |
| `ui/reader/ReaderViewModel.kt` | `toggleImageEnhancement()`、`ImageEnhancer.reset()` | ✅ 原样 | reset 挂在 `init()`(komikku 的初始化入口与上游不同) |
| `loader/HttpPageLoader.kt` | preloadSize 联动 + internalLoadPage 增强流替换 | ✏️ 适配 | **本分支修正**:增强关闭时回落 komikku 的 `preloadSize()`(SY 偏好)而非上游的常量 4,避免关闭功能时行为回归 |
| `loader/DownloadPageLoader.kt` | loadPage 时触发增强 | ✅ 原样 | |
| `viewer/ReaderPageImageView.kt` | 485→1396 行:状态角标、增强图轮询、交叉淡入交换、缓存自愈、onPageSelected 剪枝 | ✏️ 部分移植 + 2 处修正 | 保留 komikku 的 KMK 缩放特性(disableZoomIn/doubleTapZoom/landscapeZoomScaleType)与 webtoon 长条 Coil 解码路径(并在该路径也接入增强);`enhancementDisplayEnabled` 为本分支新增开关;**修复 SSIV recycle 竞态**(§3.1)、**偏好 collector 绑定 attach/detach 生命周期**(§3.4) |
| `viewer/pager/PagerViewer.kt` | offscreenPageLimit 联动、destroy 时 cancelAll、hasSplitPage | ✏️ 适配 | `else 1` 保留 komikku 默认(上游 mihon 为 2);新增 `hasSplitPage` |
| `viewer/pager/PagerConfig.kt` | 16 个偏好变更监听(cancelAll + 清缓存 + 刷 adapter) | ✅ 原样 | |
| `viewer/pager/PagerPageHolder.kt` | 增强上下文绑定、缓存优先显示、拆页变体、合并双页 | ✏️ 适配(差异最大) | 见 §2 拆页设计与 §2.2 合并双页 |
| `viewer/webtoon/WebtoonViewer.kt` | destroy cancelAll、onPageSelected reprioritize、currentGlobalPageIndex | ✅ 原样 | |
| `viewer/webtoon/WebtoonConfig.kt` | 同 PagerConfig 监听(以 appContext 清缓存) | ✅ 原样 | |
| `viewer/webtoon/WebtoonPageHolder.kt` | (上游无此文件改动;本分支主动接入) | 🆕 本分支扩展 | webtoon 侧缓存优先显示;上游 webtoon 无拆页概念故无需变体 |
| `presentation/reader/appbars/*` | 底栏增强开关按钮 | ❌ 跳过 | §2 |
| `App.kt` / `SettingsAdvancedScreen` / `GLUtil` | 上游含 GLUtil(DEVICE_TEXTURE_LIMIT 等) | ⭕ N/A | komikku 自带(KMK 特性),直接使用 |

## 2. 有意差异(7 项)

1. **拆页增强重新设计**:上游 auto-split 模式对每个"半页"独立增强(`wide_left/right` 变体 + 拆分增强流),与其自研 PageSpreadDetector 深度耦合。komikku 用 SY 的 `dualPageSplit`(显示期拆分 + InsertPage 独立 holder),照搬会有重复插入 InsertPage 的风险。本分支方案:**整页增强 + 显示期重切**(`processEnhanced` 不再触发 `onPageSplit`,以 `hasSplitPage` 守卫缓存过早命中),两半共享同一份整页缓存。代价:拆页父页首载必须先看一次原始整页(与上游守卫语义一致)。
2. **SY 合并双页(extraPage)在线合并**:两源直接重跑 `mergePages` 会带副作用(fullPage 标记/插入拆页)。本分支改为:主副页各自独立增强(cache key 用各自的 `enhancementKeySuffix`),`ReaderPageImageView` 通过 `secondaryPage` 感知副页并在两半缓存都就绪后调用无副作用的 `mergeEnhancedPages` 在线换入;任一半未就绪或不可增强时保持原始显示,不阻塞拆页布局。
3. **底栏快捷开关按钮未移植**:komikku 底栏是 SY 的用户可配置按钮系统(`ReaderBottomButton` 枚举 + 设置页),上游是固定图标行,直接搬会破坏按钮自定义体系。`toggleImageEnhancement()` 已就绪,后续若要加按钮走 `ReaderBottomButton` 枚举扩展。
4. **ink 滤镜未移植**:上游隐藏偏好控制(无设置 UI、默认全关),与 upscale 无关。
5. **spatial depth(Depth Anything)未移植**:独立特性,非 upscale;相关 cpp/Kotlin/assets/字符串均剔除。
6. **QNN 构建方式**:上游 = 本地 QAIRT SDK(头文件 + DLC + 上下文生成本地化);本分支 = vendored 头文件 + Maven AAR 运行库 + 上下文直接入库。**构建不再依赖高通 SDK**,换来的成本是仓库 +~530MB 二进制(见 §4 待决)。
7. **遗留偏好不搬**:`pref_waifu2x_enabled`(上游旧版残留,无 UI)、`realCuganProEnabled`(Pro 由模型枚举决定)。新装用户与升级用户偏好键均与上游对齐。

## 3. 审查发现的问题(均已修复)

### 3.1 [已修] SSIV recycle 竞态崩溃(真机复现)
上游的 `clearProcessedSwapView`/`completeProcessedSwapTransition` 对**仍挂在视图树上的** SSIV 先 `recycle()` 后 `removeView()`;tachiyomiorg SSIV 的 `onDraw→sendStateChanged` 会解引用已被置空的 `vTranslate` → NPE 闪退(真机 PID 28748 复现,栈:`SubsamplingScaleImageView.sendStateChanged ← onDraw`)。修复:先 `removeView` 再 `recycle`,并在 recycle 前摘除 `OnStateChangedListener`。**上游代码存在同样的潜伏竞态。**

### 3.2 [已修] webtoon 预载页数回归(移植笔误)
上游 `HttpPageLoader.preloadSize` 回落值是常量 4(纯 mihon 语义);直译到 komikku 会覆盖 SY 的 `preloadSize()` 偏好。已改为回落 `readerPreferences.preloadSize().get()`。

### 3.3 [已修] 暂存区混入无关文件
`qnncheck/`(检查 AAR 的临时解包)曾误入 index,已移除;`gradle.properties`(+tooling.parallel)是用户在会话前的既有改动,**不属于本分支,提交时必须排除**。

### 3.4 [已修] 偏好 collector 泄漏(与视图生命周期解耦)
三个偏好 collector 原在 `ReaderPageImageView.init` 注册且挂在永不取消的 scope 上;pager/webtoon 的 RecyclerView 每创建一个页面视图都会新增一批常驻 collector(并持续读写已 detach 视图的 `statusView`),随滚动累积。修复:collector 改在 `onAttachedToWindow` 启动、`onDetachedFromWindow` 取消;`viewScope` 仅在旧实例已取消时才重建,声明处实例不再成为孤儿。

### 3.5 [已修] Coil 解码被全局串行 + 推理占用解码线程池
- 伴生对象上的 `Semaphore(1)` 把经此解码器的所有解码(正文页、封面等)串成单队列,上游无此串行 — 已移除。
- 数秒级推理原先跑在共享 Coil 解码线程池(3 线程)上,会饿死其它解码 — 改为 `Dispatchers.IO.limitedParallelism(1)` 专用调度器;增强的串行性仍由 `ImageEnhancer` 单 worker 与 native 锁保证。

### 3.6 [已修] 解码结果契约错误(显示依赖轮询竞争)
`decode()` 原返回原始位图、增强结果经异步队列落盘,显示正确性依赖 500ms 轮询与保存队列的竞争,且保存被拒时推理结果被静默丢弃。修复:新增 `ImageEnhancementCache.saveToCacheSync`(同步写入、调用方保留位图所有权),`decode()` 直接返回增强结果;并删除已无调用者的异步 `enqueueSaveToCache` 及其队列/worker。

### 3.7 [已修] 运行时模型下载回退与死代码
- 移除 `Waifu2x` 的 GitHub/HF 运行时下载回退:模型已全部随包提供,缺失属打包错误,改为快速失败而非在解码线程发起同步网络请求。
- `extractModelsToCache` 加 `@Synchronized`,避免多入口并发解压竞争。
- 删除无调用者的 `Waifu2x.process()`、`processingId` 与对应 `nativeProcess` 声明(cpp 侧符号仍由 `nativeProcessRealCugan` 内部使用)。

### 3.8 [已评估/不采纳] abort 定向化
曾将 `nativeAbortProcessing`/`nativeClearAbortProcessing` 由进程级标志改为按请求 id 定向,但未运行 id 的目标会残留,使后续推理被误杀(当前单 worker + 全局 native 锁下,原「每次 prepare 无条件清除」语义自愈且正确)。评估后回退,保持上游语义。

## 4. 机械检查结果

- ✅ `i18n/`、`i18n-sy/` 非 base 无任何新增(diff 为空) — 符合 AGENTS.md i18n 硬规则
- ✅ 字符串仅新增于 `i18n-kmk/.../base/strings.xml`(37 个)
- ✅ `spotlessApply` → `spotlessCheck` 通过;`assembleDebug` / `assemblePreview`(R8)通过
- ✅ 无上游标识泄漏(未动 `AppUpdateChecker.GITHUB_REPO`、applicationId 保持 `app.komikku`)
- ✅ 偏好键与上游一致,上游用户迁移路径可用
- ✅ `.gitattributes`:`*.param` 与 `*.bin`/`*.so`/`*.dlc`/`*.a` 一致标记为 binary,防止 ncnn 模型描述被行尾归一化
- ✅ `docs/upscale-assets-sha256sums.txt` 覆盖全部二进制与 ncnn `.param`
- ⚠️ 未跟踪的 `app/src/main/assets/Anime4K_*.glsl`(根目录 7 个)是用户本地既有文件,与本次移植无关,保持未跟踪

## 5. 遗留/待决事项

1. **仓库体积**:third_party(ncnn 99MB + qnn-include 1.9MB)、assets(~527MB)、libQnnModelDlc(3.2MB)拟全部入库(上游同样入库),仓库将增 ~630MB。备选:Git LFS / 下载脚本 / 只保留 v79 上下文 — **待确认后提交**。
2. `reader_image_enhancement_toast` 字符串目前无引用(为底栏按钮预留)。
3. NPU 上下文为静态资产;若未来要为新架构重新生成,需上游 `tools/qnn` 的 Docker 管线(未移植)。

## 6. 1.3.11 / 1.3.12 同步审查 — sync/upscale-1-3-11

参照系:`HaoweiLi97/mihon_img_upscale` 053c33caf(Release 1.3.11,父 65e3f1900)与 3e5aee233(Release 1.3.12,父 053c33caf)中增强/阅读器相关的改动子集。
被审对象:`sync/upscale-1-3-11` 分支相对 `master` 的 3 个提交(38382d22f、dc94dce90、8318dea84;18 文件,+1202/−348)。
结论先行:**三个提交均与提交信息声明一致;7 个原生 C++ 文件 + 2 个宿主测试与上游字节级一致;Kotlin 层全部偏差可归因到 §1–§2 的既定 KMK 适配;无范围蔓延、无实现错误。**

### 6.1 逐提交对账

**38382d22f — 1.3.11 原生与解码修复**

| 上游改动 | 状态 | 说明 |
|---|---|---|
| `monochrome_guard.h`(新) | ✅ 原样 | 64×64 快速拒绝采样、NEON + 可移植小端块路径、0.05% 弱色度噪声预算;替换 JNI 层的输入 RGB→灰采样归一化,有色调扫描与小块彩色区域保留源像素,中性页的伪色仍由共享输出通道抑制 |
| `periodic_texture_guard.h` | ✅ 原样 | 正交轴 70% 方向覆盖要求(局部笔画簇不再缩整块)、per-core TilePlan/Analysis 缓存、AxisTransform 圆整缩放比(Vulkan/QNN 共享)、占用率提前退出 |
| 75% shrink-and-restore 守卫限制到中性 Real-CUGAN Pro 页 | ✅ 一致 | `nativeInitRealCugan` 增加 `isPro` 参数(JNI → `g_realcugan_pro` → 守卫条件 `g_realcugan_pro && is_monochrome_input`);denoise3 图名启发式移除 |
| `TachiyomiImageDecoder` 源分辨率解码 | ✅ 一致 | 增强开启时按源分辨率解码,推理前不再被 `inSampleSize` 采样丢失细节 |
| `ImageEnhancementCache` 修订号 | ✅ 一致 | 新 `_dv` 输入解码修订(=1);纹理守卫修订 `_tv`(=3)条件 `model == 1 \|\| (model == 0 && noise == 3)`(全部 Real-CUGAN Pro + SE denoise3);monochrome 修订 `_nv`(=3);bundle/QNN 上下文版本 16/18 → **17/19**(本 fork 序列 +1 语义,上游 14/17 → 15/18) |
| 宿主回归测试 `app/src/test/cpp/` | ✅ 原样 | monochrome / periodic_texture 两文件;未接 Gradle/CI,仅头文件注释中的手动 `c++` 命令(上游同) |

**dc94dce90 — 1.3.12 阅读器稳定性修复**

| 上游改动 | 状态 | 说明 |
|---|---|---|
| `openmp_runtime.h`(新) | ✅ 原样 | JNI_OnLoad 设 `KMP_TOPOLOGY_METHOD=flat`,规避 Android 运行时在 libomp 计核与拓扑发现之间下线 CPU 导致的断言(Real-CUGAN 初始化路径) |
| `ImageEnhancementCache` 验证快照 API | ✅ 逐字一致 | `readCachedImage`(锁内字节快照 + 1/32 采样预解码拒绝损坏/空条目)、`decodeCachedImage`、compare-and-delete `discardCachedImage`、锁内 `removeCachedImage`(拒绝以失败/零字节编码覆盖页面唯一有效缓存) |
| `TachiyomiImageDecoder` 解码回落 | ✅ 一致 | `source.peek()` 预检原生解码器,失败仍可回落 BitmapFactory;缓存增强图一律经 `decodeCachedImage` 验证快照解码 |
| `ReaderPageImageView` 陈旧缓存拒收与恢复 | ✅ 一致 | `imageRevision` / `currentCachedFile` 追踪;处理源文件名编码的增强配置已过期则忽略;增强加载失败经 `originalImageSourceFactory` 回落原始页并每绑定重试一次增强 |
| `PagerPageHolder` 全路径验证 | ✏️ 适配(既定约定) | 初载、两个 factory、合并双页半图全部经 `readCachedImage` 验证并回落原始显示流;`buildOriginalDisplaySource` 复用非变更显示变换(`processEnhanced`/`mergeEnhancedPages`),恢复不再重插拆页。上游的 `shouldUseSplitDisplaySource` 系列辅助在本 fork 拆页设计(§2.1)下不存在 |

**8318dea84 — QNN 资产剥离构建(本 fork 自有,无上游对应)**

`-Pexclude-qnn-assets` → `strip-qnn.gradle.kts` 经 `finalizedBy` 挂到每个 `package<Variant>` 任务:重写非 arm64 split APK 剔除 `assets/qnn-contexts/*`、zipalign 对齐、debug keystore 重签;CI release job 用 `SIGNING_KEY`(release keystore)再签;universal 与 arm64-v8a 保留模型;不带该属性的构建不变。x86 split 体积 ~412MB → ~115MB。

### 6.2 移植取舍

每条记录:决定 / 放弃的替代方案 / 代价。

1. **原生层与上游字节级一致**:7 个 cpp 文件 + 2 个宿主测试逐字拷贝,komikku 侧可做的精简/改写一律不做。代价:komikku 无法在不破坏"字节级一致"属性的前提下裁剪未用路径;收益:后续 1.3.x 同步保持零噪声 diff,对账成本恒定。
2. **monochrome 穷举中性检测替代采样归一化**:选 64×64 快速拒绝 + NEON/可移植块路径 + 0.05% 弱色度噪声预算的穷举检测;放弃 1.3.11 之前的输入 RGB→灰采样归一化(会毁掉有色调扫描与小块彩色区域的源像素)。代价:每块多一次分析遍;接受中性页 ≤0.05% 的色度泄漏(伪色仍由共享输出通道抑制)。
3. **增强开启时按源分辨率解码**:选全分辨率解码进推理;放弃按阅读器显示尺寸预采样(省内存但推理前丢细节)。代价:解码峰值内存更高、推理输入更大;仅在增强开启时生效。
4. **缓存版本 16/18 → 17/19 + `_dv`/`_tv`/`_nv` 升版**:选升版而非兼容读取;放弃保留旧条目。代价:存量用户增强缓存整体失效一次(下次阅读重新推理);收益:旧守卫/旧解码路径产生的条目永不显示。`_tv3` 收窄到 `model == 1 || (model == 0 && noise == 3)` 而非全局后缀升版,是为了把失效范围限制在受影响模型。
5. **增强加载失败 → 回落原始页 + 每绑定重试一次增强**:选有界单次重试(`enhancedRecoveryCount`);放弃两种极端 — 不重试(损坏条目 = 该页永久降级,直到手动清缓存)与无限重试(失败循环)。代价:损坏条目在首次访问多花一次增强推理,随后被 compare-and-delete 丢弃。
6. **所有增强文件访问经 `readCachedImage` 验证**:选每次访问都做 1/32 采样预解码;放弃"只在首载验证"。代价:每次访问少量 CPU/分配;收益:损坏条目在完整解码与 SSIV 显示之前被拒绝。
7. **陈旧配置条目忽略而非立即删除**:文件名编码增强配置,过期条目在访问时忽略;磁盘回收交给既有 3GB→90% trim。放弃"发现即删"。代价:短暂多占磁盘;收益:不在显示路径(该文件可能正被当前页流式使用)上做删除,避免竞态。
8. **QNN 剥离选包后重写而非构建期分流**:AGP 未提供按 split 剔除 assets 的构建期配置,故 `finalizedBy` 挂包后任务重写 split。代价:每变体多一次重写 + zipalign + 重签的构建时间;本地产物为 debug keystore 签名(安装信任链由 CI release job 用 release keystore 重签终结);变体列表硬编码与 `apkDir` 锚定 `projectDirectory` 的漂移风险已在脚本注释内自我声明。

### 6.3 有意不移植(上游 1.3.11/1.3.12 其余部分)

1. **host 侧 source-api/LocalSource 改动**(3e5aee233)— 上游该半区是 TachiyomiX 1.6 合并更新 API 的桥接重做(`Source.getMangaUpdate` 经 `LegacyMangaUpdateContext` 协程上下文分发、`CatalogueSource` 桥委托 super、`HttpSource` 收敛 Rx 兜底、`LocalSource` 直接覆写 suspend API + 两个源测试),与 upscale 正交。取舍:本同步只声明读器/增强范围,不动 `source-api` ABI(AGENTS.md:避免破坏扩展 ABI);代价:1.6 扩展的桥接修复要等专门的同步轮次。
2. **androidTest**:`ImageEnhancementCacheTest.kt`、`NativeModelStartupTest.kt`(3e5aee233)— 设备 instrumentation 测试;本 fork 无模拟器/设备 CI,无法执行。缓存验证逻辑的回归保障由 `app/src/test/cpp` 宿主测试与 Kotlin 层对账覆盖。
3. **扩展兼容性文件**(053c33caf):`SearchSource`、`ExceptionFormatter`、proguard、gradle 升级 — 与 upscale 无关。
4. **`ReaderPreferences` model-20 HFA2Real 退役机制**(053c33caf)— fork 无 `REMOVED_ENHANCEMENT_MODELS`/`FIRST_CUSTOM_MODEL_ID` 机制,从未存在过模型 20。
5. **`app/build.gradle.kts` QNN staging 回退**(053c33caf)— fork 静态入库 `assets/qnn-contexts/`,无 staging 块(§1.1)。
6. **`tools/qnn/export_models.py` checkpoint 解包**(053c33caf)— fork 无 `tools/qnn`(§5.3)。

### 6.4 已审/排除的疑点(备查,避免重复误报)

- **`discardCachedImage` 的 `current != expectedSource` 不是恒等比较**:okio-jvm 3.17.0 的 `Buffer.equals` 为逐段内容比较(反汇编确认:引用相等 → instanceof → size → 逐段内容),且 `Buffer.close()` 是空操作(bytecode 仅 `return`)— compare-and-delete 语义正确,与上游一致。
- **`app/build.gradle.kts` 死导入**:剥离提交曾引入 9 个未使用导入(使用它们的逻辑在 `strip-qnn.gradle.kts` 内自行导入);ktlint 不检查 `.kts` 未用导入(`spotlessCheck` 不报错),已在 8318dea84 手工清理。

### 6.5 机械检查

- ✅ `:app:spotlessCheck` 通过;i18n 模块零触碰,KMK/SY 岛完整
- ✅ `assembleDebug` 通过
- ✅ 18 个文件全部映射到提交信息声明,无范围蔓延

## 7. 1.3.15 同步与审查收尾 — feat/upscale-1-3-15 + fix/upscale-review-followup

参照系:`HaoweiLi97/mihon_img_upscale` 1ab1420(Release 1.3.15)/v1.3.15 标签。
被审对象:本地 `master` 领先 `origin/master` 的 8 个提交(2026-10-21 合并,fast-forward 至 15d9b0a49),以及其后的审查收尾分支 `fix/upscale-review-followup`。
结论先行:**8 个提交与上游 v1.3.15 语义对齐,引擎 cpp 7 个文件字节级一致(见 §7.4);审查发现 5 项问题,均已在收尾分支处置(4 项修复、1 项决定保留);新增 18 个 JVM 单测全部通过。**

### 7.1 逐提交对账(8 个,2026-10-21 并入 master)

| 提交 | 内容 | 对齐状态 |
|---|---|---|
| dcef7ec90 | 增强失败显式失败而非伪装模型输出:解码路径的 enhanced 结果所有权契约(ownsResult/finally 回收)、透明图拒收后保留原图 | ✅ 与上游 1.3.15 语义一致 |
| 5d5d64ba1 | 缓存写盘队列防护:单槽 Channel + `pendingSaveKeys` 簿记、取消/清空不泄漏位图、`clear()` 与写盘以读写锁互斥(写盘持读锁全程) | ✅ 一致 |
| e6d2b413a | 增强队列串行化与重试竞态:worker 在同一 monitor 下 poll、`requeueOnCancel` 簿记、`processRequest` 代际/身份校验、被取消尝试不得抑制替代请求的重排队 | ✅ 一致 |
| f366fe980 | 状态标签跨轮询/重绑定稳定:`EnhancementPollKey`(revision+manga/chapter/page/variant/config)去重重启、`statusUpdateVersion` 拒绝陈旧回帖 | ✅ 一致 |
| f2e7c7305 | **QNN 设备编译路线**:`assets/qnn-models` 15 个 FP16 DLC(33.6MB,取自 v1.3.15 release APK)取代 437MB 预编译 contexts;`QnnModelContexts` 按设备在 `noBackupFilesDir` 编译并持久化(缓存键含 CACHE_VERSION=20/QNN 2.49.0/DLC hash/HTP 架构/Build.FINGERPRINT/ABI,checksum 校验,新 context 就绪后惰性回收同模型旧 key 目录);删除 `strip-qnn.gradle.kts` 与 `-Pexclude-qnn-assets`;INT8 全退役,精度枚举收窄为 FP16/FP32/BF16(legacy 2 → FP16),QNN 缓存键固定 `_nf1`;`waifu2x_jni.cpp`/`spatial_qnn_runtime.cpp`/`qnn_context_bridge.cpp` 字节级恢复 | ✅ 一致(资产来源见 §7.4) |
| 1f72f570a | **MediaTek NPU 后端**:`MediaTekNpu`(LiteRT 2.3.0 + NeuroPilot dispatch,17 个 tflite 32.8MB 设备编译,`litert-models/manifest.json` 校验和)、Manifest 6 个 `libneuron*` 可选 native-library、设置页后端枚举 + 准备状态(Preparing/Ready/Failed)+ 重试、默认后端 MTK 优先(次选 Qualcomm,回落 Vulkan)、`NpuModelStatus` 共享状态流 | ✅ 一致 |
| 1715ba574 | zh-rCN 4 个字符串(联发科 NPU / NPU 准备状态×3) | ⚠️ 有意偏离 i18n 规则,见 §7.3-1 |
| 15d9b0a49 | 状态徽标把 MediaTek NPU 处理页标注为 LiteRT(与后端实际运行时一致) | ✅ 一致 |

上游自述 MediaTek 路径未在真机验证(其测试机为 SM8475,Qualcomm);komikku 同样未验证。

### 7.2 审查收尾(fix/upscale-review-followup)

审查发现的 5 项及处置:

1. **zh-rCN 非 base locale 直接编辑(1715ba574)— 决定保留**。与 AGENTS.md "非 base locale 由 Weblate 接管"的硬规则冲突(自查项明确禁止);译者决定接受偏离并在此记录,理由是上游措辞直接可复用、避免中文用户在 Weblate 同步前长期看到英文设置文案。已知代价:Weblate 下次同步该 4 键可能冲突,届时以 Weblate 为准。
2. **[已修] `AndroidManifest.xml` KMK 标记未闭合**:6 个 `libneuron*` 声明的 `<!-- KMK -->` 无闭合。XML 注释内容不允许 `--`,故闭合行写作 `<!-- KMK end -->`(XML 内 KMK 块的既定写法,自本提交起生效)。
3. **[已修] `MediaTekNpu.prepareForCpuValidation` 死代码删除**:上游由 androidTest `MediaTekNpuDeviceTest` 调用,该测试未移植(§7.3-3),钩子在 komikku 无调用者。连同 `prepareInternal` 的 `cpuValidationOnly` 参数与 `litert-cpu-validation` 目录分支一并清理;JNI 侧 `nativeInitialize` 的 `cpuTest`/`prepareOnly` 参数保留(引擎接口字节级一致的前提)。
4. **[已补] JVM 单测**:见 §7.5。
5. **[已修] `app/build.gradle.kts` 冗余嵌套 KMK 标记**:litert 依赖本就在外层 KMK 块内,删除多余的 `// KMK -->`/`// KMK <--` 对。

### 7.3 移植取舍与已决事项

1. **zh-rCN 偏离**:同 §7.2-1,已决:保留。
2. **QNN 资产路线**:选"FP16 DLC + 设备编译"替代"预编译 contexts 入库 + APK 剥离构建"。收益:仓库 −437MB、非 arm64 split 不再需要包后重写、DLC 与 runtime 版本解耦;代价:首次使用每模型一次秒级设备编译(失败显式化,UI 可重试),旧版本静态 context 用户升级后 `cacheDir/qnn-contexts-v*` 残留(无害,系统可回收)。
3. **androidTest 维持不移植**(延续 §6.3-2):上游 `ImageEnhancementCacheTest`/`ImageEnhancerQueueTest`/`MediaTekNpuDeviceTest`/`QualcommHtpDeviceTest`/`NativeModelStartupTest` 均为设备 instrumentation,本 fork 无设备 CI。其中可纯 JVM 化的断言改写入 `app/src/test`(§7.5);位图/真机依赖的用例保持上游-only。`prepareForCpuValidation` 因此删除(§7.2-3)。
4. **默认后端设备自适应**:`realCuganProcessingBackend` 默认值由常量 1(Qualcomm)改为 `Waifu2x.defaultProcessingBackend()`(MTK → MediaTek,Qualcomm HTP 可用 → Qualcomm,否则 Vulkan)。仅影响新装用户;升级用户保留既有存储值。

### 7.4 机械检查(审查时复验)

- ✅ 引擎 cpp 与上游 v1.3.15 字节级一致:`float_tile_processor.h`、`litert_backend.{h,cpp}`、`qnn_context_bridge.cpp`、`spatial_qnn_runtime.{h,cpp}`、`waifu2x_jni.cpp`(git blob 哈希对比);`CMakeLists.txt` 差异仅为构建集成(新增 3 源文件、litert 头文件路径、移除 HTP 架构硬编码)
- ✅ `qnn-models` 15 个 DLC 与 `litert-models` 15 个 tflite 的 SHA-256 均与各自 `manifest.json` 一致,键与文件一一对应
- ✅ `spotlessCheck` 通过;无 `exclude-qnn-assets`/`strip-qnn` 残留引用;`.gitattributes` 已补 `*.tflite binary`
- ✅ i18n:base 新增 4 键合规;zh-rCN 为已记录的有意偏离(§7.2-1)

### 7.5 单元测试(app/src/test,JVM)

蓝本为上游 androidTest,JVM 化后可入 CI(`:app:testReleaseUnitTest`),共 18 用例:

| 测试类 | 覆盖 |
|---|---|
| `ImageEnhancerPriorityTest` | 队列排序语义:可见页(3) > 副页(2) > 提升页(1) > 预载(0)、同级按目标距离、再按 FIFO;副页属于聚焦目标(对应上游 `queuedPagesFollowVisibleSpreadThenPromotionThenDistance` 的断言部分) |
| `ImageEnhancementCacheKeyTest` | `CacheEvent.affects` 键匹配矩阵(Cleared 全命中);configHash 按后端分离(`_qv*_nf1`/`_mtv*`)、legacy INT8 → FP16、纹理守卫修订只作用于 Pro 与 SE denoise3、固定 2x 模型忽略偏好 scale |
| `Waifu2xBackendResolutionTest` | NPU 模型支持矩阵(Pro 2x/3x,其余 2x,Vulkan-only 模型不可路由);`qnnModelName` 与捆绑资产名对齐(含 Pro noise 规范化);`resolveProcessingBackend` 保留显式 NPU 选择、拒绝越界;NPU 强制 FP16 |
| `ImageEnhancementCacheDiscardTest` | compare-and-delete 语义:陈旧快照不得删除并发重存的新条目、匹配快照删除、无快照无条件删除(对应上游 `rejectingAnOldSnapshotDoesNotDeleteItsNewReplacement`) |

实现说明:`discardCachedImage` 成功路径走 `android.util.Log`,JVM 测试内以 `mockkStatic(Log)` 局部放行(不改全局 `isReturnDefaultValues`);`ImageEnhancer`/`Waifu2x`/`QnnModelContexts`/`MediaTekNpu` 的 object 初始化在 JVM 安全(loadLibrary 均有兜底)。位图/Context 依赖的写盘与入队行为仍以真机验证为准。
