package com.mssdvd.platestracker

import android.app.Application

class PlatesTrackerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
    }
}
