package com.offline.phonelink.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.offline.phonelink.R
import com.offline.phonelink.bt.LinkService
import com.offline.phonelink.bt.LinkSnapshot
import com.offline.phonelink.bt.LinkState
import com.offline.phonelink.data.CallDirection
import com.offline.phonelink.data.Contact
import com.offline.phonelink.data.History
import com.offline.phonelink.data.PhoneBook
import com.offline.phonelink.data.PhoneNumbers
import com.offline.phonelink.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val contactsAdapter = RowAdapter()
    private val recentsAdapter = RowAdapter()
    private var contacts: List<Contact> = emptyList()
    private var wasConnected = false

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        LinkService.start(this)
        reloadLists()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        setUpToolbar()
        setUpDialer()
        setUpLists()
        b.tabs.setOnItemSelectedListener { item -> showTab(item.itemId); true }
        b.statusCard.setOnClickListener { onStatusClicked() }
        b.returnToCall.setOnClickListener { startActivity(Intent(this, InCallActivity::class.java)) }

        requestMissingPermissions()
        LinkService.start(this)
        handleDialIntent(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                LinkState.state.collect { render(it) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDialIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        LinkService.current?.refresh()
        reloadLists()
    }

    /** tel: links from other apps fill the dial pad. */
    private fun handleDialIntent(intent: Intent?) {
        val number = intent?.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart ?: return
        b.tabs.selectedItemId = R.id.tab_dialer
        setNumber(PhoneNumbers.clean(number))
    }

    // ------------------------------------------------------------------ toolbar & status

    private fun setUpToolbar() {
        b.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_choose_phone -> choosePhone()
                R.id.menu_resync -> {
                    LinkService.current?.resyncPhonebook()
                    PhoneBook.invalidate()
                    Toast.makeText(this, R.string.resync_started, Toast.LENGTH_LONG).show()
                }
                R.id.menu_bt_settings -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                R.id.menu_diagnostics -> startActivity(Intent(this, DiagnosticsActivity::class.java))
            }
            true
        }
    }

    private fun render(s: LinkSnapshot) {
        val (title, color) = when {
            !s.bluetoothOn -> getString(R.string.status_bt_off) to R.color.status_bad
            !s.profileReady -> getString(R.string.status_not_ready) to R.color.status_bad
            s.connected -> getString(R.string.status_connected, s.phoneName ?: s.phoneAddress ?: "") to R.color.status_ok
            s.connecting -> getString(R.string.status_connecting) to R.color.status_warn
            else -> getString(R.string.status_not_connected) to R.color.status_warn
        }
        b.statusTitle.text = title
        b.statusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, color))

        val details = buildList {
            s.operator?.takeIf { it.isNotBlank() }?.let { add(it) }
            if (s.signal != null || s.battery != null) {
                add(getString(R.string.status_details, s.signal?.let { "$it/5" } ?: "-", s.battery?.let { "$it/5" } ?: "-"))
            }
        }.joinToString(" · ")
        b.statusDetails.text = details
        b.statusDetails.visibility = if (s.connected && details.isNotEmpty()) View.VISIBLE else View.GONE
        b.returnToCall.visibility = if (s.hasCall) View.VISIBLE else View.GONE

        // Contacts arrive shortly after the phone connects.
        if (s.connected && !wasConnected) {
            b.root.postDelayed({ PhoneBook.invalidate(); reloadLists() }, 8_000)
        }
        wasConnected = s.connected
    }

    private fun onStatusClicked() {
        val s = LinkState.state.value
        when {
            !s.profileReady -> startActivity(Intent(this, DiagnosticsActivity::class.java))
            !s.connected -> choosePhone()
        }
    }

    @SuppressLint("MissingPermission")
    private fun choosePhone() {
        val service = LinkService.current ?: return LinkService.start(this)
        val devices = service.hfp.pairedDevices()
        if (devices.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.no_paired)
                .setPositiveButton(R.string.open_bt_settings) { _, _ -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .show()
            return
        }
        val names = devices.map { runCatching { it.alias ?: it.name }.getOrNull() ?: it.address }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_phone_title)
            .setItems(names) { _, i ->
                Toast.makeText(this, getString(R.string.connecting_to, names[i]), Toast.LENGTH_SHORT).show()
                if (!service.connectTo(devices[i])) Toast.makeText(this, R.string.connect_failed, Toast.LENGTH_LONG).show()
            }
            .setNeutralButton(R.string.open_bt_settings) { _, _ -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .show()
    }

    // ------------------------------------------------------------------ dialer

    private fun setUpDialer() {
        val keys = b.dialer.keys
        for (i in 0 until keys.childCount) {
            val key = keys.getChildAt(i) as Button
            key.setOnClickListener { setNumber(b.dialer.number.text.toString() + key.text) }
            if (key.text == "0") key.setOnLongClickListener { setNumber(b.dialer.number.text.toString() + "+"); true }
        }
        b.dialer.backspace.setOnClickListener { setNumber(b.dialer.number.text.toString().dropLast(1)) }
        b.dialer.backspace.setOnLongClickListener { setNumber(""); true }
        b.dialer.callButton.setOnClickListener {
            val number = b.dialer.number.text.toString()
            if (number.isNotEmpty() && callNumber(number)) setNumber("")
        }
    }

    private fun setNumber(n: String) {
        b.dialer.number.text = n
        b.dialer.matchName.text = if (n.length >= 6) History.nameFor(contacts, n) ?: "" else ""
    }

    private fun callNumber(number: String): Boolean {
        val service = LinkService.current
        if (service == null || service.phone() == null) {
            Toast.makeText(this, R.string.no_phone_to_call, Toast.LENGTH_LONG).show()
            return false
        }
        if (!service.dial(number)) {
            Toast.makeText(this, R.string.dial_failed, Toast.LENGTH_LONG).show()
            return false
        }
        startActivity(Intent(this, InCallActivity::class.java))
        return true
    }

    // ------------------------------------------------------------------ contacts & recents

    private fun setUpLists() {
        b.contacts.list.layoutManager = LinearLayoutManager(this)
        b.contacts.list.adapter = contactsAdapter
        b.recents.list.layoutManager = LinearLayoutManager(this)
        b.recents.list.adapter = recentsAdapter
        b.contacts.search.doAfterTextChanged { showContacts() }
    }

    private fun showTab(id: Int) {
        b.dialer.root.visibility = if (id == R.id.tab_dialer) View.VISIBLE else View.GONE
        b.contacts.root.visibility = if (id == R.id.tab_contacts) View.VISIBLE else View.GONE
        b.recents.root.visibility = if (id == R.id.tab_recents) View.VISIBLE else View.GONE
        if (id != R.id.tab_dialer) reloadLists()
    }

    private fun reloadLists() {
        lifecycleScope.launch {
            val (c, h) = withContext(Dispatchers.IO) {
                PhoneBook.invalidate()
                PhoneBook.contacts(this@MainActivity) to PhoneBook.history(this@MainActivity)
            }
            contacts = c
            showContacts()
            recentsAdapter.submit(
                h.map { e ->
                    val kind = when (e.direction) {
                        CallDirection.INCOMING -> getString(R.string.recent_in)
                        CallDirection.OUTGOING -> getString(R.string.recent_out)
                        CallDirection.MISSED -> getString(R.string.recent_missed)
                    }
                    val time = DateUtils.getRelativeTimeSpanString(e.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                    val title = e.name?.takeIf { it.isNotBlank() } ?: PhoneNumbers.pretty(e.number).ifEmpty { getString(R.string.unknown_number) }
                    Row(title, "$kind · $time") { if (e.number.isNotBlank()) callNumber(e.number) }
                },
            )
            b.recents.empty.visibility = if (h.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showContacts() {
        val shown = History.filter(contacts, b.contacts.search.text?.toString().orEmpty())
        contactsAdapter.submit(
            shown.map { c ->
                Row(c.name, c.numbers.joinToString(", ") { PhoneNumbers.pretty(it) }) { callContact(c) }
            },
        )
        b.contacts.empty.visibility = if (contacts.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun callContact(c: Contact) {
        if (c.numbers.size == 1) {
            callNumber(c.numbers[0])
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_number)
            .setItems(c.numbers.map { PhoneNumbers.pretty(it) }.toTypedArray()) { _, i -> callNumber(c.numbers[i]) }
            .show()
    }

    // ------------------------------------------------------------------ permissions

    private fun requestMissingPermissions() {
        val wanted = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.RECORD_AUDIO,
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) {
            Toast.makeText(this, R.string.permissions_needed, Toast.LENGTH_LONG).show()
            askPermissions.launch(wanted.toTypedArray())
        }
    }
}
