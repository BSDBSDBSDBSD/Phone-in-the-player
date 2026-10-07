package com.offline.phonelink.root

import java.lang.reflect.Constructor

/**
 * A tiny program run as root through app_process (not inside the app):
 *   CLASSPATH=<apk> app_process /system/bin com.offline.phonelink.root.AudioPolicyTool connect|disconnect|sco-on <address>
 *
 * The audio policy only lets system processes declare devices and force routes, so a normal app gets
 * "unauthorized UID". Run as root, this declares the Bluetooth call headset (SCO in/out) and routes
 * communication audio to it, so MediaTek's Bluetooth call path (BT CVSD) carries the call's sound.
 * Prints one line per step; exit code 0 when every step returned success.
 */
object AudioPolicyTool {

    private const val ROLE_INPUT = 1
    private const val ROLE_OUTPUT = 2
    private const val TYPE_BLUETOOTH_SCO = 7 // AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    private const val DEVICE_OUT_BLUETOOTH_SCO = 0x10
    private const val DEVICE_IN_BLUETOOTH_SCO_HEADSET = 0x80000008.toInt()
    private const val FOR_COMMUNICATION = 0
    private const val FORCE_NONE = 0
    private const val FORCE_BT_SCO = 3

    private const val TYPE_BUILTIN_SPEAKER = 2 // AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    private const val DEVICE_ROLE_PREFERRED = 1

    @JvmStatic
    fun main(args: Array<String>) {
        val verb = args.getOrNull(0)
        val connect = verb == "connect"
        val address = args.getOrNull(1) ?: ""
        var ok = true
        val system = Class.forName("android.media.AudioSystem")
        val state = if (connect) 1 else 0

        fun step(name: String, block: () -> Any?) {
            val result = runCatching(block)
            val value = result.getOrNull()
            val success = result.isSuccess && (value == null || value == 0)
            if (!success) ok = false
            println("$name -> ${result.exceptionOrNull()?.let { it.cause ?: it } ?: value}")
        }

        if (verb == "sco-on") {
            // The phone reopened the call audio: switch the Bluetooth call path on again.
            step("BT_SCO=on") { setParameters(system, "BT_SCO=on") }
            System.exit(if (ok) 0 else 1)
        }

        if (!connect) {
            step("media back to default") { mediaToSpeaker(system, on = false) }
            step("BT_SCO=off") { setParameters(system, "BT_SCO=off") }
            step("forceUse(communication, none)") { forceUse(system, FOR_COMMUNICATION, FORCE_NONE) }
        }
        step("sco out state=$state") { connectDevice(system, ROLE_OUTPUT, DEVICE_OUT_BLUETOOTH_SCO, address, state) }
        step("sco in state=$state") { connectDevice(system, ROLE_INPUT, DEVICE_IN_BLUETOOTH_SCO_HEADSET, address, state) }
        if (connect) {
            step("forceUse(communication, bt_sco)") { forceUse(system, FOR_COMMUNICATION, FORCE_BT_SCO) }
            // The same parameters Android's AudioService sends when a Bluetooth headset opens its call
            // audio; on MediaTek "BT_SCO=on" switches on the Bluetooth call path (BT CVSD).
            step("bt headset params") { setParameters(system, "bt_headset_name=PhoneLink;bt_headset_nrec=on;bt_wbs=off") }
            step("BT_SCO=on") { setParameters(system, "BT_SCO=on") }
            // While Bluetooth call audio is on, MediaTek sends all playback to it; the phone's voice
            // must come out of the player's speaker instead.
            step("media to speaker") { mediaToSpeaker(system, on = true) }
        }
        System.exit(if (ok) 0 else 1)
    }

    /** Makes media playback prefer the built-in speaker (or drops that preference). */
    private fun mediaToSpeaker(system: Class<*>, on: Boolean): Any? {
        val strategies = Class.forName("android.media.audiopolicy.AudioProductStrategy")
            .getMethod("getAudioProductStrategies").invoke(null) as List<*>
        val media = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build()
        val strategy = strategies.filterNotNull().first { s ->
            s.javaClass.getMethod("supportsAudioAttributes", android.media.AudioAttributes::class.java).invoke(s, media) as Boolean
        }
        val id = strategy.javaClass.getMethod("getId").invoke(strategy) as Int
        if (!on) {
            return system.methods.first { it.name == "removeDevicesRoleForStrategy" && it.parameterTypes.size == 2 }
                .invoke(null, id, DEVICE_ROLE_PREFERRED)
        }
        val cls = Class.forName("android.media.AudioDeviceAttributes")
        val speaker = cls.constructors.first { c ->
            c.parameterTypes.contentEquals(arrayOf(Int::class.java, Int::class.java, String::class.java))
        }.newInstance(ROLE_OUTPUT, TYPE_BUILTIN_SPEAKER, "")
        return system.methods.first { it.name == "setDevicesRoleForStrategy" && it.parameterTypes.size == 3 }
            .invoke(null, id, DEVICE_ROLE_PREFERRED, listOf(speaker))
    }

    private fun setParameters(system: Class<*>, keyValues: String): Any? =
        system.methods.first { it.name == "setParameters" && it.parameterTypes.size == 1 }.invoke(null, keyValues)

    private fun forceUse(system: Class<*>, usage: Int, config: Int): Any? =
        system.methods.first { it.name == "setForceUse" && it.parameterTypes.size == 2 }.invoke(null, usage, config)

    private fun connectDevice(system: Class<*>, role: Int, native: Int, address: String, state: Int): Any? {
        val withAttributes = system.methods.firstOrNull {
            it.name == "setDeviceConnectionState" && it.parameterTypes.size == 3 &&
                it.parameterTypes[0].name == "android.media.AudioDeviceAttributes"
        }
        if (withAttributes != null) {
            val cls = Class.forName("android.media.AudioDeviceAttributes")
            @Suppress("UNCHECKED_CAST")
            val ctor = cls.constructors.first { c ->
                c.parameterTypes.contentEquals(arrayOf(Int::class.java, Int::class.java, String::class.java))
            } as Constructor<Any>
            return withAttributes.invoke(null, ctor.newInstance(role, TYPE_BLUETOOTH_SCO, address), state, 0)
        }
        return system.methods.first { it.name == "setDeviceConnectionState" && it.parameterTypes.size == 5 }
            .invoke(null, native, state, address, "PhoneLink", 0)
    }
}
