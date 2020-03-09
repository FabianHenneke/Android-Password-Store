package com.zeapo.pwdstore.autofill.oreo

import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.service.autofill.SaveCallback
import android.util.Base64
import android.widget.RemoteViews
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.github.ajalt.timberkt.Timber.tag
import com.github.ajalt.timberkt.e
import com.google.common.net.InternetDomainName
import com.zeapo.pwdstore.PasswordEntry
import com.zeapo.pwdstore.R
import java.io.File
import java.security.MessageDigest


private fun ByteArray.sha256(): ByteArray {
    return MessageDigest.getInstance("SHA-256").run {
        update(this@sha256)
        digest()
    }
}

private fun ByteArray.base64(): String {
    return Base64.encodeToString(this, Base64.NO_WRAP)
}

private fun stableHash(array: Collection<ByteArray>): String {
    val hashes = array.map { it.sha256().base64() }
    return hashes.sorted().joinToString(separator = ";")
}

fun computeCertificatesHash(context: Context, packageName: String): String {
    val signaturesOld =
        context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
    val stableHashOld = stableHash(signaturesOld.map { it.toByteArray() })
    if (Build.VERSION.SDK_INT >= 28) {
        val info = context.packageManager.getPackageInfo(
            packageName, PackageManager.GET_SIGNING_CERTIFICATES
        )
        val signaturesNew =
            info.signingInfo.signingCertificateHistory ?: info.signingInfo.apkContentsSigners
        val stableHashNew = stableHash(signaturesNew.map { it.toByteArray() })
        if (stableHashNew != stableHashOld) tag("CertificatesHash").e { "Mismatch between old and new hash: $stableHashNew != $stableHashOld" }
    }
    return stableHashOld
}

fun getCanonicalDomain(host: String): String? {
    var idn = InternetDomainName.from(host)
    while (idn != null && !idn.isTopPrivateDomain) idn = idn.parent()
    return idn.toString()
}

data class Credentials(val username: String?, val password: String) {
    companion object {
        fun fromStoreEntry(file: File, entry: PasswordEntry): Credentials {
            return if (entry.hasUsername()) Credentials(entry.username, entry.password)
            else Credentials(file.nameWithoutExtension, entry.password)
        }
    }
}

private fun makeRemoteView(
    context: Context, title: String, summary: String, iconRes: Int
): RemoteViews {
    return RemoteViews(context.packageName, R.layout.oreo_autofill_dataset).apply {
        setTextViewText(R.id.title, title)
        setTextViewText(R.id.summary, summary)
        setImageViewResource(R.id.icon, iconRes)
    }
}

fun makeFillMatchRemoteView(context: Context, file: File, formOrigin: FormOrigin): RemoteViews {
    val title = formOrigin.getPrettyIdentifier(context, indicateTrust = false)
    val summary = file.nameWithoutExtension
    val iconRes = R.drawable.ic_person_black_24dp
    return makeRemoteView(context, title, summary, iconRes)
}

fun makeSearchAndFillRemoteView(context: Context, formOrigin: FormOrigin): RemoteViews {
    val title = formOrigin.getPrettyIdentifier(context, indicateTrust = true)
    val summary = context.getString(R.string.oreo_autofill_search_in_store)
    val iconRes = R.drawable.ic_search_black_24dp
    return makeRemoteView(context, title, summary, iconRes)
}

fun makeGenerateAndFillRemoteView(context: Context, formOrigin: FormOrigin): RemoteViews {
    val title = formOrigin.getPrettyIdentifier(context, indicateTrust = true)
    val summary = context.getString(R.string.oreo_autofill_generate_password)
    val iconRes = R.drawable.ic_autofill_new_password
    return makeRemoteView(context, title, summary, iconRes)
}

fun makePlaceholderRemoteView(context: Context): RemoteViews {
    return makeRemoteView(context, "PLACEHOLDER", "PLACEHOLDER", R.mipmap.ic_launcher)
}

class AutofillSecurityException(message: String) : Exception(message)

fun makeWarningRemoteView(context: Context): RemoteViews {
    val title = "Possible phishing attempt detected"
    val summary = "Tap for details"
    val iconRes = R.drawable.ic_warning_red_24dp
    return makeRemoteView(context, title, summary, iconRes)
}

@RequiresApi(Build.VERSION_CODES.O)
class FixedSaveCallback(context: Context, private val callback: SaveCallback) {

    private val applicationContext = context.applicationContext

    fun onFailure(message: CharSequence) {
        callback.onFailure(message)
        // When targeting SDK 29, the message is no longer shown as a toast.
        // See https://developer.android.com/reference/android/service/autofill/SaveCallback#onFailure(java.lang.CharSequence)
        if (applicationContext.applicationInfo.targetSdkVersion >= 29) {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    fun onSuccess(intentSender: IntentSender) {
        if (Build.VERSION.SDK_INT >= 28) {
            callback.onSuccess(intentSender)
        } else {
            callback.onSuccess()
            // On SDKs < 28, we cannot advise the Autofill framework to launch the save intent in
            // the context of the app that triggered the save request. Hence, we launch it here.
            applicationContext.startIntentSender(intentSender, null, 0, 0, 0)
        }
    }
}
