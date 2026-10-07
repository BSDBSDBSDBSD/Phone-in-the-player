package com.offline.phonelink.bt

import android.content.Context
import com.offline.phonelink.R
import java.io.File

/**
 * Installs the app into the system partition by itself (for players whose /system is writable),
 * doing what the Magisk module does: the APK in /system/priv-app, the privileged-permission
 * allowlist in /system/etc/permissions and the Bluetooth profile switches in /system/build.prop.
 * build.prop is backed up first and is only ever appended to (never rewritten), so restoring the
 * backup undoes it exactly.
 */
object SystemInstaller {

    const val APP_DIR = "/system/priv-app/PhoneLink"
    const val PERMISSIONS_FILE = "/system/etc/permissions/privapp-permissions-com.offline.phonelink.xml"
    const val BACKUP_DIR = "/sdcard/PhoneLink-backup"
    private const val MAGISK_MODULE = "/data/adb/modules/phonelink"

    /** Exit codes the scripts use to say why they stopped. */
    const val EXIT_NOT_WRITABLE = 3
    const val EXIT_MAGISK_MODULE = 4
    const val EXIT_NOT_INSTALLED = 5

    fun install(context: Context): SystemCheck.ShellResult {
        val xml = File(context.cacheDir, "privapp-permissions.xml")
        context.resources.openRawResource(R.raw.privapp_permissions).use { input ->
            xml.outputStream().use { input.copyTo(it) }
        }
        xml.setReadable(true, false)
        val apk = context.applicationInfo.sourceDir
        val props = SystemCheck.PROFILE_PROPS.joinToString("\n") { prop ->
            "grep -q '^$prop=true' /system/build.prop || echo '$prop=true' >> /system/build.prop"
        }
        return runScript(
            context, "install.sh",
            """
            [ -d $MAGISK_MODULE ] && [ ! -f $MAGISK_MODULE/disable ] && { echo "Installed by the Magisk module"; exit $EXIT_MAGISK_MODULE; }
            ${remount()}
            mkdir -p $BACKUP_DIR
            [ -f $BACKUP_DIR/build.prop ] || cp /system/build.prop $BACKUP_DIR/build.prop || exit 1
            mkdir -p $APP_DIR || exit 1
            cp '$apk' $APP_DIR/PhoneLink.apk || exit 1
            cp '${xml.absolutePath}' $PERMISSIONS_FILE || exit 1
            chown 0:0 $APP_DIR $APP_DIR/PhoneLink.apk $PERMISSIONS_FILE
            chmod 755 $APP_DIR
            chmod 644 $APP_DIR/PhoneLink.apk $PERMISSIONS_FILE
            chcon u:object_r:system_file:s0 $APP_DIR $APP_DIR/PhoneLink.apk $PERMISSIONS_FILE 2>/dev/null
            [ -n "${'$'}(tail -c 1 /system/build.prop)" ] && echo >> /system/build.prop
            $props
            sync
            echo "Installed"
            """,
        )
    }

    fun uninstall(context: Context): SystemCheck.ShellResult = runScript(
        context, "uninstall.sh",
        """
        [ -d $MAGISK_MODULE ] && [ ! -f $MAGISK_MODULE/disable ] && { echo "Installed by the Magisk module"; exit $EXIT_MAGISK_MODULE; }
        [ -d $APP_DIR ] || { echo "Not installed in the system"; exit $EXIT_NOT_INSTALLED; }
        ${remount()}
        rm -rf $APP_DIR $PERMISSIONS_FILE
        # Writing into the existing file keeps its owner, permissions and security label.
        [ -f $BACKUP_DIR/build.prop ] && cat $BACKUP_DIR/build.prop > /system/build.prop
        sync
        echo "Removed"
        """,
    )

    fun installedBySelf(): Boolean = SystemCheck.root("[ -d $APP_DIR ]", 10).ok

    fun reboot() {
        SystemCheck.root("sync; reboot", 10)
    }

    /** Opens /system for writing and checks that it really is writable. */
    private fun remount() = """
        mount -o rw,remount / 2>/dev/null || mount -o rw,remount /system 2>/dev/null
        touch /system/.phonelink_test 2>/dev/null && rm /system/.phonelink_test || { echo "/system is read-only"; exit $EXIT_NOT_WRITABLE; }
    """.trimIndent()

    private fun runScript(context: Context, name: String, body: String): SystemCheck.ShellResult {
        val file = File(context.cacheDir, name)
        file.writeText(body.trimIndent() + "\n")
        file.setReadable(true, false)
        return SystemCheck.root("sh '${file.absolutePath}'", 60)
    }
}
