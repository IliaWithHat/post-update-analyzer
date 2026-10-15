package com.ilia.postupdateanalyzer.metadata.parser

import com.ilia.postupdateanalyzer.support.fake.TestPngs
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Base64

class HelperOutputParserTest {

    private fun line(json: String): String {
        return "PUA|" + Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
    }

    private fun icon(bytes: ByteArray): String {
        return Base64.getEncoder().encodeToString(bytes)
    }

    @Test
    fun `parses a complete output`() {
        val png = TestPngs.png("settings")
        val output = listOf(
            "PUA-BEGIN|2",
            line("""{"packageName":"com.android.settings","label":"Налаштування","versionCode":33,"versionName":"13","system":true,"updatedSystem":false,"firstInstallTime":1,"lastUpdateTime":2,"apkPath":"/system/priv-app/Settings/Settings.apk","icon":"${icon(png)}"}"""),
            line("""{"packageName":"com.example.game","label":"Game","versionCode":12345678901,"system":false}"""),
            "PUA-END",
        ).joinToString("\r\n")

        val parsed = HelperOutputParser.parse(output)

        assertThat(parsed.complete).isTrue()
        assertThat(parsed.announced).isEqualTo(2)
        assertThat(parsed.badLines).isZero()
        val settings = parsed.apps.getValue("com.android.settings")
        assertThat(settings.label).isEqualTo("Налаштування")
        assertThat(settings.versionCode).isEqualTo(33)
        assertThat(settings.system).isTrue()
        assertThat(settings.iconPng).isEqualTo(png)
        assertThat(parsed.apps.getValue("com.example.game").versionCode).isEqualTo(12345678901)
        assertThat(parsed.apps.getValue("com.example.game").iconPng).isNull()
    }

    @Test
    fun `ignores noise and counts broken lines`() {
        val output = listOf(
            "WARNING: linker: something",
            "PUA-BEGIN|3",
            "PUA|not-base64!!!",
            line("not json"),
            line("""{"label":"no package"}"""),
            line("""{"packageName":"bad name;reboot"}"""),
            line("""{"packageName":"com.ok.app"}"""),
        ).joinToString("\n")

        val parsed = HelperOutputParser.parse(output)

        assertThat(parsed.apps.keys).containsExactly("com.ok.app")
        assertThat(parsed.badLines).isEqualTo(4)
        assertThat(parsed.complete).isFalse()
    }

    @Test
    fun `wrong field types are ignored, not fatal`() {
        val parsed = HelperOutputParser.parse(line("""{"packageName":"com.ok.app","versionCode":"abc","system":"yes","label":42}"""))

        val app = parsed.apps.getValue("com.ok.app")
        assertThat(app.versionCode).isNull()
        assertThat(app.system).isNull()
        assertThat(app.label).isNull()
    }

    @Test
    fun `reports a fatal helper error`() {
        val parsed = HelperOutputParser.parse("PUA-FATAL|" + Base64.getEncoder().encodeToString("SecurityException".toByteArray()))

        assertThat(parsed.fatal).isEqualTo("SecurityException")
        assertThat(parsed.apps).isEmpty()
    }

    @Test
    fun `keeps per-app errors`() {
        val parsed = HelperOutputParser.parse(line("""{"packageName":"com.broken.app","error":"Resources not found"}"""))

        assertThat(parsed.apps.getValue("com.broken.app").error).isEqualTo("Resources not found")
    }

    @Test
    fun `accepts only real PNG icons of sane size`() {
        assertThat(HelperOutputParser.decodeIcon(icon(TestPngs.png("x")))).isNotNull()
        assertThat(HelperOutputParser.decodeIcon(icon("GIF89a....".toByteArray()))).isNull()
        assertThat(HelperOutputParser.decodeIcon("%%%")).isNull()
        assertThat(HelperOutputParser.decodeIcon(icon(TestPngs.png("x") + ByteArray(HelperOutputParser.MAX_ICON_BYTES)))).isNull()
    }

    @Test
    fun `long labels are cut`() {
        val parsed = HelperOutputParser.parse(line("""{"packageName":"com.ok.app","label":"${"x".repeat(1000)}"}"""))

        assertThat(parsed.apps.getValue("com.ok.app").label).hasSize(200)
    }
}
