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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmPolicyScreen(
    pageBitmaps: List<Bitmap>,
    ocrBlocks: List<OcrBlock>,
    initialDraft: DraftPolicy,
    pageLabels: List<String> = emptyList(),
    onCancel: () -> Unit,
    onConfirmSave: (DraftPolicy) -> Unit
) {
    var draft by remember { mutableStateOf(initialDraft) }
    var activeFieldKey by remember { mutableStateOf(PolicyFieldKey.APPLICANT) }
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
        // ==================== 顶部导航栏：页面快速切换与取消 ====================
        Surface(
            color = Color(0xFF0F172A),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState())
                ) {
                    pageBitmaps.indices.forEach { idx ->
                        val labelText = pageLabels.getOrNull(idx) ?: "第${idx + 1}页"
                        FilterChip(
                            selected = currentPageIndex == idx,
                            onClick = { currentPageIndex = idx },
                            label = { Text(labelText, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF38BDF8),
                                selectedLabelColor = Color(0xFF0F172A),
                                containerColor = Color(0xFF1E293B),
                                labelColor = Color(0xFF94A3B8)
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onCancel) {
                    Text("取消", color = Color(0xFF94A3B8), fontSize = 13.sp)
                }
            }
        }

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

            // 悬浮在原图下方的操作提示条
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "💡 当前核对【${activeFieldKey.label}】：可直接点击图上绿框一键填入",
                    color = Color(0xFFF1F5F9),
                    fontSize = 11.sp,
                    maxLines = 1
                )
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
