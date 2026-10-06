package com.offline.phonelink.ui

import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.text.format.DateUtils
import android.view.View
import android.view.WindowManager
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.offline.phonelink.R
import com.offline.phonelink.bt.LinkService
import com.offline.phonelink.bt.LinkSnapshot
import com.offline.phonelink.bt.LinkState
import com.offline.phonelink.bt.PhoneCall
import com.offline.phonelink.data.PhoneBook
import com.offline.phonelink.data.PhoneNumbers
import com.offline.phonelink.databinding.ActivityInCallBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The call screen: answer / reject, hang up, mute, keypad tones, volume and where the sound plays. */
class InCallActivity : AppCompatActivity() {

    private lateinit var b: ActivityInCallBinding
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private var activeSince: Pair<Int, Long>? = null
    private var hadCall = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityInCallBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_VOICE_CALL

        b.answerButton.setOnClickListener { LinkService.current?.answer() }
        b.rejectButton.setOnClickListener { LinkService.current?.reject() }
        b.hangUpButton.setOnClickListener { LinkService.current?.hangUp() }
        b.audioButton.setOnClickListener { LinkService.current?.toggleAudio() }
        b.muteButton.setOnClickListener {
            audio.isMicrophoneMute = !audio.isMicrophoneMute
            renderMute()
        }
        b.volumeUp.setOnClickListener { changeVolume(AudioManager.ADJUST_RAISE) }
        b.volumeDown.setOnClickListener { changeVolume(AudioManager.ADJUST_LOWER) }
        b.keypadButton.setOnClickListener {
            val show = b.keypad.visibility != View.VISIBLE
            b.keypad.visibility = if (show) View.VISIBLE else View.GONE
            b.controls.visibility = if (show) View.GONE else View.VISIBLE
        }
        for (i in 0 until b.dtmfKeys.childCount) {
            val key = b.dtmfKeys.getChildAt(i) as Button
            key.setOnClickListener {
                LinkService.current?.sendDigit(key.text[0])
                b.dtmfDigits.append(key.text)
            }
        }
        renderMute()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                LinkState.state.collect { render(it) }
            }
        }
        // Call timer
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    renderState(LinkState.state.value.mainCall)
                    delay(1_000)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        LinkService.current?.refresh()
    }

    private fun render(s: LinkSnapshot) {
        val call = s.mainCall
        if (call == null) {
            if (hadCall) {
                b.callState.setText(R.string.call_ended)
                audio.isMicrophoneMute = false
                b.root.postDelayed({ if (!LinkState.state.value.hasCall) finish() }, 1_200)
            } else {
                finish()
            }
            return
        }
        hadCall = true

        val name = PhoneBook.nameFor(this, call.number)
        val pretty = PhoneNumbers.pretty(call.number)
        b.callName.text = name ?: pretty.ifEmpty { getString(R.string.unknown_number) }
        b.callNumber.text = if (name != null) pretty else ""
        b.callNumber.visibility = if (name != null) View.VISIBLE else View.GONE

        val others = s.calls.filter { it.id != call.id && !it.isOver }
        b.otherCalls.visibility = if (others.isEmpty()) View.GONE else View.VISIBLE
        b.otherCalls.text = others.joinToString("\n") { other ->
            val who = PhoneBook.nameFor(this, other.number) ?: PhoneNumbers.pretty(other.number)
            "$who · ${stateText(other)}"
        }

        val incoming = call.isRinging
        b.incomingButtons.visibility = if (incoming) View.VISIBLE else View.GONE
        b.hangUpButton.visibility = if (incoming) View.GONE else View.VISIBLE
        b.controls.alpha = if (incoming) 0.4f else 1f
        b.audioButton.setText(if (s.audioOnPlayer) R.string.sound_on_player else R.string.sound_on_phone)

        if (call.isActive && activeSince?.first != call.id) activeSince = call.id to SystemClock.elapsedRealtime()
        renderState(call)
    }

    private fun renderState(call: PhoneCall?) {
        call ?: return
        b.callState.text = if (call.isActive) {
            val since = activeSince?.takeIf { it.first == call.id }?.second ?: SystemClock.elapsedRealtime()
            DateUtils.formatElapsedTime((SystemClock.elapsedRealtime() - since) / 1000)
        } else {
            stateText(call)
        }
    }

    private fun stateText(call: PhoneCall): String = getString(
        when (call.state) {
            PhoneCall.STATE_INCOMING -> R.string.incoming_call
            PhoneCall.STATE_WAITING -> R.string.waiting_call
            PhoneCall.STATE_DIALING -> R.string.dialing
            PhoneCall.STATE_ALERTING -> R.string.ringing_out
            PhoneCall.STATE_HELD, PhoneCall.STATE_HELD_BY_RESPONSE_AND_HOLD -> R.string.on_hold
            else -> R.string.notif_in_call
        },
    )

    private fun renderMute() {
        b.muteButton.setText(if (audio.isMicrophoneMute) R.string.unmute else R.string.mute)
    }

    private fun changeVolume(direction: Int) {
        audio.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, direction, AudioManager.FLAG_SHOW_UI)
    }
}
