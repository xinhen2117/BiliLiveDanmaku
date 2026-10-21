package com.bililive.danmaku.ui.login

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Source-level regression checks, not a device backup/restore integration test. */
class BackupRulesTest {
    @Test
    fun `manifest uses both generations of backup rules`() {
        val application = parse("AndroidManifest.xml")
            .getElementsByTagName("application").item(0) as Element
        assertEquals("@xml/backup_rules", application.getAttribute("android:fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", application.getAttribute("android:dataExtractionRules"))
    }

    @Test
    fun `legacy backup allows only settings and excludes login preferences`() {
        assertSettingsOnly(parse("res/xml/backup_rules.xml").documentElement)
    }

    @Test
    fun `cloud backup and device transfer each exclude credentials`() {
        val document = parse("res/xml/data_extraction_rules.xml")
        for (mode in listOf("cloud-backup", "device-transfer")) {
            val sections = document.getElementsByTagName(mode)
            assertEquals("Missing or duplicate $mode rules", 1, sections.length)
            assertSettingsOnly(sections.item(0) as Element)
        }
    }

    @Test
    fun `login preferences stay outside every backup allowlist`() {
        val source = sourceFile("java/com/bililive/danmaku/data/remote/BiliBiliLoginManager.kt").readText()
        assertTrue(source.contains("private const val PREF_NAME = \"bili_login\""))
        assertTrue(source.contains("getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)"))
        // The sole include is in the file domain, never the sharedpref login storage domain.
        for (path in listOf("res/xml/backup_rules.xml", "res/xml/data_extraction_rules.xml")) {
            val includes = parse(path).getElementsByTagName("include")
            for (index in 0 until includes.length) {
                val entry = includes.item(index) as Element
                assertTrue(entry.getAttribute("domain") != "sharedpref")
                assertTrue(!entry.getAttribute("path").contains("bili_login"))
            }
        }
    }

    private fun assertSettingsOnly(section: Element) {
        val includes = section.getElementsByTagName("include")
        assertEquals(1, includes.length)
        val settings = includes.item(0) as Element
        assertEquals("file", settings.getAttribute("domain"))
        assertEquals("datastore/settings.preferences_pb", settings.getAttribute("path"))

        // An include makes this an allowlist: all other files/domains are excluded by default.
        // Out-of-allowlist excludes are redundant and rejected by Android's FullBackupContent lint.
        assertEquals(0, section.getElementsByTagName("exclude").length)
    }

    private fun parse(path: String) = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder().parse(sourceFile(path))

    private fun sourceFile(path: String): File {
        // Android Gradle runs tests from app/, while standalone JVM checks use the repo root.
        return listOf(File("src/main/$path"), File("app/src/main/$path"))
            .first { it.isFile }
    }
}
