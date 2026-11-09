package com.meenai.mina

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**List of configurations that will cause the process to crash as soon as it starts,Block it on the computer first(The kind that only real machines will reveal). */
class ManifestProvidersTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    @Test
    fun fileProvidersGrantUriPermissionsAndStayPrivate() {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("manifest not found from ${File(".").absolutePath}", manifest.exists())
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest)
        val providers = doc.getElementsByTagName("provider")
        var fileProviders = 0
        for (i in 0 until providers.length) {
            val p = providers.item(i) as Element
            val metas = p.getElementsByTagName("meta-data")
            val isFileProvider = (0 until metas.length).any {
                (metas.item(it) as Element).getAttributeNS(androidNs, "name") == "android.support.FILE_PROVIDER_PATHS"
            }
            if (!isFileProvider) continue
            fileProviders++
            val name = p.getAttributeNS(androidNs, "name")
            // FileProvider.attachInfoexist grantUriPermissionsThrows when not true SecurityException,The process cannot start
            assertEquals("$name grantUriPermissions", "true", p.getAttributeNS(androidNs, "grantUriPermissions"))
            assertEquals("$name exported", "false", p.getAttributeNS(androidNs, "exported"))
        }
        assertTrue("expected at least one FileProvider", fileProviders > 0)
    }

    @Test
    fun helperHandoffProviderIsOnlyReachableWithShellLevelPermission() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val providers = doc.getElementsByTagName("provider")
        val p = (0 until providers.length).map { providers.item(it) as Element }
            .single { it.getAttributeNS(androidNs, "name") == ".shell.HelperHandoffProvider" }
        //The shell must be able to adjust it;Ordinary apps do not have this permission,Can’t even get the provider
        assertEquals("true", p.getAttributeNS(androidNs, "exported"))
        assertEquals("android.permission.INTERACT_ACROSS_USERS_FULL", p.getAttributeNS(androidNs, "permission"))
        assertEquals("\${applicationId}.helper", p.getAttributeNS(androidNs, "authorities"))
    }

    /**Computer connection services are open to the public only to allow adbshell to start it:Ordinary apps do not have this permission,Can't turn on or off the USB mode. */
    @Test
    fun computerAccessServiceIsOnlyStartableWithShellLevelPermission() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val services = doc.getElementsByTagName("service")
        val s = (0 until services.length).map { services.item(it) as Element }
            .single { it.getAttributeNS(androidNs, "name") == ".mcp.McpService" }
        assertEquals("true", s.getAttributeNS(androidNs, "exported"))
        assertEquals("android.permission.INTERACT_ACROSS_USERS_FULL", s.getAttributeNS(androidNs, "permission"))
        assertEquals("specialUse", s.getAttributeNS(androidNs, "foregroundServiceType"))
        //No intent-filter:Can only be started explicitly by component name
        assertEquals(0, s.getElementsByTagName("intent-filter").length)
    }

    /**All components registered in the list can be found by class by full name.(The entrance where the code was moved remains a thin shell with the same name.). */
    @Test
    fun everyComponentClassExists() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val names = listOf("application", "activity", "service", "receiver", "provider").flatMap { tag ->
            val nodes = doc.getElementsByTagName(tag)
            (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(androidNs, "name") }
        }
        assertTrue(names.size > 5)
        for (n in names) {
            val full = if (n.startsWith(".")) "com.meenai.mina$n" else n
            assertTrue("$full is not a class", runCatching { Class.forName(full, false, javaClass.classLoader) }.isSuccess)
        }
    }

    /**
     *The pages and services in the test APK list run in the test package's own process.,There is no Kotlin standard library there(Dependencies that overlap with the App are not included in the test APK):
     *Each must have a Java source file with the same name. The class name does not match or is written as Kotlin,The real phone crashes as soon as I turn it on.,Screen reading only reads a blank screen.
     */
    @Test
    fun testApkComponentsAreJavaClassesWithMatchingNames() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/androidTest/AndroidManifest.xml"))
        val names = listOf("activity", "service", "receiver", "provider").flatMap { tag ->
            val nodes = doc.getElementsByTagName(tag)
            (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(androidNs, "name") }
        }
        assertTrue(names.isNotEmpty())
        for (n in names) {
            val path = "src/androidTest/java/${n.replace('.', '/')}"
            assertTrue("$n has no Java source at $path.java", File("$path.java").isFile)
        }
    }

    /**
     *Photo accessories for walking system cameras:Declares use of camera, but not required(Devices without cameras are installed as usual). This statement cannot be deleted:
     *Some systems classify applications according to APK characteristics.,Without it Mina may be treated as a game, limiting the refresh rate.
     */
    @Test
    fun cameraIsDeclaredAsOptionalFeature() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val features = doc.getElementsByTagName("uses-feature")
        val camera = (0 until features.length).map { features.item(it) as Element }
            .singleOrNull { it.getAttributeNS(androidNs, "name") == "android.hardware.camera" }
        assertTrue("uses-feature android.hardware.camera is missing", camera != null)
        assertEquals("false", camera!!.getAttributeNS(androidNs, "required"))
    }

    /**
     *The test page for compatibility self-test is open to the public only for the built-in assistant(shell)drive it:The caller must hold INTERACT_ACROSS_USERS_FULL,Ordinary apps cannot be opened;
     *No intent-filter,Can only be opened by component name;A single task, do not enter the latest task. The class name recognized by the assistant is consistent with the list.
     */
    @Test
    fun selfCheckTargetIsOnlyStartableWithShellLevelPermission() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val activities = doc.getElementsByTagName("activity")
        val a = (0 until activities.length).map { activities.item(it) as Element }
            .single { it.getAttributeNS(androidNs, "name") == ".selfcheck.SelfCheckTargetActivity" }
        assertEquals("true", a.getAttributeNS(androidNs, "exported"))
        assertEquals("android.permission.INTERACT_ACROSS_USERS_FULL", a.getAttributeNS(androidNs, "permission"))
        assertEquals(0, a.getElementsByTagName("intent-filter").length)
        assertEquals(com.meenai.mina.cap.phone.shell.SystemIntents.OWN_PAGE_CLASS, "com.meenai.mina" + a.getAttributeNS(androidNs, "name"))
        assertEquals("true", a.getAttributeNS(androidNs, "excludeFromRecents"))
        assertTrue(a.hasAttributeNS(androidNs, "taskAffinity"))
        assertEquals("", a.getAttributeNS(androidNs, "taskAffinity"))
    }

    /**Built-in helper processes are started with this full name. */
    @Test
    fun helperEntryClassExists() {
        val c = Class.forName(com.meenai.mina.core.shell.HelperHandoff.HELPER_CLASS, false, javaClass.classLoader)
        assertTrue(c.methods.any { it.name == "main" && java.lang.reflect.Modifier.isStatic(it.modifiers) })
    }
}
