package com.offline.phonelink.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Android refuses to boot when a privileged app asks for a privileged permission that is not in its
 * allowlist file. These checks keep the manifest and both copies of the allowlist in step.
 */
class PrivappPermissionsTest {

    private val privileged = setOf(
        "android.permission.BLUETOOTH_PRIVILEGED",
        "android.permission.START_ACTIVITIES_FROM_BACKGROUND",
    )

    private fun permissionNames(file: File, tag: String): Set<String> =
        Regex("""<$tag\s+android:name="([^"]+)"|<$tag\s+name="([^"]+)"""")
            .findAll(file.readText())
            .map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            .toSet()

    @Test
    fun appAndMagiskModuleShipTheSameAllowlist() {
        val inApp = File("src/main/res/raw/privapp_permissions.xml").readText()
        val inModule = File("magisk/system/etc/permissions/privapp-permissions-com.offline.phonelink.xml").readText()
        assertEquals(inModule, inApp)
    }

    @Test
    fun allowlistCoversEveryPrivilegedPermissionInTheManifest() {
        val requested = permissionNames(File("src/main/AndroidManifest.xml"), "uses-permission")
        val allowed = permissionNames(File("src/main/res/raw/privapp_permissions.xml"), "permission")
        assertEquals(privileged, allowed)
        assertEquals(privileged, requested.filter { it in privileged || it.endsWith("_PRIVILEGED") }.toSet())
    }
}
