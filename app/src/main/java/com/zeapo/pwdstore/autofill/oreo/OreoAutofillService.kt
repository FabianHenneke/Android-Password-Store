package com.zeapo.pwdstore.autofill.oreo

import android.annotation.SuppressLint
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.*
import android.util.Log
import androidx.annotation.RequiresApi
import com.zeapo.pwdstore.BuildConfig

@RequiresApi(Build.VERSION_CODES.O)
class OreoAutofillService : AutofillService() {

    companion object {
        private const val TAG = "OreoAutofillService"
        // FIXME: Add a configurable blacklist
        private val BLACKLISTED_PACKAGES = listOf(
                BuildConfig.APPLICATION_ID,
                "android",
                "com.android.settings",
                "com.android.systemui",
                "com.android.vending",
                "com.oneplus.applocker",
                "org.sufficientlysecure.keychain"
        )
    }

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        if (request.fillContexts.size != 1)
            Log.d(TAG, "Unusual number of fillContexts: ${request.fillContexts.size}")
        val structureToFill = request.fillContexts.lastOrNull()?.structure
        if (structureToFill == null || structureToFill.activityComponent.packageName in BLACKLISTED_PACKAGES) {
            callback.onSuccess(null)
            return
        }
        val formToFill = Form(this, structureToFill)
        if (!formToFill.canBeFilled) {
            Log.d(TAG, "Form cannot be filled")
            callback.onSuccess(null)
            return
        }
        Log.d(TAG, "Sending a FillResponse")
        val matchedFiles = AutofillMatcher.getMatchesFor(applicationContext, formToFill.formOrigin!!)
        callback.onSuccess(formToFill.fillCredentials(this, matchedFiles))
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        Log.d(TAG, "onSaveRequest() called")
        if (request.fillContexts.size != 1)
            Log.d(TAG, "Unusual number of fillContexts: ${request.fillContexts.size}")
        val structureToFill = request.fillContexts.lastOrNull()?.structure
        if (structureToFill == null) {
            callback.onFailure("Failed to save credentials")
            return
        }
        val formToFill = Form(this, structureToFill)
        if (!formToFill.canBeSaved) {
            callback.onFailure("Failed to save credentials")
            return
        }
        val (couldSave, intent) = formToFill.saveCredentials(this)
        if (couldSave) {
            if (intent != null) {
                if (Build.VERSION.SDK_INT >= 28) {
                    callback.onSuccess(intent)
                } else {
                    throw IllegalStateException("saveCredentials returned intent, but SDK_INT < 28")
                }
            } else {
                callback.onSuccess()
            }
        } else {
            callback.onFailure("Failed to save credentials")
        }
    }
}