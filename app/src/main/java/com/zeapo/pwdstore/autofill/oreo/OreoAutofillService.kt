package com.zeapo.pwdstore.autofill.oreo

import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.*
import androidx.annotation.RequiresApi
import com.github.ajalt.timberkt.d
import com.github.ajalt.timberkt.w
import com.zeapo.pwdstore.BuildConfig
import com.zeapo.pwdstore.R

@RequiresApi(Build.VERSION_CODES.O)
class OreoAutofillService : AutofillService() {

    companion object {
        // FIXME: Provide a user-configurable denylist
        private val DENYLISTED_PACKAGES = listOf(
            BuildConfig.APPLICATION_ID,
            "android",
            "com.android.settings",
            "com.android.settings.intelligence",
            "com.android.systemui",
            "com.oneplus.applocker",
            "org.sufficientlysecure.keychain"
        )

        private const val DISABLE_AUTOFILL_DURATION_MS = 1000 * 60 * 60 * 24L
    }

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        if (request.fillContexts.size != 1) d { "Unusual number of fillContexts: ${request.fillContexts.size}" }
        val structureToFill = request.fillContexts.lastOrNull()?.structure
        if (structureToFill == null) {
            callback.onSuccess(null)
            return
        }
        if (structureToFill.activityComponent.packageName in DENYLISTED_PACKAGES) {
            if (Build.VERSION.SDK_INT >= 28) {
                callback.onSuccess(FillResponse.Builder().run {
                    disableAutofill(DISABLE_AUTOFILL_DURATION_MS)
                    build()
                })
            } else {
                callback.onSuccess(null)
            }
            return
        }
        val isManualRequest =
            request.flags and FillRequest.FLAG_MANUAL_REQUEST == FillRequest.FLAG_MANUAL_REQUEST
        val formToFill = Form(this, structureToFill, isManualRequest)
        if (!formToFill.canBeFilled) {
            d { "Form cannot be filled" }
            callback.onSuccess(null)
            return
        }
        formToFill.fillCredentials(this, callback)
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        // SaveCallback's behavior and feature set differs based on both target and device SDK, so
        // we replace it with a wrapper that works the same in all situations.
        @Suppress("NAME_SHADOWING") val callback = FixedSaveCallback(this, callback)
        if (request.fillContexts.size != 1) w { "Unusual number of fillContexts: ${request.fillContexts.size}" }
        val structureToFill = request.fillContexts.lastOrNull()?.structure
        if (structureToFill == null) {
            callback.onFailure(getString(R.string.oreo_autofill_save_app_not_supported))
            return
        }
        val formToFill = Form(this, structureToFill, isManualRequest = false)
        if (!formToFill.canBeSaved) {
            callback.onFailure(getString(R.string.oreo_autofill_save_no_password_field))
            return
        }
        formToFill.saveCredentials(this, callback)
    }
}