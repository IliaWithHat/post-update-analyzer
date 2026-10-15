package com.ilia.postupdateanalyzer.support.fake

/** Small real PNG icons, a different color per name so icon de-duplication can be tested. */
object TestPngs {
    fun png(seed: String): ByteArray {
        val image = java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val color = seed.hashCode() and 0xFFFFFF
        for (x in 0 until 16) for (y in 0 until 16) image.setRGB(x, y, color)
        return java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "png", it) }.toByteArray()
    }
}
