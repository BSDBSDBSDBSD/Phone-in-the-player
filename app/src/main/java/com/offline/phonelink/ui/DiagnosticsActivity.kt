package com.offline.phonelink.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.offline.phonelink.R
import com.offline.phonelink.bt.HfpClient
import com.offline.phonelink.bt.LinkService
import com.offline.phonelink.bt.LogCollector
import com.offline.phonelink.bt.AudioBridge
import com.offline.phonelink.bt.PhoneAppLink
import android.content.Intent
import com.offline.phonelink.bt.SystemCheck
import com.offline.phonelink.bt.SystemInstaller
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.offline.phonelink.data.PhoneBook
import com.offline.phonelink.databinding.ActivityDiagnosticsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shows step by step whether the player is ready to act as a hands-free unit, and what to fix. */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var b: ActivityDiagnosticsBinding
    private var report = ""

    private class Check(val ok: Boolean?, val title: String, val help: String? = null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.refresh.setOnClickListener { runChecks() }
        b.copy.setOnClickListener {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("PhoneLink", report))
            Toast.makeText(this, R.string.diag_copied, Toast.LENGTH_SHORT).show()
        }
        b.enableNow.setOnClickListener { enableNow() }
        b.collectLog.setOnClickListener { collectLog() }
        b.phoneAppLink.setOnClickListener { togglePhoneAppLink() }
        b.bridgeSwitch.isChecked = AudioBridge.isEnabled(this)
        b.bridgeSwitch.setOnCheckedChangeListener { _, on -> AudioBridge.setEnabled(this, on) }
        b.installSystem.setOnClickListener {
            confirm(R.string.install_system_confirm, R.string.install_system_go) {
                runInstaller(R.string.install_done) { SystemInstaller.install(this) }
            }
        }
        b.uninstallSystem.setOnClickListener {
            confirm(R.string.uninstall_confirm, R.string.uninstall_go) {
                runInstaller(R.string.uninstall_done) { SystemInstaller.uninstall(this) }
            }
        }
        runChecks()
    }

    private fun runChecks() {
        lifecycleScope.launch {
            val checks = withContext(Dispatchers.IO) { collect() }
            val system = SystemCheck.isSystemApp(this@DiagnosticsActivity)
            b.installSystem.visibility = if (system) View.GONE else View.VISIBLE
            b.uninstallSystem.visibility = if (system) View.VISIBLE else View.GONE
            val link = PhoneAppLink.isEnabled(this@DiagnosticsActivity)
            b.phoneAppLink.visibility = if (link == null) View.GONE else View.VISIBLE
            b.phoneAppLink.setText(if (link == false) R.string.phone_app_link_on else R.string.phone_app_link_off)
            b.checks.removeAllViews()
            for (c in checks) addRow(c)
            report = buildString {
                appendLine("PhoneLink · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                for (c in checks) appendLine("${mark(c.ok)} ${c.title}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun collect(): List<Check> {
        val list = mutableListOf<Check>()
        list += Check(
            Build.VERSION.SDK_INT >= 33,
            "אנדרואיד ${Build.VERSION.RELEASE}",
            if (Build.VERSION.SDK_INT < 33) "האפליקציה נבנתה ונבדקה לאנדרואיד 13. בגרסה ישנה יותר ייתכן שחלק מהדברים לא יעבדו." else null,
        )
        val root = SystemCheck.hasRoot()
        list += Check(root, "הרשאת Root", if (!root) "לא התקבלה הרשאת Root. ב-Magisk צריך לאשר ל\"דיבורית\" גישת Superuser (נדרש רק לכפתור ההפעלה הזמנית)." else null)

        val system = SystemCheck.isSystemApp(this)
        val privileged = SystemCheck.hasPrivilegedBluetooth(this)
        list += Check(
            system && privileged, "מותקנת כאפליקציית מערכת עם הרשאת בלוטות' מיוחדת",
            when {
                !system -> "לחץ למטה על \"התקן כאפליקציית מערכת\" (אם אפשר לכתוב למחיצת המערכת), או התקן את מודול ה-Magisk (PhoneLink-magisk.zip). אחר כך מפעילים מחדש את הנגן."
                !privileged -> "האפליקציה במערכת אבל בלי ההרשאה. ודא שהמודול מותקן במלואו והפעל מחדש."
                else -> null
            },
        )
        list += Check(HfpClient.unlockHiddenApi(), "גישה לממשק הדיבורית של אנדרואיד")

        for (prop in SystemCheck.PROFILE_PROPS) {
            val value = SystemCheck.prop(prop)
            val optional = prop.contains("map")
            list += Check(
                if (value == "true") true else if (optional) null else false,
                "$prop = ${value.ifEmpty { "(לא מוגדר)" }}",
                if (value != "true" && !optional) "הפרופיל כבוי. המודול מפעיל אותו אחרי הפעלה מחדש, או לחץ על \"הפעל עכשיו עם Root\"." else null,
            )
        }

        val service = LinkService.current
        val hfp = service?.hfp
        list += Check(
            hfp?.hfp != null, "פרופיל הדיבורית (HFP) זמין",
            if (hfp?.hfp == null) {
                if (hfp?.hfpRequested == false) "אנדרואיד סירב לפתוח את הפרופיל: הוא כבוי בנגן הזה."
                else "הפרופיל לא נפתח. נסה להפעיל מחדש את הבלוטות' או את הנגן."
            } else null,
        )
        list += Check(hfp?.pbap != null, "פרופיל אנשי הקשר (PBAP) זמין", if (hfp?.pbap == null) "בלי זה אפשר לחייג, אבל אנשי הקשר לא יגיעו מהטלפון." else null)

        val paired = hfp?.pairedDevices().orEmpty()
        list += Check(
            paired.isNotEmpty(), "טלפונים מצומדים: ${paired.size}",
            if (paired.isEmpty()) "צמד את הטלפון בהגדרות הבלוטות' של הנגן (חפש מכשירים בנגן, ואשר את הקוד בשני המכשירים)." else null,
        )
        val phone = hfp?.connectedPhone()
        list += Check(
            phone != null,
            if (phone != null) "מחובר לשיחות: ${runCatching { phone.alias ?: phone.name }.getOrNull() ?: phone.address}" else "אין טלפון מחובר לשיחות",
            if (phone == null && paired.isNotEmpty()) "במסך הראשי לחץ \"בחר טלפון\". אם הטלפון שואל אם לאשר גישה, אשר." else null,
        )
        if (phone != null) {
            val pbapOk = hfp.pbapState(phone) == android.bluetooth.BluetoothProfile.STATE_CONNECTED
            list += Check(pbapOk, if (pbapOk) "אנשי הקשר מחוברים" else "אנשי הקשר לא מחוברים",
                if (!pbapOk) "הטלפון אולי לא תומך בשיתוף אנשי קשר, או שצריך לאשר בו גישה לאנשי הקשר." else null)
            val audioOn = hfp.audioOnPlayer(phone)
            list += Check(null, if (audioOn) "ערוץ הקול פתוח לנגן" else "ערוץ הקול סגור (נפתח בזמן שיחה)")
        }
        when (PhoneAppLink.isEnabled(this)) {
            true -> list += Check(
                false, "השיחות עוברות גם לאפליקציית הטלפון הרגילה",
                "בנגן הזה זה מקריס את שירות השמע בזמן שיחה (ולכן אין קול). לחץ למטה על \"נתק מאפליקציית הטלפון הרגילה\".",
            )
            false -> list += Check(true, "השיחות עוברות רק דרך \"דיבורית\"")
            null -> {}
        }
        val copied = PhoneBook.copiedFromPhone(this)
        list += Check(
            if (copied > 0) true else null,
            if (copied >= 0) "אנשי קשר שהגיעו מהטלפון: $copied" else "אין הרשאה לקרוא אנשי קשר",
        )
        return list
    }

    private fun enableNow() {
        b.enableNow.isEnabled = false
        b.enableNow.setText(R.string.diag_working)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { SystemCheck.enableProfilesNow() }
            b.shellOutput.visibility = View.VISIBLE
            b.shellOutput.text = (if (result.ok) "OK\n" else "FAILED\n") + result.output
            delay(4_000)
            LinkService.current?.hfp?.open()
            delay(1_500)
            LinkService.current?.refresh()
            b.enableNow.isEnabled = true
            b.enableNow.setText(R.string.diag_enable_now)
            runChecks()
        }
    }

    private fun collectLog() {
        b.collectLog.isEnabled = false
        b.collectLog.setText(R.string.diag_working)
        lifecycleScope.launch {
            val log = withContext(Dispatchers.IO) { LogCollector.collect(this@DiagnosticsActivity) }
            b.collectLog.isEnabled = true
            b.collectLog.setText(R.string.collect_log)
            if (!log.ok) {
                b.shellOutput.visibility = View.VISIBLE
                b.shellOutput.text = log.error
                MaterialAlertDialogBuilder(this@DiagnosticsActivity)
                    .setMessage(R.string.collect_log_failed)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }
            MaterialAlertDialogBuilder(this@DiagnosticsActivity)
                .setMessage(R.string.collect_log_done)
                .setPositiveButton(R.string.share) { _, _ ->
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "PhoneLink-log")
                        .putExtra(Intent.EXTRA_TEXT, report + "\n" + log.text.takeLast(400_000))
                    startActivity(Intent.createChooser(send, getString(R.string.share)))
                }
                .setNegativeButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun togglePhoneAppLink() {
        val turnOn = PhoneAppLink.isEnabled(this) == false
        confirm(
            if (turnOn) R.string.phone_app_link_on_confirm else R.string.phone_app_link_off_confirm,
            if (turnOn) R.string.phone_app_link_on else R.string.phone_app_link_off,
        ) {
            b.phoneAppLink.isEnabled = false
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { PhoneAppLink.setEnabled(this@DiagnosticsActivity, turnOn) }
                b.phoneAppLink.isEnabled = true
                b.shellOutput.visibility = View.VISIBLE
                b.shellOutput.text = "exit ${result.code}\n${result.output}"
                Toast.makeText(
                    this@DiagnosticsActivity,
                    if (result.ok) R.string.phone_app_link_done else R.string.install_failed,
                    Toast.LENGTH_LONG,
                ).show()
                delay(5_000)
                LinkService.current?.hfp?.open()
                LinkService.current?.refresh()
                runChecks()
            }
        }
    }

    private fun confirm(message: Int, action: Int, onYes: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setMessage(message)
            .setPositiveButton(action) { _, _ -> onYes() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Runs the system install / removal and explains the outcome; offers a restart when it worked. */
    private fun runInstaller(doneMessage: Int, work: () -> SystemCheck.ShellResult) {
        b.installSystem.isEnabled = false
        b.uninstallSystem.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (!SystemCheck.hasRoot()) null else work()
            }
            b.installSystem.isEnabled = true
            b.uninstallSystem.isEnabled = true
            if (result != null) {
                b.shellOutput.visibility = View.VISIBLE
                b.shellOutput.text = "exit ${result.code}\n${result.output}"
            }
            val message = when (result?.code) {
                null -> R.string.no_root
                0 -> doneMessage
                SystemInstaller.EXIT_NOT_WRITABLE -> R.string.system_read_only
                SystemInstaller.EXIT_MAGISK_MODULE -> R.string.installed_by_magisk
                SystemInstaller.EXIT_NOT_INSTALLED -> R.string.not_installed_in_system
                else -> R.string.install_failed
            }
            val dialog = MaterialAlertDialogBuilder(this@DiagnosticsActivity).setMessage(message)
            if (result?.ok == true) {
                dialog.setPositiveButton(R.string.reboot_now) { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) { SystemInstaller.reboot() }
                }.setNegativeButton(R.string.later, null)
            } else {
                dialog.setPositiveButton(android.R.string.ok, null)
            }
            dialog.show()
        }
    }

    private fun addRow(c: Check) {
        val pad = (8 * resources.displayMetrics.density).toInt()
        val title = TextView(this).apply {
            text = "${mark(c.ok)}  ${c.title}"
            textSize = 16f
            setPadding(0, pad, 0, 0)
            setTextColor(
                ContextCompat.getColor(
                    context,
                    when (c.ok) { true -> R.color.status_ok; false -> R.color.status_bad; null -> R.color.status_warn },
                ),
            )
        }
        b.checks.addView(title)
        c.help?.let {
            b.checks.addView(TextView(this).apply { text = it; setPadding(pad * 3, 0, 0, pad / 2) })
        }
    }

    private fun mark(ok: Boolean?) = when (ok) { true -> "✓"; false -> "✗"; null -> "•" }
}
