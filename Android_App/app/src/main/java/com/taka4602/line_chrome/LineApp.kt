package com.taka4602.line_chrome

import android.app.Application
import com.taka4602.line_chrome.data.LineRepository
import com.taka4602.line_chrome.service.Notifier

class LineApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        // The service can be revived by the system without the Activity, so the
        // repository has to be usable before any UI exists.
        LineRepository.init(this)
    }
}
