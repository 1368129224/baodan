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
