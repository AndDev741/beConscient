package org.beconscient.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Checks the merged manifest (with library contributions) and the extraction
 * rules: no backup, no transfer, no exported receivers, system-bound services
 * guarded by their system permissions, nothing direct-boot aware.
 */
class ManifestTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun mergedManifest(): File {
        val props = Properties()
        javaClass.classLoader!!.getResourceAsStream("com/android/tools/test_config.properties")?.use { props.load(it) }
        props.getProperty("android_merged_manifest")?.let { File(it) }?.takeIf { it.isFile }?.let { return it }
        return File("build/intermediates").walkTopDown()
            .filter { it.name == "AndroidManifest.xml" && it.path.contains("merged_manifest") && it.path.contains("debug", ignoreCase = true) }
            .firstOrNull() ?: error("merged manifest not found")
    }

    private fun parse(file: File) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(file).documentElement

    private fun Element.attr(name: String): String? = getAttributeNS(androidNs, name).takeIf { hasAttributeNS(androidNs, name) }

    private fun Element.children(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test
    fun `backup is off and extraction rules are set`() {
        val app = parse(mergedManifest()).children("application").single()
        assertEquals("false", app.attr("allowBackup"))
        assertTrue(app.attr("dataExtractionRules")!!.isNotEmpty())
        assertTrue(app.attr("fullBackupContent")!!.isNotEmpty())
        assertTrue(app.attr("networkSecurityConfig")!!.isNotEmpty())
    }

    @Test
    fun `receivers are not exported`() {
        val receivers = parse(mergedManifest()).children("receiver")
        assertTrue(receivers.isNotEmpty())
        for (r in receivers) assertEquals("${r.attr("name")} must not be exported", "false", r.attr("exported"))
        val names = receivers.map { it.attr("name") }
        assertFalse("androidx.work.impl.diagnostics.DiagnosticsReceiver" in names)
        assertFalse("androidx.profileinstaller.ProfileInstallReceiver" in names)
    }

    @Test
    fun `exported services are bound only by the system`() {
        val allowed = mapOf(
            "org.beconscient.collector.notifications.NotificationListener" to "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
            "org.beconscient.collector.shorts.ShortsAccessibilityService" to "android.permission.BIND_ACCESSIBILITY_SERVICE",
            "androidx.work.impl.background.systemjob.SystemJobService" to "android.permission.BIND_JOB_SERVICE",
        )
        val services = parse(mergedManifest()).children("service")
        for (s in services) {
            val name = s.attr("name")!!
            if (s.attr("exported") == "true") {
                assertEquals("$name is exported", allowed[name], s.attr("permission"))
            }
        }
        val ours = services.filter { it.attr("name")!!.startsWith("org.beconscient") }.associate { it.attr("name") to it.attr("permission") }
        assertEquals(allowed.filterKeys { it.startsWith("org.beconscient") }, ours)
        // Providers from libraries (androidx.startup) must not be exported either.
        parse(mergedManifest()).children("provider").forEach { assertEquals("false", it.attr("exported")) }
    }

    @Test
    fun `nothing runs before the first unlock`() {
        val root = parse(mergedManifest())
        for (tag in listOf("application", "activity", "service", "receiver", "provider")) {
            for (e in root.children(tag)) assertFalse("${e.attr("name")} is direct boot aware", e.attr("directBootAware") == "true")
        }
    }

    @Test
    fun `extraction rules exclude every domain from cloud backup and device transfer`() {
        val rules = parse(File("src/main/res/xml/data_extraction_rules.xml"))
        val domains = setOf(
            "root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref",
        )
        for (section in listOf("cloud-backup", "device-transfer")) {
            val el = rules.children(section).single()
            assertTrue("$section must not include anything", el.children("include").isEmpty())
            val excluded = el.children("exclude").filter { it.getAttribute("path") == "." }.map { it.getAttribute("domain") }.toSet()
            assertEquals(section, domains, excluded)
        }
        // Android 10 and 11 read the older format.
        val legacy = parse(File("src/main/res/xml/backup_rules.xml"))
        assertTrue(legacy.children("include").isEmpty())
        assertEquals(domains, legacy.children("exclude").filter { it.getAttribute("path") == "." }.map { it.getAttribute("domain") }.toSet())
    }

    @Test
    fun `the Shorts service watches only YouTube and reads view ids`() {
        val xml = parse(File("src/main/res/xml/shorts_accessibility.xml"))
        assertEquals("com.google.android.youtube", xml.attr("packageNames"))
        assertTrue(xml.attr("accessibilityFlags")!!.contains("flagReportViewIds"))
    }

    @Test
    fun `release forbids cleartext and only debug allows it`() {
        val release = parse(File("src/main/res/xml/network_security_config.xml"))
        assertEquals("false", release.children("base-config").single().getAttribute("cleartextTrafficPermitted"))
        val debug = parse(File("src/debug/res/xml/network_security_config.xml"))
        assertEquals("true", debug.children("base-config").single().getAttribute("cleartextTrafficPermitted"))
    }
}
