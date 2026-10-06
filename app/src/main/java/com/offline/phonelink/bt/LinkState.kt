package com.offline.phonelink.bt

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Everything the screens show about the phone, kept up to date by [LinkService]. */
data class LinkSnapshot(
    val bluetoothOn: Boolean = false,
    /** Android handed out the hands-free client profile (the player is set up correctly). */
    val profileReady: Boolean = false,
    val phoneName: String? = null,
    val phoneAddress: String? = null,
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val calls: List<PhoneCall> = emptyList(),
    val audioOnPlayer: Boolean = false,
    val signal: Int? = null,
    val battery: Int? = null,
    val operator: String? = null,
) {
    val ringing: PhoneCall? get() = calls.firstOrNull { it.isRinging }
    val hasCall: Boolean get() = calls.any { !it.isOver }

    /** The call shown in the middle of the call screen. */
    val mainCall: PhoneCall?
        get() = calls.firstOrNull { it.isRinging && it.state == PhoneCall.STATE_INCOMING }
            ?: calls.firstOrNull { it.isActive }
            ?: calls.firstOrNull { it.isDialing }
            ?: calls.firstOrNull { it.isRinging }
            ?: calls.firstOrNull { it.isHeld }
}

object LinkState {
    private val _state = MutableStateFlow(LinkSnapshot())
    val state: StateFlow<LinkSnapshot> = _state

    internal fun set(snapshot: LinkSnapshot) {
        _state.value = snapshot
    }
}
