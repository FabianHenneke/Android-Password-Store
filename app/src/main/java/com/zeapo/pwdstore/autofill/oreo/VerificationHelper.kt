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

private fun combineHashes(hashes: List<ByteArray>): ByteArray {
    return MessageDigest.getInstance("SHA-256").run {
        for (hash in hashes)
            update(hash)
        digest()
    }
}

fun Context.getPackageVerificationId(packageName: String): String? {
    val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
    val signatureHashes = info.signatures.map { it.toByteArray().sha256() }
    val fullHash = combineHashes(signatureHashes)
    return Base64.encodeToString(fullHash, Base64.NO_WRAP)
}