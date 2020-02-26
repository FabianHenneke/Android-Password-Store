package com.zeapo.pwdstore.autofill.oreo.ui

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import androidx.core.os.bundleOf
import com.zeapo.pwdstore.PasswordStore
import com.zeapo.pwdstore.autofill.oreo.Credentials
import com.zeapo.pwdstore.autofill.oreo.FormOrigin
import com.zeapo.pwdstore.crypto.PgpActivity
import com.zeapo.pwdstore.utils.PasswordRepository

class AutofillSaveActivity : Activity() {

    companion object {
        private const val TAG = "AutofillStoreActivity"
        private const val EXTRA_FOLDER_NAME = "com.zeapo.pwdstore.autofill.oreo.ui.EXTRA_FOLDER_NAME"
        private const val EXTRA_PASSWORD = "com.zeapo.pwdstore.autofill.oreo.ui.EXTRA_PASSWORD"
        private const val EXTRA_USERNAME = "com.zeapo.pwdstore.autofill.oreo.ui.EXTRA_USERNAME"
        private const val EXTRA_SHOULD_MATCH_APP = "com.zeapo.pwdstore.autofill.oreo.ui.EXTRA_SHOULD_MATCH_APP"
        private const val EXTRA_SHOULD_MATCH_WEB = "com.zeapo.pwdstore.autofill.oreo.ui.EXTRA_SHOULD_MATCH_WEB"

        private var saveRequestCode = 1

        fun makeSaveIntentSender(context: Context, credentials: Credentials, formOrigin: FormOrigin): IntentSender {
            val identifier = formOrigin.getPrettyIdentifier(context, indicateTrust = false)
            val sanitizedIdentifier = identifier.replace("""[\\\/]""", "")
            val folderName = sanitizedIdentifier.takeUnless { it.isBlank() }
                    ?: formOrigin.identifier
            val intent = Intent(context, AutofillSaveActivity::class.java).apply {
                putExtras(bundleOf(
                        EXTRA_FOLDER_NAME to folderName,
                        EXTRA_PASSWORD to credentials.password,
                        EXTRA_USERNAME to credentials.username,
                        EXTRA_SHOULD_MATCH_APP to formOrigin.identifier.takeIf { formOrigin is FormOrigin.App },
                        EXTRA_SHOULD_MATCH_WEB to formOrigin.identifier.takeIf { formOrigin is FormOrigin.Web }
                ))
            }
            return PendingIntent.getActivity(context, saveRequestCode++, intent, PendingIntent.FLAG_CANCEL_CURRENT).intentSender
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = PasswordRepository.getRepositoryDirectory(applicationContext)
        val username: String? = intent.getStringExtra(EXTRA_USERNAME)
        val suggestedExtra = if (username != null) "username: $username" else null

        val saveIntent = Intent(this, PgpActivity::class.java).apply {
            putExtras(bundleOf(
                    "REPO_PATH" to repo.absolutePath,
                    "FILE_PATH" to repo.resolve(intent.getStringExtra(EXTRA_FOLDER_NAME)).absolutePath,
                    "OPERATION" to "ENCRYPT",
                    "SUGGESTED_PASS" to intent.getStringExtra(EXTRA_PASSWORD),
                    "SUGGESTED_EXTRA" to suggestedExtra,
                    "SHOULD_MATCH_APP" to intent.getStringExtra(EXTRA_SHOULD_MATCH_APP),
                    "SHOULD_MATCH_WEB" to intent.getStringExtra(EXTRA_SHOULD_MATCH_WEB)
            ))
        }
        startActivityForResult(saveIntent, PasswordStore.REQUEST_CODE_ENCRYPT)
        finish()
    }
}