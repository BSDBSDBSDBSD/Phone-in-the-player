package com.offline.phonelink.root

import java.lang.reflect.Constructor

/**
 * A tiny program run as root through app_process (not inside the app):
 *   CLASSPATH=<apk> app_process /system/bin com.offline.phonelink.root.AudioPolicyTool connect|disconnect <address>
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

    @JvmStatic
    fun main(args: Array<String>) {
        val connect = args.getOrNull(0) == "connect"
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

        if (!connect) {
            step("forceUse(communication, none)") { forceUse(system, FOR_COMMUNICATION, FORCE_NONE) }
        }
        step("sco out state=$state") { connectDevice(system, ROLE_OUTPUT, DEVICE_OUT_BLUETOOTH_SCO, address, state) }
        step("sco in state=$state") { connectDevice(system, ROLE_INPUT, DEVICE_IN_BLUETOOTH_SCO_HEADSET, address, state) }
        if (connect) {
            step("forceUse(communication, bt_sco)") { forceUse(system, FOR_COMMUNICATION, FORCE_BT_SCO) }
        }
        System.exit(if (ok) 0 else 1)
    }

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
