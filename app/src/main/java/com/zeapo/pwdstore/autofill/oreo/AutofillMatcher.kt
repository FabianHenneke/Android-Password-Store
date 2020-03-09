package com.zeapo.pwdstore.autofill.oreo

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.core.content.edit
import com.github.ajalt.timberkt.Timber.e
import com.github.ajalt.timberkt.d
import com.github.ajalt.timberkt.i
import com.github.ajalt.timberkt.w
import com.zeapo.pwdstore.R
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
        private const val MAX_NUM_MATCHES = 10

        private const val PREFERENCE_PREFIX_TOKEN = "token;"
        private fun tokenKey(formOrigin: FormOrigin.App) =
            "$PREFERENCE_PREFIX_TOKEN${formOrigin.identifier}"

        private const val PREFERENCE_PREFIX_MATCHES = "matches;"
        private fun matchesKey(formOrigin: FormOrigin) =
            "$PREFERENCE_PREFIX_MATCHES${formOrigin.identifier}"

        private fun hasFormOriginHashChanged(context: Context, formOrigin: FormOrigin): Boolean {
            return when (formOrigin) {
                is FormOrigin.Web -> false
                is FormOrigin.App -> {
                    val packageName = formOrigin.identifier
                    val certificatesHash = computeCertificatesHash(context, packageName)
                    val storedCertificatesHash =
                        context.autofillAppMatches.getString(tokenKey(formOrigin), null)
                            ?: return false
                    val hashHasChanged = certificatesHash != storedCertificatesHash
                    if (hashHasChanged) {
                        e { "$packageName: stored=$storedCertificatesHash, new=$certificatesHash" }
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
                throw AutofillSecurityException(context.getString(R.string.oreo_autofill_publisher_changed))
            }
            val matchPreferences = context.matchPreferences(formOrigin)
            val matchedFiles =
                matchPreferences.getStringSet(matchesKey(formOrigin), emptySet())!!.map { File(it) }
            return matchedFiles.filter { it.exists() }.also { validFiles ->
                matchPreferences.edit {
                    putStringSet(matchesKey(formOrigin), validFiles.map { it.absolutePath }.toSet())
                }
            }
        }

        fun clearMatchesFor(context: Context, formOrigin: FormOrigin) {
            context.matchPreferences(formOrigin).edit {
                remove(matchesKey(formOrigin))
                if (formOrigin is FormOrigin.App) remove(tokenKey(formOrigin))
            }
        }

        fun addMatchFor(context: Context, formOrigin: FormOrigin, file: File) {
            if (!file.exists()) return
            // FIXME
            if (hasFormOriginHashChanged(context, formOrigin)) {
                throw AutofillSecurityException(context.getString(R.string.oreo_autofill_publisher_changed))
            }
            val matchPreferences = context.matchPreferences(formOrigin)
            val matchedFiles =
                matchPreferences.getStringSet(matchesKey(formOrigin), emptySet())!!.map { File(it) }
            val newFiles = setOf(file.absoluteFile).union(matchedFiles)
            if (newFiles.size > MAX_NUM_MATCHES) {
                Toast.makeText(
                    context,
                    context.getString(R.string.oreo_autofill_max_matches_reached, MAX_NUM_MATCHES),
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            matchPreferences.edit {
                putStringSet(matchesKey(formOrigin), newFiles.map { it.absolutePath }.toSet())
            }
            storeFormOriginHash(context, formOrigin)
            d { "Stored match for $formOrigin" }
        }

        // FIXME: Moving folders?
        fun updateMatchesFor(context: Context, file: File, newFile: File) {
            val oldPath = file.absolutePath
            val newPath = newFile.absolutePath
            for (prefs in listOf(context.autofillAppMatches, context.autofillWebMatches)) {
                for ((key, value) in prefs.all) {
                    if (!key.startsWith(PREFERENCE_PREFIX_MATCHES)) continue
                    val matches = value as? MutableSet<String>
                    if (matches == null) {
                        w { "Failed to read matches for $key" }
                        continue
                    }
                    var shouldCommit = false
                    if (newPath in matches) {
                        matches.remove(newPath)
                        shouldCommit = true
                        i { "Overwriting match for $key: $newPath" }
                    }
                    if (oldPath in matches) {
                        matches.remove(oldPath)
                        matches.add(newPath)
                        shouldCommit = true
                        i { "Updating match for $key: $oldPath --> $newPath" }
                    }
                    if (shouldCommit) {
                        prefs.edit(commit = true) {
                            putStringSet(key, matches)
                        }
                    }
                }
            }
        }
    }
}