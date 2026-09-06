package com.libeyond.imandroid

import android.app.Application
import com.libeyond.imandroid.sdk.logging.IMLog

class IMApp : Application() {
    override fun onCreate() {
        super.onCreate()
        IMLog.tag("IM.App").i("app_start", "versionName" to BuildConfig.VERSION_NAME)
    }
}
