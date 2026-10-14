package com.example.policyvault.domain.extractor

import com.example.policyvault.data.model.*
import kotlin.math.abs
import kotlin.math.max

class SpatialRuleExtractor {

    // 内置国内主流保司简称词库（包括复星保德信、平安、国寿、太保等）
    private val insurerDict = listOf(
        "复星保德信", "复星联合健康", "复星联合", "保德信",
        "平安人寿", "平安财险", "平安养老", "平安健康", "中国平安",
        "中国人寿", "太平洋人寿", "太平洋产险", "中国太保", "太保寿险",
        "友邦人寿", "友邦保险", "泰康人寿", "泰康在线", "泰康养老",
        "新华人寿", "新华保险",
        "中国人民人寿", "中国人保", "人保寿险", "人保健康", "人保财险",
        "众安在线", "众安保险", "阳光人寿", "阳光财险", "阳光保险",
        "前海人寿", "同方全球", "同方全球人寿",
        "中意人寿", "工银安盛", "工银安盛人寿", "建信人寿", "招商信诺",
        "百年人寿", "信泰人寿", "弘康人寿", "瑞华健康", "大都会人寿", "中美联泰",
        "民生人寿", "华夏人寿", "富德生命人寿", "富德生命", "生命人寿",
        "中邮人寿", "农银人寿", "交银人寿", "长城人寿", "君康人寿",
        "华泰人寿", "长生人寿", "中德安联", "汇丰人寿", "安联人寿",
        "陆家嘴国泰", "中荷人寿", "北大方正人寿", "光大永明", "海保人寿",
        "爱心人寿", "横琴人寿", "鼎诚人寿", "国富人寿", "三峡人寿", "北京人寿",
        "太平人寿", "太平财险", "中国太平", "利安人寿", "渤海人寿", "天安人寿",
        "德华安顾", "中英人寿", "恒大人寿", "中融人寿"
    )

    // 正则表达式库
    private val policyNoRegex = Regex("""[A-Za-z0-9\-]{6,30}""")
    private val dateRegex = Regex("""(20\d{2})\s*[-/年.]\s*(0?[1-9]|1[0-2])\s*[-/月.]\s*(0?[1-9]|[12]\d|3[01])""")
    private val moneyRegex = Regex("""([1-9]\d{0,2}(?:,\d{3})*(?:\.\d{1,2})?|[1-9]\d*(?:\.\d{1,2})?)\s*(万元|万|元|RMB|￥)?""")
    private val chineseNameRegex = Regex("""^[\u4e00-\u9fa5·]{2,4}$""")

    fun extract(
        blocks: List<OcrBlock>,
        pdfSandboxPath: String,
        previewImagePaths: List<String>
    ): DraftPolicy {
        val insurer = extractInsurer(blocks)
        val policyNo = extractPolicyNo(blocks)
        val applicant = extractPersonName(blocks, listOf("投保人姓名", "投保人", "投保人员", "投保客户", "客户姓名"))
        var insured = extractPersonName(blocks, listOf("被保险人姓名", "被保险人", "被保人姓名", "被保人", "主被保险人", "第一被保险人"))

        // 如果被保险人识别为“本人”、“同投保人”或“同上”，自动继承投保人姓名
        if (insured.value in listOf("本人", "同投保人", "同上", "投保人本人", "是本人") && applicant.value.isNotEmpty()) {
            insured = insured.copy(value = applicant.value, level = ConfidenceLevel.HIGH)
        } else if (insured.value.isEmpty() && applicant.value.isNotEmpty()) {
            val hasSelfRelation = blocks.any { it.text.contains("本人") && (it.text.contains("关系") || it.text.contains("投保")) }
            if (hasSelfRelation) {
                insured = applicant.copy(level = ConfidenceLevel.MEDIUM)
            }
        }

        val productName = extractProductName(blocks)
        val sumInsured = extractAmount(blocks, listOf("基本保险金额", "基本保额", "保险金额", "保额", "保障金额", "有效保额", "基准保额"), isSumInsured = true)
        val effectiveDate = extractEffectiveDate(blocks)
        val annualPremium = extractAmount(blocks, listOf("保费合计", "保险费合计", "年交保费", "年缴保费", "首期保费", "期交保费", "期缴保费", "合计保费", "首期保险费", "实交保费", "保险费", "保费"), isSumInsured = false)

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
        for (brand in insurerDict) {
            val matched = blocks.firstOrNull { it.text.contains(brand) }
            if (matched != null) {
                return ExtractedField(brand, ConfidenceLevel.HIGH, matched.id, matched.pageIndex)
            }
        }
        val fallback = blocks.firstOrNull { block ->
            val t = block.text
            (t.contains("人寿") || t.contains("保险") || t.contains("健康险") || t.contains("养老险")) &&
                    (t.contains("公司") || t.contains("股份") || t.contains("分公司")) &&
                    !t.contains("条款") && !t.contains("合同") && !t.contains("释义")
        }
        return if (fallback != null) {
            val cleaned = fallback.text.replace(Regex("""^[^一-龥]+"""), "").take(16)
            ExtractedField(cleaned, ConfidenceLevel.MEDIUM, fallback.id, fallback.pageIndex)
        } else {
            ExtractedField()
        }
    }

