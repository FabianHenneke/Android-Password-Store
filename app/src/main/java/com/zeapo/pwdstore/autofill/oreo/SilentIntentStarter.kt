package com.zeapo.pwdstore.autofill.oreo

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.IntentSender
import android.os.ResultReceiver
import com.zeapo.pwdstore.autofill.AutofillActivity
import com.zeapo.pwdstore.autofill.AutofillService
import timber.log.Timber

const val EXTRA_PENDING_INTENT_RESULT_RECEIVER =
        "com.zeapo.pwdstore.autofill.oreo.EXTRA_PENDING_INTENT_RESULT_RECEIVER"
const val EXTRA_PENDING_INTENT =
        "com.zeapo.pwdstore.autofill.oreo.EXTRA_PENDING_INTENT"

class SilentPendingIntentStarter : Activity() {

    private val TAG = "SilentPendingIntentStarter"

    private lateinit var pendingIntent: PendingIntent
    private lateinit var resultReceiver: ResultReceiver

    override fun onStart() {
        super.onStart()
        pendingIntent = intent?.extras?.getParcelable(EXTRA_PENDING_INTENT) ?: run {
            finish()
            return
        }
        resultReceiver = intent?.extras?.getParcelable(EXTRA_PENDING_INTENT_RESULT_RECEIVER) ?: run {
            finish()
            return
        }
        try {
            startIntentSenderForResult(pendingIntent.intentSender, 0, null, 0, 0, 0)
        } catch (e: IntentSender.SendIntentException) {
            Timber.tag(TAG).e(e, "SendIntentException")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            super.onActivityResult(requestCode, resultCode, data)
            if (requestCode == REQUEST_CONFIRM_CREDENTIAL)
                returnResult(resultCode)
        }

        private fun returnResult(resultCode: Int) {
            intent.getParcelableExtra<ResultReceiver>(EXTRA_CONFIRM_DEVICE_CREDENTIAL_RECEIVER)
                    ?.send(resultCode, Bundle())
            finish()
        }
    }

}