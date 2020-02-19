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
        val structureToFill = request.fillContexts.lastOrNull()?.structure ?: return
        val formToFill = Form(structureToFill)
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        if (request.fillContexts.size != 1)
            Log.d(TAG, "Unusual number of fillContexts: ${request.fillContexts.size}")
        val structureToFill = request.fillContexts.lastOrNull()?.structure ?: return
        val formToFill = Form(structureToFill)
        return
    }


}