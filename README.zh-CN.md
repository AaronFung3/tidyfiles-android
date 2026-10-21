# Komikku_img_upscale

[English](README.md) | 简体中文

### 简介

基于 Komikku 的高性能分支，集成 AI 超分辨率（Real-CUGAN、Real-ESRGAN、Waifu2x 等），支持
Vulkan 与 Qualcomm / MediaTek NPU 处理，实时增强低分辨率漫画。

<p align="center">
  <img width="350" alt="Komikku AI 图像增强预览" src="./.github/readme-images/upscale-preview.png">
  <br>
</p>

> 本文件仅包含图像增强相关说明，应用本身及上游项目的完整功能、下载与许可信息见 [英文 README](README.md)。

---

## 超分辨率模型

| 模型 | 倍数 | 作者 | 项目 / 模型 |
| :--- | :---: | :--- | :--- |
| **Real-CUGAN SE** | 2x / 3x / 4x | Bilibili AI Lab | [Real-CUGAN](https://github.com/bilibili/ailab/tree/main/Real-CUGAN) |
| **Real-CUGAN Pro** | 2x / 3x | Bilibili AI Lab | [Real-CUGAN](https://github.com/bilibili/ailab/tree/main/Real-CUGAN) |
| **Real-CUGAN Nose** | 2x | Bilibili AI Lab | [Real-CUGAN](https://github.com/bilibili/ailab/tree/main/Real-CUGAN) |
| **Real-ESRGAN animevideov3** | 2x / 3x / 4x | Xintao Wang 等 | [Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN) |
| **Real-ESRGAN general-x4v3 (Photo)** | 2x | Xintao Wang 等 | [Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN) |
| **Waifu2x** | 2x | nagadomi | [waifu2x](https://github.com/nagadomi/waifu2x) |
| **Waifu2x (Fast)** | 2x | nagadomi | [waifu2x](https://github.com/nagadomi/waifu2x) |
| **W2xEX Universal Fast** | 2x | AaronFeng753 | [W2xEX 模型文件](https://huggingface.co/randomblock1/W2xEX-ESRGAN) |
| **W2xEX Omni Mini V2** | 2x | AaronFeng753 | [W2xEX 模型文件](https://huggingface.co/randomblock1/W2xEX-ESRGAN) |
| **W2xEX Photo Small** | 2x | AaronFeng753 | [W2xEX 模型文件](https://huggingface.co/randomblock1/W2xEX-ESRGAN) |
| **AnimeJaNai v2 UltraCompact** | 2x | the-database | [OpenModelDB](https://openmodeldb.info/models/2x-AnimeJaNai-v2-UltraCompact) |
| **sudo UltraCompact** | 2x | sudo rm -rf / --no-preserve-root#8353 | [OpenModelDB](https://openmodeldb.info/models/2x-sudo-UltraCompact) |
| **SPAN NomosUni Photo** | 2x | Philip Hofmann | [OpenModelDB](https://openmodeldb.info/models/2x-NomosUni-span-multijpg-ldl) |

## NPU 加速

除 Vulkan 后端外还提供可选的 NPU 后端。Vulkan 与 NPU 在阅读器设置中是彼此独立的选择；
选择 NPU 后，模型列表只保留带有随包 NPU 资产的模型，不支持的设备会保持该选项不可用并继续
使用 Vulkan。

| 后端 | 说明 |
| :--- | :--- |
| **Vulkan GPU** | 支持下表的完整模型列表，精度可选 FP16、FP32 或 BF16 |
| **Qualcomm NPU** | 通过 QNN / HTP 运行 FP16 模型；随包运行时覆盖 HTP v69、v73、v75、v79 与 v81 |
| **MediaTek NPU** | 在 Android 12+ 的 ARM64 联发科设备上通过 LiteRT / NeuroPilot 运行 FP16 模型 |

| 骁龙平台 | SoC | HTP 架构 |
| :--- | :---: | :---: |
| 骁龙 8+ Gen 1 | SM8475 | v69 |
| 骁龙 8 Gen 2 | SM8550 | v73 |
| 骁龙 8 Gen 3 | SM8650 | v75 |
| 骁龙 8 Elite | SM8750 | v79 |
| 骁龙 8 Elite Gen 5 | SM8850 | v81 |

### NPU 模型范围

两个 NPU 后端使用相同的 15 个 FP16 模型变体：

| 模型 | 倍数 | 变体 |
| :--- | :---: | :--- |
| Real-ESRGAN animevideov3 | 2x | 标准 |
| Real-ESRGAN general-x4v3 (Photo) | 2x | 标准 |
| Real-CUGAN SE | 2x | 无降噪、降噪 1 / 2 / 3、保守模式 |
| Real-CUGAN Pro | 2x / 3x | 无降噪、降噪 3、保守模式 |
| W2xEX Photo Small | 2x | 标准 |
| SPAN NomosUni Photo | 2x | 标准 |

实际速度取决于图像尺寸、模型变体、机身温度与设备固件。

NPU 模型以精简的 FP16 DLC / TFLite 资产随包分发，首次使用时在设备上编译。设置页会显示准备
进度，失败后可手动重试；准备完成的模型保存在应用私有目录，重启应用后仍可复用，清理增强图片
缓存不会删除它们。选择 NPU 后端后，失败时不会回落到 CPU 或 Vulkan——需要使用 GPU 请显式
选择 Vulkan。联发科路径尚未在对应硬件上验证，实际可用性取决于设备及其系统驱动。匹配的 HTP
Stub/Skel 运行时库来自 Qualcomm 官方 Maven 构件（`com.qualcomm.qti:qnn-runtime`），因此构建
本项目无需安装 Qualcomm SDK。移植细节与构建配置见
[docs/upscale-port-review.md](./docs/upscale-port-review.md)。

## 致谢

- [HaoweiLi97/mihon_img_upscale](https://github.com/HaoweiLi97/mihon_img_upscale) — 实时 AI
  超分辨率功能（原生 NCNN/Vulkan 引擎、模型流水线、QNN 后端与阅读器集成）移植自他们的工作。
- [Komikku](https://github.com/komikku-app/komikku) — 本分支所基于的应用，以及其上上游
  [Mihon](https://github.com/mihonapp/mihon) 与
  [TachiyomiSY](https://github.com/jobobby04/TachiyomiSY)。
