package com.shimonhoter.localhost

import android.content.Context
import android.provider.ContactsContract
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject

class ContactsBridge(
    private val context: Context,
    private val hasContactsAccess: () -> Boolean,
    private val requestContactsAccess: () -> Unit
) {
    @JavascriptInterface
    fun getContacts(): String {
        if (!hasContactsAccess()) {
            requestContactsAccess()
            return JSONObject().apply { put("error", "permission_required") }.toString()
        }
        val array = JSONArray()
        val resolver = context.contentResolver
        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )
        cursor?.use {
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                array.put(JSONObject().apply {
                    put("name", it.getString(nameIdx))
                    put("phone", it.getString(numberIdx))
                })
            }
        }
        return array.toString()
    }
}
