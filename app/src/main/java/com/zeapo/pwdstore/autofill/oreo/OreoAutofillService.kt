package com.zeapo.pwdstore.autofill.oreo

import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.*
import android.util.Log
import androidx.annotation.RequiresApi
import com.zeapo.pwdstore.BuildConfig
import com.zeapo.pwdstore.R

private enum class SaveDenylistStatus(val value: Int) {
    Default(0),
    Cancelled(1),
    Denied(2);
}

@RequiresApi(Build.VERSION_CODES.O)
class OreoAutofillService : AutofillService() {

    companion object {
        private const val TAG = "OreoAutofillService"
        // FIXME: Provide a user-configurable denylist
        private val DENYLISTED_PACKAGES = listOf(
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
        if (structureToFill == null || structureToFill.activityComponent.packageName in DENYLISTED_PACKAGES) {
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
        formToFill.fillCredentials(this, matchedFiles, callback)
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        Log.d(TAG, "onSaveRequest() called")
        // SaveCallback's behavior and feature set differs based on both target and device SDK, so
        // we replace it with a wrapper that works the same in all situations.
        @Suppress("NAME_SHADOWING") val callback = FixedSaveCallback(this, callback)
        if (request.fillContexts.size != 1)
            Log.d(TAG, "Unusual number of fillContexts: ${request.fillContexts.size}")
        val structureToFill = request.fillContexts.lastOrNull()?.structure
        if (structureToFill == null) {
            callback.onFailure(getString(R.string.oreo_autofill_save_app_not_supported))
            return
        }
        val formToFill = Form(this, structureToFill)
        if (!formToFill.canBeSaved) {
            callback.onFailure(getString(R.string.oreo_autofill_save_no_password_field))
            return
        }
        formToFill.saveCredentials(this, callback)
    }
}