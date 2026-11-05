package com.shunp.vitmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmazonPanelTest {
    @Test fun asinFromProductUrls() {
        assertEquals("B07WXL5YPW", AmazonPanel.asinFromUrl("https://www.amazon.co.jp/dp/B07WXL5YPW?ref=x"))
        assertEquals("B07WXL5YPW", AmazonPanel.asinFromUrl("amazon.co.jp/Nintendo-Switch/dp/B07WXL5YPW/ref=sr_1_1"))
        assertEquals("4065316936", AmazonPanel.asinFromUrl("https://www.amazon.co.jp/gp/product/4065316936"))
        assertEquals("B07WXL5YPW", AmazonPanel.asinFromUrl("https://www.amazon.co.jp/gp/aw/d/B07WXL5YPW"))
    }

    @Test fun nonProductPagesHaveNoAsin() {
        assertNull(AmazonPanel.asinFromUrl("https://www.amazon.co.jp/s?k=switch"))
        assertNull(AmazonPanel.asinFromUrl("amazon.co.jp"))
        assertNull(AmazonPanel.asinFromUrl("https://www.amazon.com/dp/B07WXL5YPW"))
    }

    @Test fun links() {
        assertEquals("https://sakura-checker.jp/search/B07WXL5YPW/", AmazonPanel.sakuraUrl("B07WXL5YPW"))
        assertEquals("https://keepa.com/#!product/5-B07WXL5YPW", AmazonPanel.keepaUrl("B07WXL5YPW"))
    }
}
