package com.example.policyvault

import com.example.policyvault.domain.util.ChineseConverter
import org.junit.Assert.assertEquals
import org.junit.Test

class ChineseConverterTest {

    @Test
    fun testTraditionalToSimplified() {
        val input = "复星保德信星海贏家青版养老年金保险(分紅型)(计划一)"
        val output = ChineseConverter.toSimplified(input)
        assertEquals("复星保德信星海赢家青版养老年金保险(分红型)(计划一)", output)
    }
}