    private fun extractPolicyNo(blocks: List<OcrBlock>): ExtractedField {
        val anchors = listOf("保险单号", "保单编号", "保单号码", "合同编号", "合同号码", "保险合同编号", "保单号", "合同号")
        for (block in blocks) {
            val matchedAnchor = anchors.firstOrNull { block.text.contains(it) } ?: continue
            
            // 1. 同行冒号或空格后提取
            val textAfter = block.text.substringAfter(matchedAnchor)
            val inlineMatch = policyNoRegex.find(textAfter)
            if (inlineMatch != null && inlineMatch.value.count { it.isDigit() } >= 5) {
                return ExtractedField(inlineMatch.value, ConfidenceLevel.HIGH, block.id, block.pageIndex)
            }

            // 2. 空间邻近提取（同行右侧或下方）
            val neighbors = findSpatialNeighbors(block, blocks)
            for (cand in neighbors) {
                val m = policyNoRegex.find(cand.text)
                if (m != null && m.value.count { it.isDigit() } >= 5) {
                    return ExtractedField(m.value, ConfidenceLevel.HIGH, cand.id, cand.pageIndex)
                }
            }
        }

        // 3. Fallback: 查找以 NO. 或 No. 开头的特征编码
        val noMatch = blocks.firstNotNullOfOrNull { b ->
            Regex("""(?:NO|No|合同号|保单号)[.：:\s]*([A-Za-z0-9\-]{8,25})""").find(b.text)?.let { res ->
                ExtractedField(res.groupValues[1], ConfidenceLevel.MEDIUM, b.id, b.pageIndex)
            }
        }
        return noMatch ?: ExtractedField()
    }

    private fun extractPersonName(blocks: List<OcrBlock>, anchors: List<String>): ExtractedField {
        val noiseWords = setOf(
            "姓名", "性别", "男", "女", "年龄", "出生日期", "证件号码", "身份证", "证件类型",
            "国籍", "联系电话", "手机", "地址", "投保人", "被保险人", "关系", "本人", "法定", "同投保人"
        )
        for (block in blocks) {
            val matchedAnchor = anchors.firstOrNull { block.text.contains(it) } ?: continue
            
            // 1. 同行内提取
            val afterAnchor = block.text.substringAfter(matchedAnchor).trim()
            val cleanedInline = afterAnchor.replace(Regex("""^[:：\s]+"""), "").trim()
            val inlineNameMatch = Regex("""^[\u4e00-\u9fa5·]{2,4}""").find(cleanedInline)
            if (inlineNameMatch != null && inlineNameMatch.value !in noiseWords) {
                return ExtractedField(inlineNameMatch.value, ConfidenceLevel.HIGH, block.id, block.pageIndex)
            }

            // 2. 空间邻近检索（右侧邻居、下方单元格）
            val neighbors = findSpatialNeighbors(block, blocks)
            for (cand in neighbors) {
                val cleanedCand = cand.text.replace(Regex("""^[:：\s]+"""), "").trim()
                val candNameMatch = Regex("""^[\u4e00-\u9fa5·]{2,4}""").find(cleanedCand)
                if (candNameMatch != null && candNameMatch.value !in noiseWords) {
                    return ExtractedField(candNameMatch.value, ConfidenceLevel.HIGH, cand.id, cand.pageIndex)
                }
            }
        }
        return ExtractedField()
    }

