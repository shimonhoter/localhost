package com.shimonhoter.localhost

import android.telephony.SmsManager
import android.webkit.JavascriptInterface

/**
 * Exposed to loaded pages as `window.AndroidSms`. Only reachable by pages
 * served through this app's own local server (files the user chose to
 * open), never by arbitrary remote content.
 *
 * Usage from a page's JavaScript:
 *   AndroidSms.sendSms("0501234567", "hello")
 */
class SmsBridge(private val onSendRequested: (phone: String, message: String) -> Unit) {

    @JavascriptInterface
    fun sendSms(phone: String, message: String) {
        onSendRequested(phone, message)
    }
}

object SmsSender {
    fun send(phone: String, message: String) {
        val smsManager = SmsManager.getDefault()
        val parts = smsManager.divideMessage(message)
        smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
    }
}
