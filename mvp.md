# 安卓端纯离线「保单管家」MVP 完整研发实现文档

## 1. 项目概览与架构蓝图

### 1.1 MVP 核心产品定义

本产品定位为“零联网权限、端侧离线 OCR 解析、人机协同快速建档”的个人与家庭保单管理工具。

* **核心输入**：在首页点击“导入保单 PDF”调起系统文件选择器，或从微信、邮件、本地文件管理器一键分享/打开电子保单 PDF。
* **核心处理**：自动截取前 $3$ 页进行高清光栅化，通过端侧 CPU（ARM NEON 加速）运行轻量级 PP-OCRv4 模型，结合空间几何锚点与正则表达式提取 $8$ 大核心字段。
* **核心交互**：
  1. **家庭保单首页 (`HomeScreen`)**：汇总展示已导入保单总览（总保单数、年缴总保费）、按家庭成员（被保险人）筛选卡片、查看核心保障额度及触发新保单导入。
  2. **上下分屏双向联动确认页 (`ConfirmPolicyScreen`)**：上半屏展示带 OCR 识别框的原图画布，下半屏展示带红绿灯置信度的表单；支持“点表单自动定位原图”与“点原图文字框一键填入表单（零打字纠错）”。
* **隐私承诺**：`AndroidManifest.xml` 彻底移除 `android.permission.INTERNET` 权限，所有 PDF 原件与结构化数据 $100\%$ 存储在应用私有沙盒内。

### 1.2 工程包结构设计

```text
com.example.policyvault
├── data
│   ├── local
│   │   ├── AppDatabase.kt          // Room 数据库单例
│   │   ├── PolicyDao.kt            // 保单增删改查接口
│   │   └── PolicyEntity.kt         // 保单数据库表实体 (8大核心字段 + 沙盒路径)
│   └── model
│       ├── OcrBlock.kt             // OCR 识别块 (文本 + 归一化矩形坐标)
│       └── DraftPolicy.kt          // 待确认保单草稿 (含字段置信度状态)
├── domain
│   ├── pdf
│   │   └── PdfProcessor.kt         // PDF 沙盒落盘 + 前3页防黑底高清转图
│   ├── ocr
│   │   └── OcrEngineWrapper.kt     // RapidOCR (NCNN) 离线推理封装
│   └── extractor
│       └── SpatialRuleExtractor.kt // 空间几何锚点 + 正则字段提取引擎
└── ui
    ├── confirm
    │   └── ConfirmPolicyScreen.kt  // 上下分屏双向联动确认页 (Jetpack Compose)
    ├── home
    │   └── HomeScreen.kt           // 首页：已导入保单列表、家庭成员筛选与导入入口
    └── MainActivity.kt             // 闭环路由控制器：串联首页、文件选择、解析与确认入库
```

---

## 2. 环境配置与依赖清单

### 2.1 `app/build.gradle.kts` 配置

为控制安装包体积（目标 $\le 25\text{ MB}$），ABI 仅保留现代手机通用的 `arm64-v8a`。

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.example.policyvault"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.policyvault"
        minSdk = 26 // Android 8.0+，全面支持 PdfRenderer 与现代 Compose
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0-mvp"

        ndk {
            // 仅打包 64 位 ARM 架构，立减 50% SO 库体积，覆盖 99.9% 现代安卓手机
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }
}

dependencies {
    // Jetpack Compose 核心套件
    val composeBom = platform("androidx.compose:compose-bom:2024.02.02")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Coroutines 异步协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Room 本地数据库
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // 离线 OCR 引擎：RapidOCR-Android (基于腾讯 NCNN + PP-OCRv4 Mobile)
    implementation("com.github.RapidAI:RapidOcrAndroidOnnx:1.2.0") // 或引入 NCNN AAR
}
```

### 2.2 `AndroidManifest.xml`（零网络权限 + 微信/系统 PDF 分享直达）

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.example.policyvault">

    <!-- 严禁声明 INTERNET 权限，打造纯离线隐私信任壁垒 -->

    <application
        android:allowBackup="true"
        android:label="保单管家"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">

        <activity
            android:name=".ui.MainActivity"
            android:exported="true"
            android:launchMode="singleTask">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>

            <!-- 接收微信、邮件、文件管理器的「用其他应用打开」 -->
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:mimeType="application/pdf" />
            </intent-filter>

            <!-- 接收系统「分享文件给应用」 -->
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="application/pdf" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

---

## 3. 数据层设计：核心数据模型与 Room 数据库

### 3.1 OCR 坐标块与草稿置信度模型 (`OcrBlock.kt` & `DraftPolicy.kt`)

每个识别字段不仅保存字符串值，还保存其对应的 `sourceBlockId` 和置信度状态，用于驱动确认页的高亮联动。

```kotlin
package com.example.policyvault.data.model

import android.graphics.RectF

/**
 * 单页 OCR 识别出的独立文本块
 * @param id 唯一索引，用于表单与原图画布双向绑定
 * @param pageIndex 所在 PDF 页码 (0, 1, 2)
 * @param text 识别出的文本内容
 * @param normRect 归一化矩形坐标 [0.0f, 1.0f]，与渲染分辨率完全解耦
 * @param confidence 识别置信度 (0.0f ~ 1.0f)
 */
data class OcrBlock(
    val id: Int,
    val pageIndex: Int,
    val text: String,
    val normRect: RectF,
    val confidence: Float
)

/**
 * 字段提取置信度分级（驱动 UI 红绿灯提示）
 */
enum class ConfidenceLevel {
    HIGH,   // 🟢 锚点+正则双重命中，极高概率准确
    MEDIUM, // 🟡 全局正则猜测或跨行截断，建议肉眼扫一眼
    LOW     // 🔴 未识别到，需点击上方原图文字框一键填入
}

/**
 * 单个表单字段的包装结构
 */
data class ExtractedField(
    val value: String = "",
    val level: ConfidenceLevel = ConfidenceLevel.LOW,
    val sourceBlockId: Int? = null,
    val pageIndex: Int = 0
)

/**
 * 8 大黄金字段草稿对象
 */
