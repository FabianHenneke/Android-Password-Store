package com.zeapo.pwdstore.autofill.oreo

import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.*
import androidx.annotation.RequiresApi
import com.github.ajalt.timberkt.d
import com.github.ajalt.timberkt.e
import com.github.ajalt.timberkt.w
import com.zeapo.pwdstore.BuildConfig
import com.zeapo.pwdstore.R
import com.zeapo.pwdstore.autofill.oreo.ui.AutofillSaveActivity

@RequiresApi(Build.VERSION_CODES.O)
class OreoAutofillService : AutofillService() {

    companion object {
        // TODO: Provide a user-configurable denylist
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
        val formToFill = FillableForm.parseAssistStructure(this, structureToFill, isManualRequest) ?: run {
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
        val structure = request.fillContexts.lastOrNull()?.structure ?: run {
            callback.onFailure(getString(R.string.oreo_autofill_save_app_not_supported))
            return
        }
        val clientState = request.clientState ?: run {
            e { "Received save request without client state" }
            callback.onFailure(getString(R.string.oreo_autofill_save_internal_error))
            return
        }
        val scenario = AutofillScenario.fromClientState(clientState).recoverNodes(structure) ?: run {
            e { "Failed to recover nodes from Autofill IDs" }
            callback.onFailure(getString(R.string.oreo_autofill_save_internal_error))
            return
        }

        val usernameValue = scenario.username?.autofillValue
        val username = if (usernameValue?.isText == true) usernameValue.textValue else null
        val passwordValue = scenario.fieldsToSave
        if (passwordValue == null) {
            callback.onFailure(context.getString(R.string.oreo_autofill_save_invalid_password))
            return
        }
        val password = if (passwordValue.isText) {
            passwordValue.textValue
        } else {
            callback.onFailure(context.getString(R.string.oreo_autofill_save_invalid_password))
            return
        }
        // Do not store masked passwords
        if (password.all { it == '*' || it == '•' }) {
            callback.onFailure(context.getString(R.string.oreo_autofill_save_invalid_password))
            return
        }
        val credentials = Credentials(username?.toString(), password.toString())
        callback.onSuccess(
            AutofillSaveActivity.makeSaveIntentSender(
                context, credentials, formOrigin!!
            )
        )
    }
}