package com.zeapo.pwdstore.autofill.oreo

import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.*
import android.util.Log
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.O)
class OreoAutofillService : AutofillService() {
    private val TAG = "OreoAutofillService"

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        if (request.fillContexts.size != 1)
            Log.d(TAG, "Unusual number of fillContexts: ${request.fillContexts.size}")
        val structureToFill = request.fillContexts.lastOrNull()?.structure
        if (structureToFill == null) {
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
        callback.onSuccess(formToFill.fillWith("John Doe", "hunter2", this))
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


}