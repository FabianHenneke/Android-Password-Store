package com.zeapo.pwdstore.autofill.oreo.ui

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.view.autofill.AutofillManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.zeapo.pwdstore.PasswordEntry
import com.zeapo.pwdstore.autofill.oreo.Credentials
import com.zeapo.pwdstore.autofill.oreo.Form
import kotlinx.coroutines.*
import me.msfjarvis.openpgpktx.util.OpenPgpApi
import me.msfjarvis.openpgpktx.util.OpenPgpServiceConnection
import org.openintents.openpgp.IOpenPgpService2
import org.openintents.openpgp.OpenPgpError
import timber.log.Timber
import java.io.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@RequiresApi(Build.VERSION_CODES.O)
class DecryptActivity : Activity(), CoroutineScope {

    var continueAfterUserInteraction: Continuation<Intent>? = null

    override val coroutineContext
        get() = Dispatchers.IO + SupervisorJob()

    override fun onStart() {
        super.onStart()
        val filePath = intent?.getStringExtra(EXTRA_FILE_PATH) ?: run {
            Timber.tag(TAG).e("DecryptActivity started without EXTRA_FILE_PATH")
            finish()
            return
        }
        val clientState = intent?.getBundleExtra(AutofillManager.EXTRA_CLIENT_STATE) ?: run {
            Timber.tag(TAG).e("DecryptActivity started without EXTRA_CLIENT_STATE")
            finish()
            return
        }
        launch {
            val credentials = decryptUsernameAndPassword(File(filePath))
            if (credentials == null) {
                setResult(RESULT_CANCELED)
            } else {
                val fillInDataset = Form.makeFillInDataset(credentials, clientState, this@DecryptActivity)
                // TODO: Use filename if username is null?
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "${credentials.username}/${credentials.password}", Toast.LENGTH_LONG).show()
                    setResult(RESULT_OK, Intent().apply {
                        putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, fillInDataset)
                    })
                }
            }
            withContext(Dispatchers.Main) {
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineContext.cancelChildren()
    }

    private suspend fun executeOpenPgpApi(data: Intent, input: InputStream, output: OutputStream): Intent? {
        var openPgpServiceConnection: OpenPgpServiceConnection? = null
        val openPgpService = suspendCoroutine<IOpenPgpService2> { cont ->
            openPgpServiceConnection = OpenPgpServiceConnection(this, OPENPGP_PROVIDER,
                    object : OpenPgpServiceConnection.OnBound {
                        override fun onBound(service: IOpenPgpService2) {
                            cont.resume(service)
                        }

                        override fun onError(e: Exception) {
                            cont.resumeWithException(e)
                        }
                    }).also { it.bindToService() }
        }
        return OpenPgpApi(this, openPgpService).executeApi(data, input, output).also {
            openPgpServiceConnection?.unbindFromService()
        }
    }

    private suspend fun decryptUsernameAndPassword(file: File, resumeIntent: Intent? = null): Credentials? {
        val command = resumeIntent ?: Intent().apply {
            action = OpenPgpApi.ACTION_DECRYPT_VERIFY
        }
        // TODO catch
        val encryptedInput = file.inputStream()
        val decryptedOutput = ByteArrayOutputStream()
        // TODO catch
        val result = executeOpenPgpApi(command, encryptedInput, decryptedOutput)
        return when (val resultCode = result?.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR)) {
            OpenPgpApi.RESULT_CODE_SUCCESS -> {
                try {
                    val entry = withContext(Dispatchers.IO) {
                        PasswordEntry(decryptedOutput)
                    }
                    if (entry.hasUsername()) {
                        Credentials(entry.username, entry.password)
                    } else {
                        Credentials(null, entry.password)
                    }
                } catch (e: UnsupportedEncodingException) {
                    Timber.tag(TAG).e(e)
                    null
                }
            }
            OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED -> {
                Timber.tag("PgpHandler").i("RESULT_CODE_USER_INTERACTION_REQUIRED")
                val pendingIntent: PendingIntent = result.getParcelableExtra(OpenPgpApi.RESULT_INTENT)
                try {
                    val intentToResume = withContext(Dispatchers.Main) {
                        suspendCoroutine<Intent> { cont ->
                            continueAfterUserInteraction = cont
                            startIntentSenderForResult(
                                    pendingIntent.intentSender,
                                    REQUEST_CODE_CONTINUE_AFTER_USER_INTERACTION,
                                    null,
                                    0,
                                    0,
                                    0)
                        }
                    }
                    decryptUsernameAndPassword(file, intentToResume)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e)
                    null
                }
            }
            OpenPgpApi.RESULT_CODE_ERROR -> {
                val error = result.getParcelableExtra<OpenPgpError>(OpenPgpApi.RESULT_ERROR)
                if (error != null) {
                    Toast.makeText(applicationContext, "Error from OpenKeyChain: ${error.message}", Toast.LENGTH_LONG).show()
                    Timber.tag(TAG).e("onError getErrorId: ${error.errorId}")
                    Timber.tag(TAG).e("onError getMessage: ${error.message}")
                }
                null
            }
            else -> {
                Timber.tag(TAG).e("Unrecognized OpenPgpApi result: $resultCode")
                null
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_CONTINUE_AFTER_USER_INTERACTION && continueAfterUserInteraction != null) {
            if (resultCode == RESULT_OK && data != null) {
                continueAfterUserInteraction?.resume(data)
            } else {
                continueAfterUserInteraction?.resumeWithException(Exception("OpenPgpApi failed to continue after user interaction"))
            }
            continueAfterUserInteraction = null
        }
    }

    companion object {
        var decryptFileRequestCode = 1
        fun makeDecryptFileIntentSender(file: File, context: Context): IntentSender {
            val intent = Intent(context, DecryptActivity::class.java).apply {
                putExtra(EXTRA_FILE_PATH, file.absolutePath)
            }
            return PendingIntent.getActivity(context, decryptFileRequestCode++, intent, PendingIntent.FLAG_CANCEL_CURRENT).intentSender
        }

        private const val TAG = "DecryptActivity"
        private const val EXTRA_FILE_PATH = "com.zeapo.pwdstore.autofill.oreo.EXTRA_FILE_PATH"
        private const val REQUEST_CODE_CONTINUE_AFTER_USER_INTERACTION = 1
        private const val OPENPGP_PROVIDER = "org.sufficientlysecure.keychain"
    }

}