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
