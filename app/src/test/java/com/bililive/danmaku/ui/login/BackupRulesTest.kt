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
    fun `excluded preferences match login storage filename`() {
        val source = sourceFile("java/com/bililive/danmaku/data/remote/BiliBiliLoginManager.kt").readText()
        assertTrue(source.contains("private const val PREF_NAME = \"bili_login\""))
        assertTrue(source.contains("getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)"))
    }

    private fun assertSettingsOnly(section: Element) {
        val includes = section.getElementsByTagName("include")
        assertEquals(1, includes.length)
        val settings = includes.item(0) as Element
        assertEquals("file", settings.getAttribute("domain"))
        assertEquals("datastore/settings.preferences_pb", settings.getAttribute("path"))

        val excludes = section.getElementsByTagName("exclude")
        assertTrue((0 until excludes.length).any { index ->
            val entry = excludes.item(index) as Element
            entry.getAttribute("domain") == "sharedpref" &&
                entry.getAttribute("path") == "bili_login.xml"
        })
    }

    private fun parse(path: String) = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder().parse(sourceFile(path))

    private fun sourceFile(path: String): File {
        // Android Gradle runs tests from app/, while standalone JVM checks use the repo root.
        return listOf(File("src/main/$path"), File("app/src/main/$path"))
            .first { it.isFile }
    }
}