data class DraftPolicy(
    val pdfSandboxPath: String,
    val previewImagePaths: List<String>,
    val insurer: ExtractedField = ExtractedField(),       // 1. 承保公司
    val policyNo: ExtractedField = ExtractedField(),      // 2. 保单号
    val applicant: ExtractedField = ExtractedField(),     // 3. 投保人
    val insured: ExtractedField = ExtractedField(),       // 4. 被保险人
    val productName: ExtractedField = ExtractedField(),   // 5. 主险名称
    val sumInsured: ExtractedField = ExtractedField(),    // 6. 基本保额(元)
    val effectiveDate: ExtractedField = ExtractedField(), // 7. 生效日期(YYYY-MM-DD)
    val annualPremium: ExtractedField = ExtractedField()  // 8. 年缴保费(元)
)
```

### 3.2 Room 数据库实体、DAO 与单例 (`PolicyEntity.kt`, `PolicyDao.kt`, `AppDatabase.kt`)

```kotlin
package com.example.policyvault.data.local

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "policies")
data class PolicyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val insurer: String,           // 承保保司
    val policyNo: String,          // 保单合同号
    val applicant: String,         // 投保人姓名
    val insured: String,           // 被保险人姓名 (用于家庭成员分组)
    val productName: String,       // 主险产品名称
    val sumInsured: Double,        // 基本保额(元)
    val effectiveDate: String,     // 生效日期 YYYY-MM-DD
    val annualPremium: Double,     // 年缴保费(元)
    val pdfSandboxPath: String,    // 沙盒内原件 PDF 路径
    val coverImagePath: String,    // 首页预览图路径
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface PolicyDao {
    @Query("SELECT * FROM policies ORDER BY createdAt DESC")
    fun observeAllPolicies(): Flow<List<PolicyEntity>>

    @Query("SELECT SUM(annualPremium) FROM policies")
    fun observeTotalAnnualPremium(): Flow<Double?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPolicy(policy: PolicyEntity): Long

    @Delete
    suspend fun deletePolicy(policy: PolicyEntity)
}

