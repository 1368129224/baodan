# 保单管家 (PolicyVault) - 安卓端纯离线 MVP

[![Build arm64-v8a APK](https://github.com/1368129224/baodan/actions/workflows/build-apk.yml/badge.svg)](https://github.com/1368129224/baodan/actions/workflows/build-apk.yml)

一款专注于**零联网权限、端侧离线 OCR 解析、人机协同快速建档**的个人与家庭保单管理工具（仅针对 Android 端优化）。

---

## 🌟 核心特性

1. **绝对隐私自证**
   - `AndroidManifest.xml` 中**彻底移除 `android.permission.INTERNET` 权限**。
   - 所有电子保单原件与结构化数据 100% 存储于安卓应用私有沙盒中，零流量消耗，零云端上传风险。
2. **多源保单导入**
   - 首页一键调用系统原生文件选择器挑选 PDF。
   - 支持从微信、邮件、文件管理器通过「用其他应用打开」或「分享文件」直达本应用。
3. **防黑底高清光栅化**
   - 靶向截取保单前 3 页，Canvas 显式涂白解决透明通道黑色底纹问题。
   - 长边等比例缩放至 1600px，平衡端侧 OCR 精度与内存占用。
4. **上下分屏双向联动核对**
   - **点表单联动原图**：点击下半屏表单字段，上半屏画布自动切至对应页并高亮显示原图文字框。
   - **点原图一键填表**：点击上半屏原图绿色文字框，内容瞬间填入当前选中表单，实现“零打字极速纠错”。
5. **空间几何与规则提取引擎**
   - 基于空间邻近几何判定（同行右侧 neighbor、表格垂直下方 neighbor）与正则规则提取 8 大核心字段：承保公司、保单号、投保人、被保险人、主险名称、基本保额、生效日期、年缴保费。
6. **家庭保单看板**
   - 自动按被保险人生成家庭成员横向筛选标签。
   - 统计有效保单数量与年缴保费总额。
7. **极简包体积与 ARM v8a 专属优化**
   - `ndk.abiFilters` 仅保留 `arm64-v8a`，剥离冗余架构 SO 库，运行更轻快。

---

## 🏗️ 技术架构

- **UI 框架**：Jetpack Compose + Material 3
- **本地存储**：Room Database (SQLite) + Kotlin Coroutines & Flow
- **PDF 光栅化**：Android 原生 `PdfRenderer`
- **构建工具**：Gradle 8.6 + Android Gradle Plugin 8.3.2 + Kotlin 1.9.22 (KSP)
- **编译架构**：仅支持 `arm64-v8a`（覆盖现代主流 64 位安卓机型）

---

## 📂 项目工程结构

```text
com.example.policyvault
├── data
│   ├── local
│   │   ├── AppDatabase.kt          // Room 数据库单例
│   │   ├── PolicyDao.kt            // 保单增删改查 DAO
│   │   └── PolicyEntity.kt         // 保单本地表实体 (8大核心字段 + 沙盒路径)
│   └── model
│       ├── OcrBlock.kt             // OCR 坐标块 (文本 + 归一化矩形坐标)
│       └── DraftPolicy.kt          // 待确认保单草稿 (含红绿灯置信度状态)
├── domain
│   ├── pdf
│   │   └── PdfProcessor.kt         // PDF 沙盒落盘与前3页防黑底光栅化
│   ├── ocr
│   │   └── OcrEngineWrapper.kt     // 离线 OCR 引擎封装与坐标归一化
│   └── extractor
│       └── SpatialRuleExtractor.kt // 空间几何锚点 + 正则字段提取引擎
└── ui
    ├── confirm
    │   └── ConfirmPolicyScreen.kt  // 上下分屏双向联动确认页 (Compose)
    ├── home
    │   └── HomeScreen.kt           // 首页家庭保单看板与导入入口
    └── MainActivity.kt             // 核心路由控制器 (Home <-> Processing <-> Confirm)
```

---

## 🚀 自动编译与下载 (GitHub Actions)

本项目已配置 GitHub Actions 自动化工作流：
每次代码推送到 `main` 分支时，将自动通过 GitHub 托管的云端流水线编译出最新的 `arm64-v8a` 架构 Release APK。

- **工作流文件**：`.github/workflows/build-apk.yml`
- **下载方式**：可在 GitHub 仓库的 **Actions** 标签页下载构建产生的 Artifact 产物，或在 **Releases** 页面直接下载 `PolicyVault-arm64-v8a-release.apk` 安装。

---

## 💻 本地构建指引

若在本地 Android Studio 或命令行中构建：

```bash
# 赋予 gradlew 执行权限
chmod +x gradlew

# 编译 arm64-v8a Release APK
./gradlew assembleRelease

# 生成的 APK 位于：
# app/build/outputs/apk/release/app-release.apk
```
