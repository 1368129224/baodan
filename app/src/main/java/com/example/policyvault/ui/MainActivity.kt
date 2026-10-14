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
import java.io.File

sealed interface AppScreenState {
    data object Home : AppScreenState
    data class Processing(val statusMessage: String) : AppScreenState
    data class Confirm(
        val pageBitmaps: List<Bitmap>,
        val ocrBlocks: List<OcrBlock>,
        val draft: DraftPolicy,
        val pageLabels: List<String>
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
                val policies by database.policyDao().observeAllPolicies().collectAsState(initial = emptyList())

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
                            pageLabels = state.pageLabels,
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
     * 智能核心端侧流水线：
     * 1. 扫描 PDF 页面，检测文本中是否包含“投保人”，定位真实的保单首页（避开封面和无用说明页）
     * 2. 截取保单首页及后续明细页并渲染高清 Bitmap
     * 3. 运行端侧离线 ML Kit OCR
     * 4. 空间几何锚点提取保单 8 大核心字段
     */
    private fun startPdfImportPipeline(uri: Uri) {
        lifecycleScope.launch {
            try {
                screenState = AppScreenState.Processing("正在导入保单文档...")
                val (sandboxPdfFile, totalPages) = pdfProcessor.prepareSandboxPdf(uri)

                // 核心策略：寻找包含“投保人”的目标保单首页
                screenState = AppScreenState.Processing("正在定位保单核心凭证页（查找投保人信息）...")
                val maxScan = minOf(totalPages, 10)
                var targetPageIndex = -1
                val scannedBitmaps = mutableMapOf<Int, Bitmap>()
                val scannedPageBlocks = mutableMapOf<Int, List<OcrBlock>>()

                for (p in 0 until maxScan) {
                    screenState = AppScreenState.Processing("正在扫描第 ${p + 1} 页文字...")
                    val bmp = pdfProcessor.renderPage(sandboxPdfFile, p)
                    scannedBitmaps[p] = bmp
                    val blocks = ocrEngine.recognizePage(bmp, pageIndex = p, startId = p * 1000)
                    scannedPageBlocks[p] = blocks

                    // 关键要求：必须先找到“投保人”，才认为是保单首页
                    if (blocks.any { it.text.contains("投保人") }) {
                        targetPageIndex = p
                        break
                    }
                }

                // 若前 10 页未找到“投保人”，退化查找“被保险人”或“保单号”
                if (targetPageIndex == -1) {
                    for (p in 0 until maxScan) {
                        val blocks = scannedPageBlocks[p] ?: continue
                        if (blocks.any { it.text.contains("被保险人") || it.text.contains("被保人") || it.text.contains("保单号") || it.text.contains("合同编号") }) {
                            targetPageIndex = p
                            break
                        }
                    }
                }
                if (targetPageIndex == -1) {
                    targetPageIndex = 0
                }

                // 扫描或加载后续紧邻的一页（通常为险种与保费明细表）
                val nextPageIndex = targetPageIndex + 1
                if (nextPageIndex < totalPages && !scannedBitmaps.containsKey(nextPageIndex)) {
                    screenState = AppScreenState.Processing("正在解析保单明细页...")
                    val nextBmp = pdfProcessor.renderPage(sandboxPdfFile, nextPageIndex)
                    scannedBitmaps[nextPageIndex] = nextBmp
                    scannedPageBlocks[nextPageIndex] = ocrEngine.recognizePage(nextBmp, nextPageIndex, nextPageIndex * 1000)
                }

                // 确定确认页要展示的页面列表：保单首页排在第 1 位
                val selectedPdfIndices = mutableListOf<Int>()
                selectedPdfIndices.add(targetPageIndex)
                if (targetPageIndex + 1 < totalPages && !selectedPdfIndices.contains(targetPageIndex + 1)) {
                    selectedPdfIndices.add(targetPageIndex + 1)
                }
                // 如果保单首页不是原件第 1 页，把封面也放在末尾作为可选参考
                if (targetPageIndex > 0 && !selectedPdfIndices.contains(0) && scannedBitmaps.containsKey(0)) {
                    selectedPdfIndices.add(0)
                }

                val uiBitmaps = mutableListOf<Bitmap>()
                val uiImageFiles = mutableListOf<File>()
                val uiOcrBlocks = mutableListOf<OcrBlock>()
                val pageLabels = mutableListOf<String>()
                var blockCounter = 0

                selectedPdfIndices.forEachIndexed { uiIdx, originalIdx ->
                    val bmp = scannedBitmaps[originalIdx]!!
                    uiBitmaps.add(bmp)

                    val imgFile = File(sandboxPdfFile.parentFile, "page_ui_$uiIdx.jpg")
                    pdfProcessor.saveBitmap(bmp, imgFile)
                    uiImageFiles.add(imgFile)

                    val label = when {
                        originalIdx == targetPageIndex -> "保单首页 (P${originalIdx + 1})"
                        originalIdx == targetPageIndex + 1 -> "明细页 (P${originalIdx + 1})"
                        originalIdx == 0 -> "封面 (P1)"
                        else -> "P${originalIdx + 1}"
                    }
                    pageLabels.add(label)

                    val rawBlocks = scannedPageBlocks[originalIdx] ?: emptyList()
                    rawBlocks.forEach { b ->
                        uiOcrBlocks.add(
                            b.copy(
                                id = blockCounter++,
                                pageIndex = uiIdx
                            )
                        )
                    }
                }

                screenState = AppScreenState.Processing("正在提取保单 8 大核心字段...")
                val draft = ruleExtractor.extract(
                    blocks = uiOcrBlocks,
                    pdfSandboxPath = sandboxPdfFile.absolutePath,
                    previewImagePaths = uiImageFiles.map { it.absolutePath }
                )

                screenState = AppScreenState.Confirm(
                    pageBitmaps = uiBitmaps,
                    ocrBlocks = uiOcrBlocks,
                    draft = draft,
                    pageLabels = pageLabels
                )
            } catch (e: Exception) {
                screenState = AppScreenState.Home
                Toast.makeText(this@MainActivity, "保单解析失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

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
