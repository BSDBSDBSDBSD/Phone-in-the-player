package com.offline.phonelink

import android.app.Application
import com.offline.phonelink.bt.HfpClient

class PhoneLinkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        HfpClient.unlockHiddenApi()
    }
}
