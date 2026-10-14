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
