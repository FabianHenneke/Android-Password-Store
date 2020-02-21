package com.zeapo.pwdstore.autofill.oreo

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Parcel
import android.os.Parcelable
import android.service.autofill.Dataset
import android.util.Base64
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import androidx.annotation.RequiresApi
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

@RequiresApi(Build.VERSION_CODES.O)
@Parcelize
data class PlaceholderDataset(val usernameId: AutofillId?, val passwordIds: List<AutofillId>) : Parcelable {
    fun buildDataset(credentials: Pair<String?, String>): Dataset {
        return Dataset.Builder().run {
            usernameId?.let { if (credentials.first != null) setValue(it, AutofillValue.forText(credentials.first)) }
            for (passwordId in passwordIds)
                setValue(passwordId, AutofillValue.forText(credentials.second))
            build()
        }
    }
}
