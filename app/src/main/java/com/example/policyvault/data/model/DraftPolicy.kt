package com.example.policyvault.data.model

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
