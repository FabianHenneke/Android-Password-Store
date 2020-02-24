package com.zeapo.pwdstore.autofill.oreo

import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.*
import android.util.Log
import androidx.annotation.RequiresApi
import com.zeapo.pwdstore.BuildConfig
import com.zeapo.pwdstore.utils.PasswordRepository
import java.io.File

@RequiresApi(Build.VERSION_CODES.O)
class OreoAutofillService : AutofillService() {
    private val TAG = "OreoAutofillService"

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        if (request.fillContexts.size != 1)
            Log.d(TAG, "Unusual number of fillContexts: ${request.fillContexts.size}")
        val structureToFill = request.fillContexts.lastOrNull()?.structure
        if (structureToFill == null || structureToFill.activityComponent.packageName in BLACKLISTED_PACKAGES) {
            callback.onSuccess(null)
            return
        }
        val formToFill = Form(structureToFill, this)
        if (!formToFill.canBeFilled) {
            Log.d(TAG, "Form cannot be filled")
            callback.onSuccess(null)
            return
        }
        Log.d(TAG, "Sending a FillResponse")
        val file1 = File(PasswordRepository.getRepositoryDirectory(applicationContext).absolutePath + "/john@doe.org.gpg")
        val file2 = File(PasswordRepository.getRepositoryDirectory(applicationContext).absolutePath + "/jane@doe.org.gpg")
        callback.onSuccess(formToFill.fillWithAfterDecryption(listOf(file1, file2), this))
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        if (request.fillContexts.size != 1)
            Log.d(TAG, "Unusual number of fillContexts: ${request.fillContexts.size}")
        val structureToFill = request.fillContexts.lastOrNull()?.structure
        if (structureToFill == null) {
            callback.onFailure("Couldn't save")
            return
        }
        val formToFill = Form(structureToFill, this)
        if (!formToFill.canBeFilled) {
            callback.onFailure("Couldn't save")
            return
        }
        callback.onFailure("Couldn't save")
    }

    companion object {
        private val BLACKLISTED_PACKAGES = listOf(
                BuildConfig.APPLICATION_ID,
                "android",
                "com.android.settings",
                "com.android.systemui",
                "com.oneplus.applocker",
                "org.sufficientlysecure.keychain"
        )
    }
}