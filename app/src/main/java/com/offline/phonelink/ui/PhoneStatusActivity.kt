package com.offline.phonelink.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.offline.phonelink.R
import com.offline.phonelink.bt.LinkService
import com.offline.phonelink.bt.LinkSnapshot
import com.offline.phonelink.bt.LinkState
import com.offline.phonelink.data.PhoneBook
import com.offline.phonelink.data.PhoneNumbers
import com.offline.phonelink.databinding.ActivityPhoneStatusBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The phone at a glance: connection, battery, signal, network, roaming, its own number, contacts. */
class PhoneStatusActivity : AppCompatActivity() {

    private lateinit var b: ActivityPhoneStatusBinding
    private var contactsFromPhone = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPhoneStatusBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.resync.setOnClickListener {
            LinkService.current?.resyncPhonebook()
            PhoneBook.invalidate()
            Toast.makeText(this, R.string.resync_started, Toast.LENGTH_LONG).show()
        }
        lifecycleScope.launch {
            contactsFromPhone = withContext(Dispatchers.IO) { PhoneBook.copiedFromPhone(this@PhoneStatusActivity) }
            render(LinkState.state.value)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                LinkState.state.collect { render(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        LinkService.current?.refresh()
    }

    private fun render(s: LinkSnapshot) {
        val unknown = getString(R.string.status_unknown)
        val rows = listOf(
            getString(R.string.status_row_phone) to (s.phoneName ?: s.phoneAddress ?: getString(R.string.status_not_connected)),
            getString(R.string.status_row_connection) to getString(if (s.connected) R.string.status_connected_short else R.string.status_disconnected_short),
            getString(R.string.status_row_battery) to (s.battery?.let { bars(it) + "  " + getString(R.string.status_of_five, it) } ?: unknown),
            getString(R.string.status_row_signal) to (s.signal?.let { bars(it) + "  " + getString(R.string.status_of_five, it) } ?: unknown),
            getString(R.string.status_row_network) to (s.operator?.takeIf { it.isNotBlank() }
                ?: s.networkAvailable?.let { getString(if (it) R.string.status_network_ok else R.string.status_network_none) } ?: unknown),
            getString(R.string.status_row_roaming) to (s.roaming?.let { getString(if (it) R.string.yes else R.string.no) } ?: unknown),
            getString(R.string.status_row_number) to (s.ownNumber?.let { PhoneNumbers.pretty(it) } ?: getString(R.string.status_number_unknown)),
            getString(R.string.status_row_contacts) to (if (contactsFromPhone >= 0) contactsFromPhone.toString() else unknown),
        )
        b.rows.removeAllViews()
        val pad = (10 * resources.displayMetrics.density).toInt()
        for ((title, value) in rows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, pad, 0, pad)
            }
            row.addView(TextView(this).apply {
                text = title
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            })
            row.addView(TextView(this).apply {
                text = value
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            })
            if (title == getString(R.string.status_row_number) && s.ownNumber != null) {
                row.setOnClickListener {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("number", s.ownNumber))
                    Toast.makeText(this, R.string.diag_copied, Toast.LENGTH_SHORT).show()
                }
            }
            b.rows.addView(row)
        }
    }

    /** "●●●○○" for a 0-5 level. */
    private fun bars(level: Int): String {
        val l = level.coerceIn(0, 5)
        return "●".repeat(l) + "○".repeat(5 - l)
    }
}
