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
