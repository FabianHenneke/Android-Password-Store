package com.zeapo.pwdstore.autofill.oreo

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Parcelable
import android.service.autofill.Dataset
import android.util.Base64
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import com.google.common.net.InternetDomainName
import com.zeapo.pwdstore.R
import kotlinx.android.parcel.Parcelize
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

fun Context.getPackageVerificationId(packageName: String): String? {
    val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
    val signatureHashes = info.signatures.map { it.toByteArray().sha256() }
    val fullHash = combineHashes(signatureHashes.sortedWith(LexicographicCompare))
    return fullHash.base64()
}

fun getCanonicalDomain(domain: String): String? {
    var idn = InternetDomainName.from(domain)
    while (idn != null && !idn.isTopPrivateDomain)
        idn = idn.parent()
    return idn.toString()
}

data class Credentials(val username: String?, val password: String)

@RequiresApi(Build.VERSION_CODES.O)
@Parcelize
data class PlaceholderDataset(val usernameId: AutofillId?, val passwordIds: List<AutofillId>) : Parcelable {
    fun buildDataset(credentials: Credentials): Dataset {
        return Dataset.Builder().run {
            usernameId?.let { if (credentials.username != null) setValue(it, AutofillValue.forText(credentials.username)) }
            for (passwordId in passwordIds)
                setValue(passwordId, AutofillValue.forText(credentials.password))
            build()
        }
    }
}

fun makeRemoteView(title: String, summary: String, context: Context): RemoteViews {
    return RemoteViews(context.packageName, R.layout.oreo_autofill_dataset).apply {
        setTextViewText(R.id.text1, title)
        setTextViewText(R.id.text2, summary)
    }
}
