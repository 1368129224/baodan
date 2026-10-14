package com.example.policyvault

import android.graphics.RectF
import com.example.policyvault.data.model.OcrBlock
import com.example.policyvault.domain.extractor.SpatialRuleExtractor
import org.junit.Assert.assertEquals
import org.junit.Test

class SpatialRuleExtractorTest {

    @Test
    fun testProductNameExtractionAndCorrection() {
        val extractor = SpatialRuleExtractor()
        val blocks = listOf(
            OcrBlock(
                id = 1,
                pageIndex = 0,
                text = "险种名称",
                normRect = RectF(0.1f, 0.2f, 0.2f, 0.22f)
            ),
            OcrBlock(
                id = 2,
                pageIndex = 0,
                text = "复星保德信星海贏家青版养老年金保险",
                normRect = RectF(0.1f, 0.23f, 0.4f, 0.25f)
            ),
            OcrBlock(
                id = 3,
                pageIndex = 0,
                text = "(分紅型)(计划一)",
                normRect = RectF(0.1f, 0.255f, 0.25f, 0.27f)
            )
        )

        val draft = extractor.extract(blocks, "", emptyList())
        assertEquals("复星保德信星海赢家青鸾版养老年金保险（分红型）（计划一）", draft.productName.value)
    }
}
