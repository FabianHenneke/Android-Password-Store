package com.zeapo.pwdstore.autofill.oreo

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import android.widget.RemoteViews
import com.google.common.net.InternetDomainName
import com.zeapo.pwdstore.PasswordEntry
import com.zeapo.pwdstore.R
import timber.log.Timber
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

private fun combineHashes(hashes: List<ByteArray>): ByteArray {
    return MessageDigest.getInstance("SHA-256").run {
        for (hash in hashes)
            update(hash)
        digest()
    }
}

object LexicographicCompare : Comparator<ByteArray> {
    override fun compare(a: ByteArray, b: ByteArray): Int {
        val minLength = a.size.coerceAtMost(b.size)
        for (i in 0 until minLength) {
            if (a[i] != b[i])
                return a[i].toInt() - b[i].toInt()
        }
        return a.size - b.size
    }
}

fun Context.makePackageSignatureToken(packageName: String): String {
    val signatures = if (Build.VERSION.SDK_INT >= 28) {
        val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        info.signingInfo.signingCertificateHistory ?: info.signingInfo.apkContentsSigners
    } else {
        packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
    }
    val signatureHashes = signatures.map { it.toByteArray().sha256() }
    val fullHash = combineHashes(signatureHashes.sortedWith(LexicographicCompare))
    return fullHash.base64().also { newHash ->
        // FIXME: Remove after testing
        val oldSignatures = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        val oldHashes = oldSignatures.map { it.toByteArray().sha256() }
        val oldHash = combineHashes(oldHashes.sortedWith(LexicographicCompare)).base64()
        if (oldHash != newHash)
            Timber.tag("SignatureToken").e("Mismatch: $oldHash != $newHash")
    }
}

fun getCanonicalDomain(domain: String): String? {
    var idn = InternetDomainName.from(domain)
    while (idn != null && !idn.isTopPrivateDomain)
        idn = idn.parent()
    return idn.toString()
}

data class Credentials(val username: String?, val password: String) {
    companion object {
        fun fromStoreEntry(file: File, entry: PasswordEntry): Credentials {
            if (entry.hasUsername())
                return Credentials(entry.username, entry.password)

            val filename = file.nameWithoutExtension
            return if (filename.contains("@") || !filename.contains(" "))
                Credentials(filename, entry.password)
            else
                Credentials(null, entry.password)
        }
    }
}

private fun makeRemoteView(context: Context, title: String, summary: String): RemoteViews {
    return RemoteViews(context.packageName, R.layout.oreo_autofill_dataset).apply {
        setTextViewText(R.id.text1, title)
        setTextViewText(R.id.text2, summary)
    }
}

fun makeRemoteView(context: Context, file: File?, formOrigin: FormOrigin): RemoteViews {
    val title: String
    val summary: String
    if (file != null) {
        title = formOrigin.getPrettyIdentifier(context, indicateTrust = false)
        summary = file.nameWithoutExtension
    } else {
        title = formOrigin.getPrettyIdentifier(context, indicateTrust = true)
        summary = "Search in store..."
    }
    return makeRemoteView(context, title, summary)
}

fun makePlaceholderRemoteView(context: Context): RemoteViews {
    return makeRemoteView(context, "PLACEHOLDER", "PLACEHOLDER")
}
