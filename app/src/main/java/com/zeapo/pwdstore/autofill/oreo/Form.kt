package com.zeapo.pwdstore.autofill.oreo

import android.app.assist.AssistStructure
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.service.autofill.SaveInfo
import android.util.Log
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import androidx.annotation.RequiresApi
import com.zeapo.pwdstore.autofill.oreo.CertaintyLevel.*
import com.zeapo.pwdstore.autofill.oreo.ui.AutofillDecryptActivity
import com.zeapo.pwdstore.autofill.oreo.ui.AutofillFilterView
import com.zeapo.pwdstore.autofill.oreo.ui.AutofillSaveActivity
import java.io.File
import kotlin.math.abs

private val AUTOFILL_BROWSERS = listOf(
        "org.mozilla.focus",
        "org.mozilla.klar",
        "com.duckduckgo.mobile.android")
private val ACCESSIBILITY_BROWSERS = listOf(
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "com.microsoft.emmx",
        "com.android.chrome",
        "com.chrome.beta",
        "com.android.browser",
        "com.brave.browser",
        "com.opera.browser",
        "com.opera.browser.beta",
        "com.opera.mini.native",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.google.android.apps.chrome",
        "com.google.android.apps.chrome_dev",
        "com.yandex.browser",
        "com.sec.android.app.sbrowser",
        "com.sec.android.app.sbrowser.beta",
        "org.codeaurora.swe.browser",
        "com.amazon.cloud9",
        "mark.via.gp",
        "org.bromite.bromite",
        "org.chromium.chrome",
        "com.kiwibrowser.browser",
        "com.ecosia.android",
        "com.opera.mini.native.beta",
        "org.mozilla.fennec_aurora",
        "org.mozilla.fennec_fdroid",
        "com.qwant.liberty",
        "com.opera.touch",
        "org.mozilla.fenix",
        "org.mozilla.fenix.nightly",
        "org.mozilla.reference.browser",
        "org.mozilla.rocket",
        "org.torproject.torbrowser",
        "com.vivaldi.browser")
private val ALL_BROWSERS = AUTOFILL_BROWSERS + ACCESSIBILITY_BROWSERS

sealed class FormOrigin(open val identifier: String) {
    data class Web(override val identifier: String) : FormOrigin(identifier)
    data class App(override val identifier: String) : FormOrigin(identifier)

