package com.example.policyvault.domain.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import com.example.policyvault.data.model.OcrBlock
import com.example.policyvault.domain.util.ChineseConverter
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OcrEngineWrapper {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /**
     * 对单张 Bitmap 执行端侧离线 OCR 识别
     */
    suspend fun recognizePage(bitmap: Bitmap, pageIndex: Int, startId: Int): List<OcrBlock> = withContext(Dispatchers.Default) {
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        if (width <= 0f || height <= 0f) return@withContext emptyList()

        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val visionText = suspendCancellableCoroutine { continuation ->
            recognizer.process(inputImage)
                .addOnSuccessListener { result ->
                    if (continuation.isActive) continuation.resume(result)
                }
                .addOnFailureListener { exception ->
                    if (continuation.isActive) continuation.resumeWithException(exception)
                }
        }

        val blocks = mutableListOf<OcrBlock>()
        var currentId = startId

        fun toNormRect(box: Rect?): RectF {
            return if (box != null) {
                val l = (box.left.toFloat() / width).coerceIn(0f, 1f)
                val t = (box.top.toFloat() / height).coerceIn(0f, 1f)
                val r = (box.right.toFloat() / width).coerceIn(0f, 1f)
                val b = (box.bottom.toFloat() / height).coerceIn(0f, 1f)
                RectF(l, t, maxOf(l + 0.005f, r), maxOf(t + 0.005f, b))
            } else {
                RectF(0f, 0f, 1f, 1f)
            }
        }

        for (textBlock in visionText.textBlocks) {
            for (line in textBlock.lines) {
                val rawLineText = line.text.trim()
                if (rawLineText.isEmpty()) continue
                // 全局繁体转简体（修复 ML Kit 常输出的“贏”、“紅”、“費”、“險”等繁体字）
                val lineText = ChineseConverter.toSimplified(rawLineText)

                // 如果该行包含多个被空格或列分隔的元素（例如表格同行“投保人   张明远”）：
                // 同时保留各元素独立块（便于高亮框点选与空间邻近对齐）与完整行文本
                if (line.elements.size > 1) {
                    // 加入整行（便于直接在行内做正则匹配）
                    blocks.add(
                        OcrBlock(
                            id = currentId++,
                            pageIndex = pageIndex,
                            text = lineText,
                            normRect = toNormRect(line.boundingBox),
                            confidence = line.confidence
                        )
                    )
                    // 加入细粒度元素块（便于用户在分屏画布精准点选）
                    for (element in line.elements) {
                        val rawElText = element.text.trim()
                        val elText = ChineseConverter.toSimplified(rawElText)
                        if (elText.isNotEmpty() && elText != ":" && elText != "：" && elText != lineText) {
                            blocks.add(
                                OcrBlock(
                                    id = currentId++,
                                    pageIndex = pageIndex,
                                    text = elText,
                                    normRect = toNormRect(element.boundingBox),
                                    confidence = element.confidence
                                )
                            )
                        }
                    }
                } else {
                    blocks.add(
                        OcrBlock(
                            id = currentId++,
                            pageIndex = pageIndex,
                            text = lineText,
                            normRect = toNormRect(line.boundingBox),
                            confidence = line.confidence
                        )
                    )
                }
            }
        }

        blocks
    }
}