    private fun extractProductName(blocks: List<OcrBlock>): ExtractedField {
        val anchors = listOf("险种名称", "主险名称", "主险合同", "保险险种", "产品名称", "保险名称", "主险", "险种")
        for (block in blocks) {
            val matchedAnchor = anchors.firstOrNull { block.text.contains(it) } ?: continue
            
            // 1. 同行内提取
            val after = block.text.substringAfter(matchedAnchor).replace(Regex("""^[:：\s]+"""), "").trim()
            if (after.length in 4..35 && isLikelyProductName(after)) {
                return ExtractedField(after, ConfidenceLevel.HIGH, block.id, block.pageIndex)
            }

            // 2. 空间邻居提取（表格下行或右列）
            val neighbors = findSpatialNeighbors(block, blocks)
            val target = neighbors.firstOrNull { cand ->
                cand.text.length in 4..35 && isLikelyProductName(cand.text)
            }
            if (target != null) {
                return ExtractedField(target.text.trim(), ConfidenceLevel.HIGH, target.id, target.pageIndex)
            }
        }

        // 3. 全局检索首张保单页中最像险种名称的文本（排除公司名、合同总称与条款字样）
        val globalMatch = blocks.filter { it.pageIndex == 0 }.firstOrNull { b ->
            b.text.length in 5..35 && isLikelyProductName(b.text) &&
                    !b.text.contains("公司") && !b.text.contains("条款") &&
                    !b.text.contains("全国") && !b.text.contains("客户")
        }
        return if (globalMatch != null) {
            ExtractedField(globalMatch.text.trim(), ConfidenceLevel.MEDIUM, globalMatch.id, globalMatch.pageIndex)
        } else {
            ExtractedField()
        }
    }

    private fun isLikelyProductName(text: String): Boolean {
        val keywords = listOf("两全", "寿险", "年金", "重疾", "医疗", "意外", "终身", "护理", "疾病", "健康", "保险")
        return keywords.any { text.contains(it) } &&
                !text.contains("保险公司") &&
                !text.contains("保险合同") &&
                !text.contains("责任免除") &&
                !text.contains("条款")
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
                if (!isSumInsured && (num <= 0 || num > 2_000_000)) continue

                val finalAmount = if (unit.contains("万")) num * 10000.0 else num
                val formatted = if (finalAmount % 1.0 == 0.0) finalAmount.toLong().toString() else "%.2f".format(finalAmount)
                return ExtractedField(formatted, ConfidenceLevel.HIGH, cand.id, cand.pageIndex)
            }
        }
        return ExtractedField()
    }

    private fun extractEffectiveDate(blocks: List<OcrBlock>): ExtractedField {
        val anchors = listOf("生效日期", "合同生效日", "生效日", "保险期间自", "保险合同生效日", "签单日期", "起保日期", "生效时间")
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
        val fallbackDate = blocks.firstNotNullOfOrNull { b ->
            dateRegex.find(b.text)?.let { m ->
                val y = m.groupValues[1]
                val month = m.groupValues[2].padStart(2, '0')
                val d = m.groupValues[3].padStart(2, '0')
                ExtractedField("$y-$month-$d", ConfidenceLevel.MEDIUM, b.id, b.pageIndex)
            }
        }
        return fallbackDate ?: ExtractedField()
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

                // 同行右侧邻居
                val isRightNeighbor = dxRight >= -0.02f && dxRight <= 0.55f && dyCenter <= 0.8f * hKey

                // 垂直下方邻居（表格列对齐）
                val dyBottom = rCand.top - rKey.bottom
                val dxCenter = abs(rCand.centerX() - rKey.centerX())
                val isBottomNeighbor = dyBottom >= -0.01f && dyBottom <= 3.0f * hKey && dxCenter <= max(wKey, 0.20f)

                when {
                    isRightNeighbor -> cand to (dxRight + 2.5f * dyCenter)
                    isBottomNeighbor -> cand to (dyBottom + 1.8f * dxCenter + 0.1f)
                    else -> null
                }
            }
            .sortedBy { it.second }
            .map { it.first }
    }
}