@Database(entities = [PolicyEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun policyDao(): PolicyDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "policy_vault_offline.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
```

---

## 4. 核心管线一：PDF 沙盒落盘与前 3 页防黑底光栅化 (`PdfProcessor.kt`)

> **关键防坑点**：多数保险公司的电子保单 PDF 背景是**透明通道（$\text{Alpha} = 0$）**。如果直接创建 `Bitmap.Config.ARGB_8888` 并调用 `PdfRenderer.Page.render()`，透明背景默认是全黑的（`#00000000`），会导致黑字印在黑底上，OCR 识别率直接归零！**必须在渲染前将 `Canvas` 显式涂白。**

```kotlin
package com.example.policyvault.domain.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

class PdfProcessor(private val context: Context) {

    companion object {
        private const val MAX_OCR_PAGES = 3          // 靶向截断：默认仅处理前 3 页
        private const val TARGET_LONG_EDGE = 1600f   // 黄金分辨率：长边固定 1600px
    }

    data class PdfRenderResult(
        val sandboxPdfFile: File,
        val pageBitmaps: List<Bitmap>,
        val savedImageFiles: List<File>,
        val totalPdfPages: Int
    )

    /**
     * 将外部传入的 PDF Uri 拷贝至私有沙盒，并渲染前 3 页为高清白底 Bitmap
     */
    suspend fun ingestAndRenderPdf(uri: Uri): PdfRenderResult = withContext(Dispatchers.IO) {
        val policyDir = File(context.filesDir, "policies/${UUID.randomUUID()}").apply { mkdirs() }
        val targetPdfFile = File(policyDir, "origin.pdf")

        // 1. 拷贝外部文件流到私有沙盒（防止外部文件被清理后无法查看原件）
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(targetPdfFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalArgumentException("无法读取该 PDF 文件流")

        val bitmaps = mutableListOf<Bitmap>()
        val imageFiles = mutableListOf<File>()
        var totalPages = 0

        // 2. 调用系统原生 PdfRenderer 光栅化前 3 页
        ParcelFileDescriptor.open(targetPdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                totalPages = renderer.pageCount
                val pagesToRender = minOf(totalPages, MAX_OCR_PAGES)

                for (pageIndex in 0 until pagesToRender) {
                    renderer.openPage(pageIndex).use { page ->
                        // 计算等比例缩放尺寸，确保长边为 1600px
                        val scale = TARGET_LONG_EDGE / max(page.width, page.height).toFloat()
                        val bmpWidth = (page.width * scale).roundToInt()
                        val bmpHeight = (page.height * scale).roundToInt()

                        val bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
                        
                        // 【核心防坑】：先将透明画布填充为纯白色，防止透明背景变黑底导致 OCR 失效
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(Color.WHITE)

                        page.render(
                            bitmap,
                            null,
                            null,
                            PdfRenderer.Page.RENDER_MODE_FOR_PRINT
                        )

                        // 保存预览图至沙盒，供确认页与详情页加载
                        val imgFile = File(policyDir, "page_$pageIndex.jpg")
                        FileOutputStream(imgFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        }

                        bitmaps.add(bitmap)
                        imageFiles.add(imgFile)
                    }
                }
            }
        }

        PdfRenderResult(
            sandboxPdfFile = targetPdfFile,
            pageBitmaps = bitmaps,
            savedImageFiles = imageFiles,
            totalPdfPages = totalPages
        )
    }
}
```

---

## 5. 核心管线二：离线 OCR 引擎封装与坐标归一化 (`OcrEngineWrapper.kt`)

将图片送入离线 OCR 引擎后，把像素级坐标 $(x_{\min}, y_{\min}, x_{\max}, y_{\max})$ 转换为相对于图片宽高的**归一化坐标** $[0.0, 1.0]$：

$$
x_{\text{norm}} = \frac{x_{\text{pixel}}}{W_{\text{bitmap}}}, \quad y_{\text{norm}} = \frac{y_{\text{pixel}}}{H_{\text{bitmap}}}
$$

这样无论手机屏幕多大、画布如何缩放，只需将归一化坐标乘以画布当前渲染宽高即可精准还原位置。

```kotlin
package com.example.policyvault.domain.ocr

import android.graphics.Bitmap
import android.graphics.RectF
import com.example.policyvault.data.model.OcrBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OcrEngineWrapper {

    /**
     * 批量对前 3 页 Bitmap 执行离线 OCR，输出带归一化坐标的 OcrBlock 列表
     */
    suspend fun recognizePages(bitmaps: List<Bitmap>): List<OcrBlock> = withContext(Dispatchers.Default) {
        val allBlocks = mutableListOf<OcrBlock>()
        var globalId = 0

        bitmaps.forEachIndexed { pageIndex, bitmap ->
            val width = bitmap.width.toFloat()
            val height = bitmap.height.toFloat()

            // 调用底层 RapidOCR (NCNN/ONNX) 的检测与识别接口
            val rawResults = runNativeRapidOcr(bitmap)

            for (item in rawResults) {
                val cleanedText = item.text.trim()
                if (cleanedText.isEmpty()) continue

                // 取四点外接矩形并归一化到 [0.0f, 1.0f]
                val left = (item.xs.minOrNull() ?: 0f).coerceIn(0f, width) / width
                val top = (item.ys.minOrNull() ?: 0f).coerceIn(0f, height) / height
                val right = (item.xs.maxOrNull() ?: width).coerceIn(0f, width) / width
                val bottom = (item.ys.maxOrNull() ?: height).coerceIn(0f, height) / height

                allBlocks.add(
                    OcrBlock(
                        id = globalId++,
                        pageIndex = pageIndex,
                        text = cleanedText,
                        normRect = RectF(left, top, right, bottom),
                        confidence = item.score
                    )
                )
            }
        }
        allBlocks
    }

    private data class NativeOcrItem(
        val xs: List<Float>,
        val ys: List<Float>,
        val text: String,
        val score: Float
    )

    private fun runNativeRapidOcr(bitmap: Bitmap): List<NativeOcrItem> {
        // 实际工程中在此处调用 RapidOCR 的 OcrEngine.detect(bitmap) 并映射结果
        // 若处于纯 UI 联调阶段，可返回几条 Mock 坐标块以便测试分屏点选交互
        return listOf(
            NativeOcrItem(listOf(120f, 380f), listOf(140f, 185f), "中国平安人寿保险股份有限公司", 0.99f),
            NativeOcrItem(listOf(120f, 260f), listOf(220f, 260f), "保单号", 0.98f),
            NativeOcrItem(listOf(280f, 620f), listOf(220f, 260f), "P880020260918231", 0.99f),
            NativeOcrItem(listOf(120f, 260f), listOf(300f, 340f), "投保人", 0.98f),
            NativeOcrItem(listOf(280f, 420f), listOf(300f, 340f), "张明远", 0.97f),
            NativeOcrItem(listOf(520f, 660f), listOf(300f, 340f), "被保险人", 0.98f),
            NativeOcrItem(listOf(680f, 820f), listOf(300f, 340f), "张明远", 0.97f),
            NativeOcrItem(listOf(120f, 260f), listOf(400f, 440f), "险种名称", 0.96f),
            NativeOcrItem(listOf(120f, 540f), listOf(460f, 505f), "平安e生保长期百万医疗保险", 0.95f),
            NativeOcrItem(listOf(580f, 720f), listOf(400f, 440f), "基本保额", 0.97f),
            NativeOcrItem(listOf(580f, 760f), listOf(460f, 505f), "200万元", 0.99f),
            NativeOcrItem(listOf(120f, 280f), listOf(560f, 600f), "生效日期", 0.98f),
            NativeOcrItem(listOf(300f, 560f), listOf(560f, 600f), "2026年03月15日", 0.99f),
            NativeOcrItem(listOf(120f, 280f), listOf(640f, 680f), "保费合计", 0.98f),
            NativeOcrItem(listOf(300f, 480f), listOf(640f, 680f), "1280.00元", 0.99f)
        )
    }
}
```

---

## 6. 核心管线三：空间几何锚点与正则提取器 (`SpatialRuleExtractor.kt`)

### 6.1 空间几何匹配数学准则

设锚点框（Key，例如“投保人”）的归一化矩形为 $R_k$，同页任意候选文本框（Candidate）的归一化矩形为 $R_c$。定义锚点框高度为 $H_k = R_k.\text{bottom} - R_k.\text{top}$，宽度为 $W_k = R_k.\text{right} - R_k.\text{left}$。

1. **同行右侧邻近判定（Horizontal Right Neighbor）**：
   * 水平方向位于锚点右侧：$R_c.\text{left} \ge R_k.\text{right} - 0.015$ 且距离不超过半屏：$R_c.\text{left} - R_k.\text{right} \le 0.45$
   * 垂直中心高度对齐：$\lvert R_c.\text{centerY}() - R_k.\text{centerY}() \rvert \le 0.75 \times H_k$
   * 空间惩罚距离得分：$D_{\text{horiz}} = (R_c.\text{left} - R_k.\text{right}) + 2.0 \times \lvert R_c.\text{centerY}() - R_k.\text{centerY}() \rvert$

2. **表格正下方邻近判定（Vertical Bottom Neighbor）**：
   * 垂直方向位于锚点正下方：$0 \le R_c.\text{top} - R_k.\text{bottom} \le 2.2 \times H_k$
   * 水平中心对齐投影重叠：$\lvert R_c.\text{centerX}() - R_k.\text{centerX}() \rvert \le \max(W_k, 0.12)$
   * 空间惩罚距离得分：$D_{\text{vert}} = (R_c.\text{top} - R_k.\text{bottom}) + 1.5 \times \lvert R_c.\text{centerX}() - R_k.\text{centerX}() \rvert$

### 6.2 完整 Kotlin 实现代码 (`SpatialRuleExtractor.kt`)

```kotlin
package com.example.policyvault.domain.extractor

import com.example.policyvault.data.model.*
import kotlin.math.abs
import kotlin.math.max

class SpatialRuleExtractor {

    // 内置国内主流保司简称词库
    private val insurerDict = listOf(
        "平安人寿", "平安财险", "平安养老", "平安健康", "中国平安",
        "中国人寿", "太平洋人寿", "太平洋产险", "中国太保",
        "友邦人寿", "泰康人寿", "泰康在线", "新华人寿", "新华保险",
        "中国人民人寿", "中国人保", "人保健康", "人保财险",
        "众安在线", "众安保险", "阳光人寿", "前海人寿", "同方全球",
        "中意人寿", "工银安盛", "建信人寿", "招商信诺", "百年人寿",
        "信泰人寿", "弘康人寿", "瑞华健康", "复星联合健康", "大都会人寿"
    )

    // 正则表达式库
    private val policyNoRegex = Regex("""[A-Za-z0-9\-]{8,30}""")
    private val dateRegex = Regex("""(20\d{2})\s*[-/年.]\s*(0?[1-9]|1[0-2])\s*[-/月.]\s*(0?[1-9]|[12]\d|3[01])""")
    private val moneyRegex = Regex("""(\d{1,3}(?:,\d{3})*(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)\s*(万元|万|元)?""")
    private val chineseNameRegex = Regex("""^[\u4e00-\u9fa5·]{2,8}$""")

    fun extract(
        blocks: List<OcrBlock>,
        pdfSandboxPath: String,
        previewImagePaths: List<String>
    ): DraftPolicy {
        val insurer = extractInsurer(blocks)
        val policyNo = extractPolicyNo(blocks)
        val applicant = extractPersonName(blocks, listOf("投保人姓名", "投保人", "客户姓名"))
        var insured = extractPersonName(blocks, listOf("被保险人姓名", "被保险人", "被保人"))

        // 如果被保险人识别为“本人”或“同投保人”，自动继承投保人姓名
        if (insured.value in listOf("本人", "同投保人", "投保人本人") && applicant.value.isNotEmpty()) {
            insured = insured.copy(value = applicant.value, level = ConfidenceLevel.HIGH)
        }

        val productName = extractProductName(blocks)
        val sumInsured = extractAmount(blocks, listOf("基本保险金额", "基本保额", "保险金额", "保额"), isSumInsured = true)
        val effectiveDate = extractEffectiveDate(blocks)
        val annualPremium = extractAmount(blocks, listOf("保费合计", "年交保费", "年缴保费", "首期保费", "保险费合计", "保险费"), isSumInsured = false)

        return DraftPolicy(
            pdfSandboxPath = pdfSandboxPath,
            previewImagePaths = previewImagePaths,
            insurer = insurer,
            policyNo = policyNo,
            applicant = applicant,
            insured = insured,
            productName = productName,
            sumInsured = sumInsured,
            effectiveDate = effectiveDate,
            annualPremium = annualPremium
        )
    }

    private fun extractInsurer(blocks: List<OcrBlock>): ExtractedField {
        for (block in blocks) {
            for (brand in insurerDict) {
                if (block.text.contains(brand)) {
                    return ExtractedField(brand, ConfidenceLevel.HIGH, block.id, block.pageIndex)
                }
            }
        }
        val fallback = blocks.firstOrNull { it.text.contains("保险") && it.text.contains("公司") }
        return if (fallback != null) {
            ExtractedField(fallback.text, ConfidenceLevel.MEDIUM, fallback.id, fallback.pageIndex)
        } else {
            ExtractedField()
        }
    }

    private fun extractPolicyNo(blocks: List<OcrBlock>): ExtractedField {
        val anchors = listOf("保险单号", "保单编号", "保单号码", "合同编号", "保单号")
        for (block in blocks) {
            if (anchors.any { block.text.contains(it) }) {
                val inlineMatch = policyNoRegex.find(block.text)
                if (inlineMatch != null && inlineMatch.value.any { it.isDigit() }) {
                    return ExtractedField(inlineMatch.value, ConfidenceLevel.HIGH, block.id, block.pageIndex)
                }
                val neighbors = findSpatialNeighbors(block, blocks)
                for (cand in neighbors) {
                    val m = policyNoRegex.find(cand.text)
                    if (m != null && m.value.count { it.isDigit() } >= 6) {
                        return ExtractedField(m.value, ConfidenceLevel.HIGH, cand.id, cand.pageIndex)
                    }
                }
            }
        }
        return ExtractedField()
    }

    private fun extractPersonName(blocks: List<OcrBlock>, anchors: List<String>): ExtractedField {
        val noiseWords = setOf("姓名", "性别", "男", "女", "出生日期", "证件号码", "身份证", "投保人", "被保险人", "关系")
        for (block in blocks) {
            val matchedAnchor = anchors.firstOrNull { block.text.contains(it) } ?: continue
            val afterColon = block.text.substringAfter(matchedAnchor).replace(Regex("[:：\\s]"), "")
            if (chineseNameRegex.matches(afterColon) && afterColon !in noiseWords) {
                return ExtractedField(afterColon, ConfidenceLevel.HIGH, block.id, block.pageIndex)
            }
            val neighbors = findSpatialNeighbors(block, blocks)
            for (cand in neighbors) {
                val cleaned = cand.text.replace(Regex("[:：\\s]"), "")
                if (chineseNameRegex.matches(cleaned) && cleaned !in noiseWords) {
                    return ExtractedField(cleaned, ConfidenceLevel.HIGH, cand.id, cand.pageIndex)
                }
            }
        }
        return ExtractedField()
    }

    private fun extractProductName(blocks: List<OcrBlock>): ExtractedField {
        val anchors = listOf("险种名称", "保险名称", "主险名称", "产品名称")
        for (block in blocks) {
            if (anchors.any { block.text.contains(it) }) {
                val neighbors = findSpatialNeighbors(block, blocks)
                val target = neighbors.firstOrNull {
                    it.text.length >= 5 && (it.text.contains("保险") || it.text.contains("重疾") || it.text.contains("医疗") || it.text.contains("寿险"))
                }
                if (target != null) {
                    return ExtractedField(target.text, ConfidenceLevel.MEDIUM, target.id, target.pageIndex)
                }
            }
        }
        val globalMatch = blocks.firstOrNull {
            it.text.length in 6..32 && (it.text.endsWith("保险") || it.text.contains("保险（") || it.text.contains("保险("))
                    && !it.text.contains("公司") && !it.text.contains("条款")
        }
        return if (globalMatch != null) {
            ExtractedField(globalMatch.text, ConfidenceLevel.MEDIUM, globalMatch.id, globalMatch.pageIndex)
        } else {
            ExtractedField()
        }
    }

    private fun extractAmount(blocks: List<OcrBlock>, anchors: List<String>, isSumInsured: Boolean): ExtractedField {
        for (block in blocks) {
            val matchedAnchor = anchors.firstOrNull { block.text.contains(it) } ?: continue
            val candidates = listOf(block) + findSpatialNeighbors(block, blocks)
            for (cand in candidates) {
                val textToSearch = if (cand.id == block.id) cand.text.substringAfter(matchedAnchor) else cand.text
                val match = moneyRegex.find(textToSearch) ?: continue
                val rawNumStr = match.groupValues[1].replace(",", "")
                val unit = match.groupValues[2]
                val num = rawNumStr.toDoubleOrNull() ?: continue

                if (isSumInsured && num < 100 && !unit.contains("万")) continue
                if (!isSumInsured && (num <= 0 || num > 1_000_000)) continue

                val finalAmount = if (unit.contains("万")) num * 10000.0 else num
                val formatted = if (finalAmount % 1.0 == 0.0) finalAmount.toLong().toString() else finalAmount.toString()
                return ExtractedField(formatted, ConfidenceLevel.HIGH, cand.id, cand.pageIndex)
            }
        }
        return ExtractedField()
    }

    private fun extractEffectiveDate(blocks: List<OcrBlock>): ExtractedField {
        val anchors = listOf("生效日期", "合同生效日", "保险期间自", "保险期间", "保障期限")
        for (block in blocks) {
            if (anchors.any { block.text.contains(it) }) {
                val candidates = listOf(block) + findSpatialNeighbors(block, blocks)
                for (cand in candidates) {
                    val m = dateRegex.find(cand.text)
                    if (m != null) {
                        val y = m.groupValues[1]
                        val month = m.groupValues[2].padStart(2, '0')
                        val d = m.groupValues[3].padStart(2, '0')
                        return ExtractedField("$y-$month-$d", ConfidenceLevel.HIGH, cand.id, cand.pageIndex)
                    }
                }
            }
        }
        return ExtractedField()
    }

    private fun findSpatialNeighbors(anchor: OcrBlock, allBlocks: List<OcrBlock>): List<OcrBlock> {
        val rKey = anchor.normRect
        val hKey = max(rKey.height(), 0.01f)
        val wKey = max(rKey.width(), 0.02f)

        return allBlocks
            .filter { it.pageIndex == anchor.pageIndex && it.id != anchor.id }
            .mapNotNull { cand ->
                val rCand = cand.normRect
                val dyCenter = abs(rCand.centerY() - rKey.centerY())
                val dxRight = rCand.left - rKey.right

                val isRightNeighbor = dxRight >= -0.015f && dxRight <= 0.45f && dyCenter <= 0.75f * hKey
                val dyBottom = rCand.top - rKey.bottom
                val dxCenter = abs(rCand.centerX() - rKey.centerX())
                val isBottomNeighbor = dyBottom >= -0.005f && dyBottom <= 2.2f * hKey && dxCenter <= max(wKey, 0.12f)

                when {
                    isRightNeighbor -> cand to (dxRight + 2.0f * dyCenter)
                    isBottomNeighbor -> cand to (dyBottom + 1.5f * dxCenter + 0.05f)
                    else -> null
                }
            }
            .sortedBy { it.second }
            .map { it.first }
    }
}
```

---

## 7. 核心交互一：上下分屏双向联动确认页 (`ConfirmPolicyScreen.kt`)

本界面解决端侧 OCR 最后 $5\%$ 的长尾识别误差：

1. **点击下半屏任意表单项** $\rightarrow$ 自动切换至该字段所在的 PDF 页码，并在上半屏原图画布中用**琥珀色高亮框**标出该文字所在位置。
2. **点击上半屏原图中的任意绿色文字框** $\rightarrow$ 将该文字框的内容**一键填入当前聚焦的输入框**，实现“零键盘打字纠错”。

```kotlin
package com.example.policyvault.ui.confirm

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.policyvault.data.model.*

enum class PolicyFieldKey(val label: String) {
    INSURER("承保公司"),
    POLICY_NO("保单编号"),
    APPLICANT("投保人"),
    INSURED("被保险人"),
    PRODUCT_NAME("主险名称"),
    SUM_INSURED("基本保额 (元)"),
    EFFECTIVE_DATE("生效日期"),
    ANNUAL_PREMIUM("年缴保费 (元)")
}

@Composable
fun ConfirmPolicyScreen(
    pageBitmaps: List<Bitmap>,
    ocrBlocks: List<OcrBlock>,
    initialDraft: DraftPolicy,
    onCancel: () -> Unit,
    onConfirmSave: (DraftPolicy) -> Unit
) {
    var draft by remember { mutableStateOf(initialDraft) }
    var activeFieldKey by remember { mutableStateOf(PolicyFieldKey.POLICY_NO) }
    var currentPageIndex by remember { mutableIntStateOf(0) }

    val activeField = remember(draft, activeFieldKey) {
        when (activeFieldKey) {
            PolicyFieldKey.INSURER -> draft.insurer
            PolicyFieldKey.POLICY_NO -> draft.policyNo
            PolicyFieldKey.APPLICANT -> draft.applicant
            PolicyFieldKey.INSURED -> draft.insured
            PolicyFieldKey.PRODUCT_NAME -> draft.productName
            PolicyFieldKey.SUM_INSURED -> draft.sumInsured
            PolicyFieldKey.EFFECTIVE_DATE -> draft.effectiveDate
            PolicyFieldKey.ANNUAL_PREMIUM -> draft.annualPremium
        }
    }

    LaunchedEffect(activeFieldKey) {
        activeField.sourceBlockId?.let {
            currentPageIndex = activeField.pageIndex.coerceIn(0, (pageBitmaps.size - 1).coerceAtLeast(0))
        }
    }

    fun updateFieldValue(key: PolicyFieldKey, newValue: String, blockId: Int? = null) {
        val updated = ExtractedField(
            value = newValue,
            level = ConfidenceLevel.HIGH,
            sourceBlockId = blockId,
            pageIndex = currentPageIndex
        )
        draft = when (key) {
            PolicyFieldKey.INSURER -> draft.copy(insurer = updated)
            PolicyFieldKey.POLICY_NO -> draft.copy(policyNo = updated)
            PolicyFieldKey.APPLICANT -> draft.copy(applicant = updated)
            PolicyFieldKey.INSURED -> draft.copy(insured = updated)
            PolicyFieldKey.PRODUCT_NAME -> draft.copy(productName = updated)
            PolicyFieldKey.SUM_INSURED -> draft.copy(sumInsured = updated)
            PolicyFieldKey.EFFECTIVE_DATE -> draft.copy(effectiveDate = updated)
            PolicyFieldKey.ANNUAL_PREMIUM -> draft.copy(annualPremium = updated)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF8FAFC))) {
        // ==================== 上半屏 (42%)：可缩放原图 + 可点击 OCR 识别框 ====================
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.42f)
                .background(Color(0xFF1E293B))
                .clipToBounds()
        ) {
            val currentBitmap = pageBitmaps.getOrNull(currentPageIndex)
            val currentPageBlocks = remember(ocrBlocks, currentPageIndex) {
                ocrBlocks.filter { it.pageIndex == currentPageIndex }
            }

            if (currentBitmap != null) {
                InteractiveOcrCanvas(
                    bitmap = currentBitmap,
                    blocks = currentPageBlocks,
                    highlightedBlockId = activeField.sourceBlockId,
                    onBlockClicked = { clickedBlock ->
                        updateFieldValue(activeFieldKey, clickedBlock.text, clickedBlock.id)
                    }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "💡 点击图上绿框可一键填入【${activeFieldKey.label}】",
                    color = Color.White,
                    fontSize = 12.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    pageBitmaps.indices.forEach { idx ->
                        FilterChip(
                            selected = currentPageIndex == idx,
                            onClick = { currentPageIndex = idx },
                            label = { Text("第${idx + 1}页", fontSize = 11.sp) }
                        )
                    }
                }
            }
        }

        // ==================== 下半屏 (58%)：带红绿灯置信度的核对表单 ====================
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.58f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "请核对保单关键信息（🔒 纯离线本地解析）",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color(0xFF0F172A)
                )
                TextButton(onClick = onCancel) {
                    Text("取消", color = Color(0xFF64748B))
                }
            }

            PolicyFieldKey.entries.forEach { key ->
                val fieldData = when (key) {
                    PolicyFieldKey.INSURER -> draft.insurer
                    PolicyFieldKey.POLICY_NO -> draft.policyNo
                    PolicyFieldKey.APPLICANT -> draft.applicant
                    PolicyFieldKey.INSURED -> draft.insured
                    PolicyFieldKey.PRODUCT_NAME -> draft.productName
                    PolicyFieldKey.SUM_INSURED -> draft.sumInsured
                    PolicyFieldKey.EFFECTIVE_DATE -> draft.effectiveDate
                    PolicyFieldKey.ANNUAL_PREMIUM -> draft.annualPremium
                }
                val isSelected = activeFieldKey == key

                ConfidenceFormRow(
                    label = key.label,
                    field = fieldData,
                    isFocused = isSelected,
                    onFocus = { activeFieldKey = key },
                    onValueChange = { updateFieldValue(key, it, fieldData.sourceBlockId) }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { onConfirmSave(draft) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("确认无误，存入本地保单库", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun InteractiveOcrCanvas(
    bitmap: Bitmap,
    blocks: List<OcrBlock>,
    highlightedBlockId: Int?,
    onBlockClicked: (OcrBlock) -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 4f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            }
            .pointerInput(blocks, scale, offset, canvasSize) {
                detectTapGestures { tapPos ->
                    if (canvasSize.width == 0 || canvasSize.height == 0) return@detectTapGestures
                    val centerX = canvasSize.width / 2f
                    val centerY = canvasSize.height / 2f
                    val rawX = (tapPos.x - offset.x - centerX) / scale + centerX
                    val rawY = (tapPos.y - offset.y - centerY) / scale + centerY

                    val normX = rawX / canvasSize.width.toFloat()
                    val normY = rawY / canvasSize.height.toFloat()

                    val hit = blocks.firstOrNull { it.normRect.contains(normX, normY) }
                    if (hit != null) onBlockClicked(hit)
                }
            }
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y
            )
    ) {
        drawImage(
            image = bitmap.asImageBitmap(),
            dstSize = IntSize(size.width.toInt(), size.height.toInt())
        )

        blocks.forEach { block ->
            val isHighlighted = block.id == highlightedBlockId
            val left = block.normRect.left * size.width
            val top = block.normRect.top * size.height
            val rectW = block.normRect.width() * size.width
            val rectH = block.normRect.height() * size.height

            drawRect(
                color = if (isHighlighted) Color(0x66F59E0B) else Color(0x2210B981),
                topLeft = Offset(left, top),
                size = Size(rectW, rectH)
            )
            drawRect(
                color = if (isHighlighted) Color(0xFFF59E0B) else Color(0xFF10B981),
                topLeft = Offset(left, top),
                size = Size(rectW, rectH),
                style = Stroke(width = if (isHighlighted) 4f else 1.5f)
            )
        }
    }
}

@Composable
private fun ConfidenceFormRow(
    label: String,
    field: ExtractedField,
    isFocused: Boolean,
    onFocus: () -> Unit,
    onValueChange: (String) -> Unit
) {
    val badgeText = when (field.level) {
        ConfidenceLevel.HIGH -> "🟢 准确"
        ConfidenceLevel.MEDIUM -> "🟡 待核对"
        ConfidenceLevel.LOW -> "🔴 点上方原图填入"
    }
    val borderColor = when {
        isFocused -> Color(0xFF2563EB)
        field.level == ConfidenceLevel.LOW -> Color(0xFFEF4444)
        field.level == ConfidenceLevel.MEDIUM -> Color(0xFFF59E0B)
        else -> Color(0xFFCBD5E1)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(width = if (isFocused) 2.dp else 1.dp, color = borderColor, shape = RoundedCornerShape(8.dp))
            .background(Color.White, RoundedCornerShape(8.dp))
            .clickable { onFocus() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, fontSize = 12.sp, color = Color(0xFF64748B), fontWeight = FontWeight.Medium)
            Text(text = badgeText, fontSize = 11.sp, color = Color(0xFF475569))
        }
        OutlinedTextField(
            value = field.value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("未提取到，可直接点击上方原图对应文字框", fontSize = 13.sp) }
        )
    }
}
```

---

## 8. 核心交互二：首页保单看板与导入入口 (`HomeScreen.kt`)

首页聚焦最纯粹的两件事：**查看已导入的保单**（含家庭年缴保费汇总、按被保险人筛选、核心保额与保单号卡片展示、删除操作）与**触发导入新保单 PDF**（支持底部悬浮按钮调起系统文件选择器，以及空状态下的操作指引）。

```kotlin
package com.example.policyvault.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.policyvault.data.local.PolicyEntity
import java.text.DecimalFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    policies: List<PolicyEntity>,
    onImportPdfClick: () -> Unit,
    onDeletePolicy: (PolicyEntity) -> Unit
) {
    // 提取所有不重复的“被保险人”姓名，生成家庭成员筛选标签
    val familyMembers = remember(policies) {
        listOf("全部") + policies.map { it.insured.ifBlank { "未命名" } }.distinct()
    }
    var selectedMember by remember { mutableStateOf("全部") }

    // 根据当前选中的家庭成员过滤保单列表
    val filteredPolicies = remember(policies, selectedMember) {
        if (selectedMember == "全部") policies
        else policies.filter { (it.insured.ifBlank { "未命名" }) == selectedMember }
    }

    // 计算当前视图下的年缴总保费
    val totalAnnualPremium = remember(filteredPolicies) {
        filteredPolicies.sumOf { it.annualPremium }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = Color(0xFF2563EB),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("离线保单管家", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    }
                },
                actions = {
                    Surface(
                        color = Color(0xFFDCFCE7),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Text(
                            text = "🔒 纯本地零联网",
                            color = Color(0xFF15803D),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onImportPdfClick,
                containerColor = Color(0xFF2563EB),
                contentColor = Color.White,
                icon = { Icon(Icons.Default.Add, contentDescription = "导入保单") },
                text = { Text("导入保单 PDF", fontWeight = FontWeight.SemiBold) }
            )
        },
        containerColor = Color(0xFFF1F5F9)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // 1. 顶部家庭保单统计总览卡片
            SummaryDashboardCard(
                policyCount = filteredPolicies.size,
                totalPremium = totalAnnualPremium,
                selectedMember = selectedMember
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 2. 家庭成员（被保险人）横向筛选栏
            if (policies.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    familyMembers.forEach { member ->
                        FilterChip(
                            selected = selectedMember == member,
                            onClick = { selectedMember = member },
                            label = { Text(member, fontSize = 13.sp) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // 3. 保单列表或空状态引导
            if (filteredPolicies.isEmpty()) {
                EmptyPolicyGuide(onImportPdfClick = onImportPdfClick)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    items(filteredPolicies, key = { it.id }) { policy ->
                        PolicyItemCard(
                            policy = policy,
                            onDelete = { onDeletePolicy(policy) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 顶部统计看板：展示有效保单数量与年缴总保费
 */
@Composable
private fun SummaryDashboardCard(
    policyCount: Int,
    totalPremium: Double,
    selectedMember: String
) {
    val decimalFormat = remember { DecimalFormat("#,##0.00") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = if (selectedMember == "全部") "家庭有效保单" else "${selectedMember} 的保单",
                    color = Color(0xFF94A3B8),
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "$policyCount",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "份",
                        color = Color(0xFFCBD5E1),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "年缴总保费预估",
                    color = Color(0xFF94A3B8),
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "¥ ${decimalFormat.format(totalPremium)}",
                    color = Color(0xFF38BDF8),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * 单张已导入保单展示卡片
 */
@Composable
private fun PolicyItemCard(
    policy: PolicyEntity,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 第一行：承保保司标签 + 保单号 + 删除按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = Color(0xFFEFF6FF),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = policy.insurer.ifBlank { "未知保司" },
                            color = Color(0xFF1D4ED8),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "单号: ${policy.policyNo.ifBlank { "--" }}",
                        color = Color(0xFF64748B),
                        fontSize = 12.sp
                    )
                }

                IconButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "删除保单",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 第二行：主险产品名称
            Text(
                text = policy.productName.ifBlank { "未命名保险产品" },
                color = Color(0xFF0F172A),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(14.dp))

            // 第三行：三列核心指标（被保险人 | 基本保额 | 年缴保费）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF8FAFC), RoundedCornerShape(8.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricColumn(label = "被保险人", value = policy.insured.ifBlank { "--" })
                MetricColumn(label = "基本保额", value = formatSumInsured(policy.sumInsured))
                MetricColumn(label = "年缴保费", value = "¥${policy.annualPremium.toInt()}/年")
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 第四行：投保人与生效日期
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "投保人：${policy.applicant.ifBlank { "--" }}",
                    color = Color(0xFF64748B),
                    fontSize = 12.sp
                )
                Text(
                    text = "生效日：${policy.effectiveDate.ifBlank { "--" }}",
                    color = Color(0xFF64748B),
                    fontSize = 12.sp
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认移除该保单？") },
            text = { Text("移除后将删除本地保单记录「${policy.productName}」。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    }
                ) {
                    Text("确认删除", color = Color(0xFFEF4444))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun MetricColumn(label: String, value: String) {
    Column {
        Text(text = label, color = Color(0xFF64748B), fontSize = 11.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = value, color = Color(0xFF0F172A), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 空状态引导卡片：指导用户点击按钮选择本地 PDF 或从微信直接分享导入
 */
@Composable
private fun EmptyPolicyGuide(onImportPdfClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                tint = Color(0xFF3B82F6),
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "尚未导入任何保单",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0F172A)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "支持两种方式离线导入电子保单 PDF：\n1. 点击下方按钮，从手机本地文件中选择 PDF\n2. 在微信打开保单 PDF，点击右上角「用其他应用打开」选择本应用",
                fontSize = 13.sp,
                color = Color(0xFF64748B),
                lineHeight = 20.sp
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onImportPdfClick,
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("立即选择保单 PDF 导入")
            }
        }
    }
}

private fun formatSumInsured(amount: Double): String {
    return when {
        amount >= 10000.0 -> {
            val wan = amount / 10000.0
            if (wan % 1.0 == 0.0) "${wan.toInt()}万元" else "${wan}万元"
        }
        amount > 0 -> "${amount.toInt()}元"
        else -> "--"
    }
}
```

---

## 9. 应用主入口与全链路闭环路由 (`MainActivity.kt`)

`MainActivity` 将首页展示、系统 PDF 文件选择器、外部微信分享接收、端侧 OCR 异步解析以及分屏核对入库完整串联为一个轻量级状态机（`Home` $\leftrightarrow$ `Processing` $\leftrightarrow$ `Confirm`），无需复杂的第三方导航框架即可直接编译运行。

```kotlin
package com.example.policyvault.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.policyvault.data.local.AppDatabase
import com.example.policyvault.data.local.PolicyEntity
import com.example.policyvault.data.model.DraftPolicy
import com.example.policyvault.data.model.OcrBlock
import com.example.policyvault.domain.extractor.SpatialRuleExtractor
import com.example.policyvault.domain.ocr.OcrEngineWrapper
import com.example.policyvault.domain.pdf.PdfProcessor
import com.example.policyvault.ui.confirm.ConfirmPolicyScreen
import com.example.policyvault.ui.home.HomeScreen
import kotlinx.coroutines.launch

sealed interface AppScreenState {
    data object Home : AppScreenState
    data class Processing(val statusMessage: String) : AppScreenState
    data class Confirm(
        val pageBitmaps: List<Bitmap>,
        val ocrBlocks: List<OcrBlock>,
        val draft: DraftPolicy
    ) : AppScreenState
}

class MainActivity : ComponentActivity() {

    private val database by lazy { AppDatabase.getInstance(this) }
    private val pdfProcessor by lazy { PdfProcessor(this) }
    private val ocrEngine by lazy { OcrEngineWrapper() }
    private val ruleExtractor by lazy { SpatialRuleExtractor() }

    private var screenState by mutableStateOf<AppScreenState>(AppScreenState.Home)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)

        setContent {
            MaterialTheme {
                // 订阅本地 Room 数据库中的全部保单列表
                val policies by database.policyDao().observeAllPolicies().collectAsState(initial = emptyList())

                // 注册系统原生 PDF 文件选择器 (无需申请存储权限)
                val pdfPickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri: Uri? ->
                    if (uri != null) {
                        startPdfImportPipeline(uri)
                    }
                }

                when (val state = screenState) {
                    is AppScreenState.Home -> {
                        HomeScreen(
                            policies = policies,
                            onImportPdfClick = {
                                pdfPickerLauncher.launch(arrayOf("application/pdf"))
                            },
                            onDeletePolicy = { policy ->
                                lifecycleScope.launch {
                                    database.policyDao().deletePolicy(policy)
                                }
                            }
                        )
                    }

                    is AppScreenState.Processing -> {
                        ProcessingLoadingOverlay(message = state.statusMessage)
                    }

                    is AppScreenState.Confirm -> {
                        ConfirmPolicyScreen(
                            pageBitmaps = state.pageBitmaps,
                            ocrBlocks = state.ocrBlocks,
                            initialDraft = state.draft,
                            onCancel = { screenState = AppScreenState.Home },
                            onConfirmSave = { confirmedDraft ->
                                saveConfirmedPolicy(confirmedDraft)
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    /**
     * 拦截从微信或系统文件管理器“用其他应用打开 / 分享”传入的 PDF Uri
     */
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                intent.data?.let { startPdfImportPipeline(it) }
            }
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val streamUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                streamUri?.let { startPdfImportPipeline(it) }
            }
        }
    }

    /**
     * 核心端侧流水线：PDF 落盘与前 3 页光栅化 -> 离线 OCR 推理 -> 空间几何锚点提取 -> 进入分屏确认页
     */
    private fun startPdfImportPipeline(uri: Uri) {
        lifecycleScope.launch {
            try {
                screenState = AppScreenState.Processing("正在截取保单前 3 页并生成高清预览...")
                val renderResult = pdfProcessor.ingestAndRenderPdf(uri)

                screenState = AppScreenState.Processing("正在运行端侧离线 OCR 识别保单文字...")
                val ocrBlocks = ocrEngine.recognizePages(renderResult.pageBitmaps)

                screenState = AppScreenState.Processing("正在提取保单 8 大核心字段...")
                val draft = ruleExtractor.extract(
                    blocks = ocrBlocks,
                    pdfSandboxPath = renderResult.sandboxPdfFile.absolutePath,
                    previewImagePaths = renderResult.savedImageFiles.map { it.absolutePath }
                )

                screenState = AppScreenState.Confirm(
                    pageBitmaps = renderResult.pageBitmaps,
                    ocrBlocks = ocrBlocks,
                    draft = draft
                )
            } catch (e: Exception) {
                screenState = AppScreenState.Home
                Toast.makeText(this@MainActivity, "保单导入失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 将用户确认后的草稿写入本地 Room 数据库，并切回首页列表
     */
    private fun saveConfirmedPolicy(draft: DraftPolicy) {
        lifecycleScope.launch {
            val entity = PolicyEntity(
                insurer = draft.insurer.value.trim(),
                policyNo = draft.policyNo.value.trim(),
                applicant = draft.applicant.value.trim(),
                insured = draft.insured.value.trim(),
                productName = draft.productName.value.trim(),
                sumInsured = draft.sumInsured.value.toDoubleOrNull() ?: 0.0,
                effectiveDate = draft.effectiveDate.value.trim(),
                annualPremium = draft.annualPremium.value.toDoubleOrNull() ?: 0.0,
                pdfSandboxPath = draft.pdfSandboxPath,
                coverImagePath = draft.previewImagePaths.firstOrNull().orEmpty()
            )
            database.policyDao().insertPolicy(entity)
            Toast.makeText(this@MainActivity, "已安全存入本地保单库", Toast.LENGTH_SHORT).show()
            screenState = AppScreenState.Home
        }
    }
}

@Composable
private fun ProcessingLoadingOverlay(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color(0xFF38BDF8))
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = message,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "🔒 全程在手机本地 CPU 运行，不消耗流量不上传云端",
                color = Color(0xFF94A3B8),
                fontSize = 12.sp
            )
        }
    }
}
```

---

## 10. MVP 研发联调与上线前验收清单 (Checklist)

| 验收维度 | 测试项与技术指标 | 预期达标标准 |
| :--- | :--- | :--- |
| **首页导入与展示闭环** | 点击首页右下角“导入保单 PDF”选择文件 $\rightarrow$ 确认页保存 $\rightarrow$ 返回首页 | 首页列表立即刷新展示新保单卡片，顶部总保单数与年缴总保费实时累加，支持按被保险人筛选 |
| **防黑底渲染测试** | 导入平安、国寿、众安等背景透明的矢量电子保单 PDF | 渲染出的前 $3$ 页 `Bitmap` 必须为纯白底黑字，无任何黑色背景色块污染 |
| **内存与耗时控制** | 导入一份 $65$ 页的长期重疾险 PDF（文件大小 $> 8\text{ MB}$） | 仅截取前 $3$ 页光栅化与 OCR，端侧总处理耗时 $\le 1.5\text{ 秒}$，内存峰值增量 $< 80\text{ MB}$ |
| **双向联动体验** | 识别漏掉或错位的字段，在确认页点击原图上的绿色文字框 | 点击后 $\le 50\text{ ms}$ 内文字准确写入当前高亮的表单输入框，无需弹出软键盘 |
| **纯离线隐私自证** | 在系统应用详情页查看 App 权限列表，或开启飞行模式测试 | 权限列表显示“无任何网络权限”，飞行模式下全流程 $100\%$ 正常运行 |
| **包体积控制** | 执行 `./gradlew assembleRelease` 打包签名 APK | 仅保留 `arm64-v8a` 架构下，Release APK 体积控制在 $20\text{ MB} \sim 24\text{ MB}$ 之间 |