    fun getPrettyIdentifier(context: Context, indicateTrust: Boolean = true): String {
        return when (this) {
            is Web -> identifier
            is App -> {
                val info = context.packageManager.getApplicationInfo(identifier, PackageManager.GET_META_DATA)
                val label = context.packageManager.getApplicationLabel(info)
                if (indicateTrust)
                    "“$label”"
                else
                    "$label"
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
class Form(context: Context, structure: AssistStructure) {
    private val TAG = "Form"

    private val fillableFields = mutableListOf<FormField>()
    private val ignoredIds = mutableListOf<AutofillId>()
    private val passwordFields by lazy { identifyPasswordFields() }
    private val usernameField by lazy { identifyUsernameField() }

    private var packageName = structure.activityComponent.packageName
    // TODO: Verify signature
    private val isBrowser = packageName in ALL_BROWSERS
    private var originToFill: String? = null

    val formOrigin by lazy {
        if (isBrowser && originToFill != null) {
            val host = Uri.parse(originToFill!!).host
            if (host != null) {
                val canonicalDomain = getCanonicalDomain(host)
                if (canonicalDomain != null)
                    FormOrigin.Web(canonicalDomain)
                else
                    null
            } else
                null
        } else {
            FormOrigin.App(packageName)
        }
    }
    val canBeFilled by lazy { (usernameField != null || passwordFields.isNotEmpty()) && formOrigin != null }
    // TODO
    val canBeSaved by lazy { passwordFields.isNotEmpty() && formOrigin != null }

    init {
        Log.d(TAG, "Request from $packageName (${computeCertificatesHash(context, packageName)})")
        parseStructure(structure)
        Log.d(TAG, "Username field: $usernameField")
        Log.d(TAG, "Password field(s): $passwordFields")
    }

    private fun parseStructure(structure: AssistStructure) {
        for (i in 0 until structure.windowNodeCount) {
            parseViewNode(structure.getWindowNodeAt(i).rootViewNode)
        }
    }

    private fun parseViewNode(node: AssistStructure.ViewNode) {
        val field = FormField(node)
        // TODO: Improve origin detection by considering iframes and restricting to the list returned by adb shell settings get global autofill_compat_mode_allowed_packages
        if (shouldContinueBasedOnOrigin(node) && field.isFillable) {
            Log.d("Form", "$field")
            fillableFields.add(field)
        } else {
            // Log.d("Form", "Found non-fillable field: $field")
            ignoredIds.add(field.autofillId)
        }

        for (i in 0 until node.childCount) {
            parseViewNode(node.getChildAt(i))
        }
    }

    private fun shouldContinueBasedOnOrigin(node: AssistStructure.ViewNode): Boolean {
        if (!isBrowser)
            return true
        val domain = node.webDomain ?: return originToFill == null
        val scheme = (if (Build.VERSION.SDK_INT >= 28) node.webScheme else null) ?: "https"
        if (scheme !in listOf("http", "https"))
            return false
        val origin = "$scheme://$domain"
        if (originToFill == null) {
            Log.d(TAG, "Origin encountered: $origin")
            originToFill = origin
        }
        if (origin != originToFill) {
            Log.d("Form", "Not same-origin field: ${node.className} with origin $origin")
            return false
        }
        return true
    }

    private fun isSingleFieldOrAdjacentPair(fields: List<FormField>): Boolean {
        if (fields.size == 1)
            return true

        if (fields.size != 2)
            return false
        val id0 = fillableFields.indexOf(fields[0])
        val id1 = fillableFields.indexOf(fields[1])
        return abs(id0 - id1) == 1
    }

    private fun isFocusedOrFollowsFocusedUsernameField(field: FormField): Boolean {
        if (field.isFocused)
            return true

        val ownIndex = fillableFields.indexOf(field)
        if (ownIndex == 0)
            return false
        val potentialUsernameField = fillableFields[ownIndex - 1]
        if (!potentialUsernameField.isFocused || potentialUsernameField.usernameCertainty < Likely)
            return false
        return true
    }

    private fun identifyPasswordFields(): List<FormField> {
        val possiblePasswordFields = fillableFields.filter { it.passwordCertainty >= Possible }
        if (possiblePasswordFields.isEmpty())
            return emptyList()
        val certainPasswordFields = fillableFields.filter { it.passwordCertainty >= Certain }
        if (isSingleFieldOrAdjacentPair(certainPasswordFields))
            return certainPasswordFields
        if (certainPasswordFields.count { isFocusedOrFollowsFocusedUsernameField(it) } == 1)
            return certainPasswordFields.filter { isFocusedOrFollowsFocusedUsernameField(it) }
        val likelyPasswordFields = fillableFields.filter { it.passwordCertainty >= Likely }
        if (isSingleFieldOrAdjacentPair(likelyPasswordFields))
            return likelyPasswordFields
        if (likelyPasswordFields.count { isFocusedOrFollowsFocusedUsernameField(it) } == 1)
            return likelyPasswordFields.filter { isFocusedOrFollowsFocusedUsernameField(it) }
        if (isSingleFieldOrAdjacentPair(possiblePasswordFields))
            return possiblePasswordFields
        return emptyList()
    }

    private fun takeFirstBeforePasswordFields(fields: List<FormField>, alwaysTakeSingleField: Boolean = false): FormField? {
        if (fields.isEmpty())
            return null
        if (fields.size == 1 && alwaysTakeSingleField)
            return fields.first()
        if (passwordFields.isEmpty())
            return null
        val firstPasswordIndex = fillableFields.indexOf(passwordFields.first())
        return fields.last { fillableFields.indexOf(it) < firstPasswordIndex }
    }

    private fun identifyUsernameField(): FormField? {
        val possibleUsernameFields = fillableFields.filter { it.usernameCertainty >= Possible }
        if (possibleUsernameFields.isEmpty())
            return null
        val certainUsernameFields = fillableFields.filter { it.usernameCertainty >= Certain }
        var result = takeFirstBeforePasswordFields(certainUsernameFields, alwaysTakeSingleField = true)
        if (result != null)
            return result
        val likelyUsernameFields = fillableFields.filter { it.usernameCertainty >= Likely }
        result = takeFirstBeforePasswordFields(likelyUsernameFields)
        if (result != null)
            return result
        return takeFirstBeforePasswordFields(possibleUsernameFields)
    }

    private val clientState by lazy {
        Bundle(2).apply {
            putParcelable(BUNDLE_KEY_USERNAME_ID, usernameField?.autofillId)
            putParcelableArrayList(BUNDLE_KEY_PASSWORD_IDS, passwordFields.map { it.autofillId }.toCollection(ArrayList()))
        }
    }

    private fun makeAuthenticationDataset(context: Context, file: File?): Dataset {
        val remoteView = makeRemoteView(context, file, formOrigin!!)
        return Dataset.Builder(remoteView).run {
            if (usernameField != null)
                usernameField!!.fillWith(this, "PLACEHOLDER")
            for (passwordField in passwordFields) {
                passwordField.fillWith(this, "PLACEHOLDER")
            }
            val intent = if (file != null)
                AutofillDecryptActivity.makeDecryptFileIntentSender(file, context)
            else
                AutofillFilterView.makeMatchAndDecryptFileIntentSender(context, formOrigin!!)
            setAuthentication(intent)
            build()
        }
    }

    private fun makeSaveInfo(): SaveInfo? {
        // TODO: Support multi-step authentication flows
        if (passwordFields.isEmpty())
            return null

        val idsToSave = passwordFields.map { it.autofillId }.toMutableList()
        var saveDataTypes = SaveInfo.SAVE_DATA_TYPE_PASSWORD

        usernameField?.let {
            saveDataTypes = saveDataTypes or SaveInfo.SAVE_DATA_TYPE_USERNAME
            idsToSave.add(it.autofillId)
        }

        return SaveInfo.Builder(saveDataTypes, idsToSave.toTypedArray()).run {
            setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
            build()
        }
    }

    fun fillCredentials(context: Context, matchedFiles: List<File>): FillResponse {
        check(canBeFilled)
        return FillResponse.Builder().run {
            for (file in matchedFiles)
                addDataset(makeAuthenticationDataset(context, file))
            addDataset(makeAuthenticationDataset(context, null))
            setClientState(clientState)
            setIgnoredIds(*ignoredIds.toTypedArray())
            val saveInfo = makeSaveInfo()
            if (saveInfo != null)
                setSaveInfo(saveInfo)
            build()
        }
    }

    fun saveCredentials(context: Context): Pair<Boolean, IntentSender?> {
        check(canBeSaved)
        val usernameValue = usernameField?.autofillValue
        val username = if (usernameValue?.isText == true) usernameValue.textValue else null
        val passwordValue = passwordFields.first().autofillValue ?: return Pair(false, null)
        val password = if (passwordValue.isText) passwordValue.textValue else return Pair(false, null)
        // Do not store masked passwords
        if (password.all { it == '*' || it == '•' })
            return Pair(false, null)
        val credentials = Credentials(username?.toString(), password.toString())
        val saveIntentSender = AutofillSaveActivity.makeSaveIntentSender(context, credentials, formOrigin!!)
        return if (Build.VERSION.SDK_INT >= 28) {
            Pair(true, saveIntentSender)
        } else {
            // On SDKs < 28, we cannot advise the Autofill framework to launch the save intent in
            // the context of the app that triggered the save request. Hence, we launch it here.
            context.startIntentSender(saveIntentSender, null, 0, 0, 0)
            Pair(true, null)
        }
    }

    companion object {

        const val BUNDLE_KEY_USERNAME_ID = "usernameId"
        const val BUNDLE_KEY_PASSWORD_IDS = "passwordIds"

        fun makeFillInDataset(context: Context, credentials: Credentials, clientState: Bundle): Dataset {
            val remoteView = makePlaceholderRemoteView(context)
            return Dataset.Builder(remoteView).run {
                val usernameId = clientState.getParcelable<AutofillId>(BUNDLE_KEY_USERNAME_ID)
                if (usernameId != null && credentials.username != null)
                    setValue(usernameId, AutofillValue.forText(credentials.username))
                val passwordIds = clientState.getParcelableArrayList<AutofillId>(BUNDLE_KEY_PASSWORD_IDS)
                if (passwordIds != null) {
                    for (passwordId in passwordIds) {
                        setValue(passwordId, AutofillValue.forText(credentials.password))
                    }
                }
                build()
            }
        }
    }
}
