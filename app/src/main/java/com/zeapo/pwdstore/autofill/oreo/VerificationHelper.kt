package com.zeapo.pwdstore.autofill.oreo

import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
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