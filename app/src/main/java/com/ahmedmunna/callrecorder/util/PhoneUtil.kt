package com.ahmedmunna.callrecorder.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

object PhoneUtil {
    /** Keeps digits and a leading plus so numbers compare reliably. */
    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return "UNKNOWN"
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) return "UNKNOWN"
        return if (trimmed.startsWith("+")) "+$digits" else digits
    }

    fun sameNumber(a: String, b: String): Boolean {
        if (a == "UNKNOWN" || b == "UNKNOWN") return false
        val x = a.filter { it.isDigit() }
        val y = b.filter { it.isDigit() }
        val n = minOf(x.length, y.length, 9)
        return n >= 5 && x.takeLast(n) == y.takeLast(n)
    }
}

data class ContactInfo(val name: String?, val photoUri: String?)

object ContactResolver {
    fun has(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun lookup(ctx: Context, number: String): ContactInfo {
        if (!has(ctx) || number == "UNKNOWN") return ContactInfo(null, null)
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            ctx.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) ContactInfo(c.getString(0), c.getString(1)) else ContactInfo(null, null)
            } ?: ContactInfo(null, null)
        }.getOrDefault(ContactInfo(null, null))
    }

    fun isContact(ctx: Context, number: String) = lookup(ctx, number).name != null
}

object CallLogResolver {
    fun has(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    /** Number of the most recent call logged at or after [sinceMs], used to learn outgoing numbers. */
    fun latestNumber(ctx: Context, sinceMs: Long): String? {
        if (!has(ctx)) return null
        return runCatching {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                "${CallLog.Calls.DATE} >= ?", arrayOf((sinceMs - 5_000).toString()),
                "${CallLog.Calls.DATE} DESC",
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }
}
