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
        const val TARGET_LONG_EDGE = 2400f   // 高清分辨率：长边提升至 2400px，确保复杂汉字（如“鸾”、“赢”）笔画清晰可辨
    }

    /**
     * 将外部 PDF 拷贝入独立私有沙盒目录
     * 返回沙盒 PDF 文件与总页数
     */
    suspend fun prepareSandboxPdf(uri: Uri): Pair<File, Int> = withContext(Dispatchers.IO) {
        val policyDir = File(context.filesDir, "policies/${UUID.randomUUID()}").apply { mkdirs() }
        val targetPdfFile = File(policyDir, "origin.pdf")

        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(targetPdfFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalArgumentException("无法读取该 PDF 文件流")

        val totalPages = ParcelFileDescriptor.open(targetPdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                renderer.pageCount
            }
        }

        Pair(targetPdfFile, totalPages)
    }

    /**
     * 渲染 PDF 的指定单页为高清 Bitmap（纯白色背景防透明黑底）
     */
    suspend fun renderPage(pdfFile: File, pageIndex: Int): Bitmap = withContext(Dispatchers.IO) {
        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                require(pageIndex in 0 until renderer.pageCount) {
                    "请求渲染的页码 $pageIndex 超出总页数 ${renderer.pageCount}"
                }
                renderer.openPage(pageIndex).use { page ->
                    val scale = TARGET_LONG_EDGE / max(page.width, page.height).toFloat()
                    val bmpWidth = (page.width * scale).roundToInt()
                    val bmpHeight = (page.height * scale).roundToInt()

                    val bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE) // 显式纯白背景

                    page.render(
                        bitmap,
                        null,
                        null,
                        PdfRenderer.Page.RENDER_MODE_FOR_PRINT
                    )
                    bitmap
                }
            }
        }
    }

    /**
     * 保存单张 Bitmap 至本地 JPEG 文件
     */
    suspend fun saveBitmap(bitmap: Bitmap, targetFile: File) = withContext(Dispatchers.IO) {
        targetFile.parentFile?.mkdirs()
        FileOutputStream(targetFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
    }
}
