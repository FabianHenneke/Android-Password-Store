package com.zeapo.pwdstore.autofill.oreo

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.core.content.edit
import timber.log.Timber
import java.io.File


private const val PREFERENCES_AUTOFILL_APP_MATCHES = "oreo_autofill_app_matches"
private val Context.autofillAppMatches
    get() = getSharedPreferences(PREFERENCES_AUTOFILL_APP_MATCHES, Context.MODE_PRIVATE)

private const val PREFERENCES_AUTOFILL_WEB_MATCHES = "oreo_autofill_web_matches"
private val Context.autofillWebMatches
    get() = getSharedPreferences(PREFERENCES_AUTOFILL_WEB_MATCHES, Context.MODE_PRIVATE)

private fun Context.matchPreferences(formOrigin: FormOrigin): SharedPreferences {
    return when (formOrigin) {
        is FormOrigin.App -> autofillAppMatches
        is FormOrigin.Web -> autofillWebMatches
    }
}

class AutofillMatcher {
    companion object {
        private const val TAG = "AutofillMatcher"
        private const val MAX_NUM_MATCHES = 5

        private fun tokenKey(formOrigin: FormOrigin.App) = "token;${formOrigin.identifier}"
        private fun matchesKey(formOrigin: FormOrigin) = "matches;${formOrigin.identifier}"

        private fun hasFormOriginHashChanged(context: Context, formOrigin: FormOrigin): Boolean {
            return when (formOrigin) {
                is FormOrigin.Web -> false
                is FormOrigin.App -> {
                    val packageName = formOrigin.identifier
                    val certificatesHash = computeCertificatesHash(context, packageName)
                    val storedCertificatesHash = context.autofillAppMatches.getString(tokenKey(formOrigin), null)
                            ?: return false
                    val hashHasChanged = certificatesHash != storedCertificatesHash
                    if (hashHasChanged) {
                        Timber.tag(TAG).e("$packageName: stored=$storedCertificatesHash, new=$certificatesHash")
                        true
                    } else {
                        false
                    }
                }
            }
        }

        private fun storeFormOriginHash(context: Context, formOrigin: FormOrigin) {
            if (formOrigin is FormOrigin.App) {
                val packageName = formOrigin.identifier
                val certificatesHash = computeCertificatesHash(context, packageName)
                context.autofillAppMatches.edit {
                    putString(tokenKey(formOrigin), certificatesHash)
                }
            }
            // We don't need to store a hash for FormOrigin.Web since it can only originate from
            // browsers we trust to verify the origin.
        }

        fun getMatchesFor(context: Context, formOrigin: FormOrigin): List<File> {
            if (hasFormOriginHashChanged(context, formOrigin)) {
                Toast.makeText(context, "The app's publisher has changed; this may be a phishing attempt.", Toast.LENGTH_LONG).show()
                return emptyList()
            }
            val matchPreferences = context.matchPreferences(formOrigin)
            val matchedFiles = matchPreferences.getStringSet(matchesKey(formOrigin), emptySet())!!.map { File(it) }
            return matchedFiles.filter { it.exists() }.also { validFiles ->
                matchPreferences.edit {
                    putStringSet(matchesKey(formOrigin), validFiles.map { it.absolutePath }.toSet())
                }
            }
        }

        fun clearMatchesFor(context: Context, formOrigin: FormOrigin) {
            context.matchPreferences(formOrigin).edit {
                remove(matchesKey(formOrigin))
                if (formOrigin is FormOrigin.App)
                    remove(tokenKey(formOrigin))
            }
        }

        fun addMatchFor(context: Context, formOrigin: FormOrigin, file: File) {
            if (!file.exists())
                return
            if (hasFormOriginHashChanged(context, formOrigin)) {
                Toast.makeText(context, "The app's publisher has changed; this may be a phishing attempt.", Toast.LENGTH_LONG).show()
                return
            }
            val matchPreferences = context.matchPreferences(formOrigin)
            val matchedFiles = matchPreferences.getStringSet(matchesKey(formOrigin), emptySet())!!.map { File(it) }
            val newFiles = setOf(file.absoluteFile).union(matchedFiles)
            if (newFiles.size > MAX_NUM_MATCHES) {
                Toast.makeText(context, "Maximum number of matches ($MAX_NUM_MATCHES) reached; clear matches before adding new ones", Toast.LENGTH_LONG).show()
                return
            }
            matchPreferences.edit {
                putStringSet(matchesKey(formOrigin), newFiles.map { it.absolutePath }.toSet())
            }
            storeFormOriginHash(context, formOrigin)
        }
    }
}