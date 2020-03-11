package com.zeapo.pwdstore.autofill.oreo

import android.app.assist.AssistStructure
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillResponse
import android.service.autofill.SaveInfo
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import com.github.ajalt.timberkt.d
import com.github.ajalt.timberkt.e
import com.zeapo.pwdstore.R
import com.zeapo.pwdstore.autofill.oreo.CertaintyLevel.*
import com.zeapo.pwdstore.autofill.oreo.ui.AutofillDecryptActivity
import com.zeapo.pwdstore.autofill.oreo.ui.AutofillFilterView
import com.zeapo.pwdstore.autofill.oreo.ui.AutofillPublisherChangedActivity
import com.zeapo.pwdstore.autofill.oreo.ui.AutofillSaveActivity
import java.io.File
import kotlin.math.abs

private val AUTOFILL_BROWSERS = listOf(
    "org.mozilla.focus", "org.mozilla.klar", "com.duckduckgo.mobile.android"
)
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
    "com.vivaldi.browser"
)
private val ALL_BROWSERS = AUTOFILL_BROWSERS + ACCESSIBILITY_BROWSERS

sealed class FormOrigin(open val identifier: String) {
    data class Web(override val identifier: String) : FormOrigin(identifier)
    data class App(override val identifier: String) : FormOrigin(identifier)

    fun getPrettyIdentifier(context: Context, untrusted: Boolean = true): String {
        return when (this) {
            is Web -> identifier
            is App -> {
                val info = context.packageManager.getApplicationInfo(
                    identifier, PackageManager.GET_META_DATA
                )
                val label = context.packageManager.getApplicationLabel(info)
                if (untrusted) "“$label”" else "$label"
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
// FIXME: Use manual request?
class Form(context: Context, structure: AssistStructure, private val isManualRequest: Boolean) {

    companion object {

        const val BUNDLE_KEY_USERNAME_ID = "usernameId"
        const val BUNDLE_KEY_PASSWORD_IDS = "passwordIds"

        fun makeFillInDataset(
            context: Context, credentials: Credentials, clientState: Bundle
        ): Dataset {
            val remoteView = makePlaceholderRemoteView(context)
            return Dataset.Builder(remoteView).run {
                val usernameId = clientState.getParcelable<AutofillId>(BUNDLE_KEY_USERNAME_ID)
                if (usernameId != null && credentials.username != null) setValue(
                    usernameId, AutofillValue.forText(credentials.username)
                )
                val passwordIds =
                    clientState.getParcelableArrayList<AutofillId>(BUNDLE_KEY_PASSWORD_IDS)
                if (passwordIds != null) {
                    for (passwordId in passwordIds) {
                        setValue(passwordId, AutofillValue.forText(credentials.password))
                    }
                }
                build()
            }
        }

        private val SUPPORTED_SCHEMES = listOf("http", "https")
    }

    private val fillableFields = mutableListOf<FormField>()
    private val ignoredIds = mutableListOf<AutofillId>()
    private var fieldIndex = 0

    private var appPackage = structure.activityComponent.packageName
    // FIXME
    //    private val isBrowser = isTrustedBrowser(context, packageName)
    private val isBrowser = appPackage in ALL_BROWSERS
    // FIXME
    //    private val isBrowserSupportingSave = isBrowser && isBrowserWithSaveSupport(packageName)
    private val isBrowserSupportingSave = isBrowser
    private val isBrowserSupportingMultiOrigin = isBrowser && isBrowserWithMultiOriginSupport(appPackage)
    private val singleOriginMode = isBrowser && !isBrowserSupportingMultiOrigin

    private val webOrigins = mutableListOf<String>()

    init {
        d { "Request from $appPackage (${computeCertificatesHash(context, appPackage)})" }
        parseStructure(structure)
    }

    private val scenario = detectFieldsToFill()

    init {
        d { "Username field: ${scenario?.username}" }
        d { "Generic password field(s): ${(scenario as? GenericAutofillScenario)?.genericPassword}" }
        d { "Current password field(s): ${(scenario as? PreciseAutofillScenario)?.currentPassword}" }
        d { "New password field(s): ${(scenario as? PreciseAutofillScenario)?.newPassword}" }
    }

    private val formOrigin = determineFormOrigin()

    init {
        d { "Origin: $formOrigin" }
    }

    val canBeFilled = scenario != null && formOrigin != null
    // FIXME
    val canBeSaved = formOrigin != null && (!isBrowser || isBrowserSupportingSave)

    private val clientState by lazy {
        check(scenario != null)
        scenario.toAutofillScenarioIds().toClientState()
    }

    private fun parseStructure(structure: AssistStructure) {
        for (i in 0 until structure.windowNodeCount) {
            parseViewNode(structure.getWindowNodeAt(i).rootViewNode)
        }
    }

    private fun parseViewNode(node: AssistStructure.ViewNode) {
        trackOrigin(node)
        val field = FormField(node, fieldIndex)
        // FIXME: Improve origin detection by considering iframes and restricting to the list returned by adb shell settings get global autofill_compat_mode_allowed_packages
        if (field.isFillable) {
            d { "$field" }
            fillableFields.add(field)
            fieldIndex++
        } else {
            d { "Found non-fillable field: $field" }
            ignoredIds.add(field.autofillId)
        }

        for (i in 0 until node.childCount) {
            parseViewNode(node.getChildAt(i))
        }
    }

    private fun trackOrigin(node: AssistStructure.ViewNode) {
        if (!isBrowser) return
        node.webOrigin?.let {
            if (it !in webOrigins) {
                d { "Origin encountered: $it" }
                webOrigins.add(it)
            }
        }
    }

    private fun isSingleFieldOrAdjacentPair(fields: List<FormField>): Boolean {
        if (fields.size == 1) return true

        if (fields.size != 2) return false
        val id0 = fillableFields.indexOf(fields[0])
        val id1 = fillableFields.indexOf(fields[1])
        return abs(id0 - id1) == 1
    }

    private fun isFocusedOrFollowsFocusedUsernameField(field: FormField): Boolean {
        if (field.isFocused) return true

        val ownIndex = fillableFields.indexOf(field)
        if (ownIndex == 0) return false
        val potentialUsernameField = fillableFields[ownIndex - 1]
        if (!potentialUsernameField.isFocused || potentialUsernameField.usernameCertainty < Likely) return false
        return true
    }

    private fun identifyPasswordFields(): List<FormField> {
        val possiblePasswordFields = fillableFields.filter { it.passwordCertainty >= Possible }
        if (possiblePasswordFields.isEmpty()) return emptyList()
        val certainPasswordFields = fillableFields.filter { it.passwordCertainty >= Certain }
        if (isSingleFieldOrAdjacentPair(certainPasswordFields)) return certainPasswordFields
        if (certainPasswordFields.count { isFocusedOrFollowsFocusedUsernameField(it) } == 1) return certainPasswordFields.filter {
            isFocusedOrFollowsFocusedUsernameField(
                it
            )
        }
        val likelyPasswordFields = fillableFields.filter { it.passwordCertainty >= Likely }
        if (isSingleFieldOrAdjacentPair(likelyPasswordFields)) return likelyPasswordFields
        if (likelyPasswordFields.count { isFocusedOrFollowsFocusedUsernameField(it) } == 1) return likelyPasswordFields.filter {
            isFocusedOrFollowsFocusedUsernameField(
                it
            )
        }
        if (isSingleFieldOrAdjacentPair(possiblePasswordFields)) return possiblePasswordFields
        return emptyList()
    }

    private fun takeFieldRightBeforePasswordFields(
        fields: List<FormField>, alwaysTakeSingleField: Boolean = false
    ): FormField? {
        if (fields.isEmpty()) return null
        if (fields.size == 1 && alwaysTakeSingleField) return fields.single()
        if (passwordFields.isEmpty()) return null
        val firstPasswordIndex = fillableFields.indexOf(passwordFields.first())
        val potentialUsernameIndex = firstPasswordIndex - 1
        if (potentialUsernameIndex < 0) return null
        val potentialUsernameField = fillableFields[potentialUsernameIndex]
        return potentialUsernameField.takeIf { it in fields }
    }

    private fun identifyUsernameField(): FormField? {
        val possibleUsernameFields = fillableFields.filter { it.usernameCertainty >= Possible }
        if (possibleUsernameFields.isEmpty()) return null
        val certainUsernameFields = fillableFields.filter { it.usernameCertainty >= Certain }
        var result =
            takeFieldRightBeforePasswordFields(certainUsernameFields, alwaysTakeSingleField = true)
        if (result != null) return result
        val likelyUsernameFields = fillableFields.filter { it.usernameCertainty >= Likely }
        result = takeFieldRightBeforePasswordFields(likelyUsernameFields)
        if (result != null) return result
        return takeFieldRightBeforePasswordFields(possibleUsernameFields)
    }

    private fun webOriginToFormOrigin(origin: String): FormOrigin? {
        val uri = Uri.parse(origin) ?: return null
        val scheme = uri.scheme ?: return null
        if (scheme !in SUPPORTED_SCHEMES) return null
        val host = uri.host ?: return null
        val canonicalDomain = getCanonicalDomain(host) ?: return null
        return FormOrigin.Web(canonicalDomain)
    }

    private fun determineFormOrigin(): FormOrigin? {
        return if (!isBrowser || webOrigins.isEmpty()) {
            FormOrigin.App(appPackage)
        } else if (webOrigins.size == 1) {
            // FIXME
            // Security assumption on trusted browsers:
            // Every origin that contributes fillable fields to a web page appears at least once as
            // the webDomain of some ViewNode (but not necessarily one that represents a fillable
            // field).
            // It is thus safe to fill into any fillable field if there is only a single origin.
            webOriginToFormOrigin(webOrigins.single())
        } else {
            // Based on our assumption above, if there are nodes from multiple origins on a page,
            // we can only safely fill if the fields to fill are all explicitly labeled with the
            // same origin.
            val fieldsToFill = passwordFields.toMutableList()
            usernameField?.let { fieldsToFill.add(it) }
            val originsAmongFieldsToFill = fieldsToFill.map { it.webOrigin }.toSet()
            if (originsAmongFieldsToFill.size != 1) return null
            val originToFill = originsAmongFieldsToFill.single() ?: return null
            webOriginToFormOrigin(originToFill)
        }
    }

    private fun makePlaceholderDataset(
        remoteView: RemoteViews, intentSender: IntentSender
    ): Dataset {
        return Dataset.Builder(remoteView).run {
            usernameField?.fillWith(this, "PLACEHOLDER")
            for (passwordField in passwordFields) {
                passwordField.fillWith(this, "PLACEHOLDER")
            }
            setAuthentication(intentSender)
            build()
        }
    }

    private fun makeFillMatchDataset(context: Context, file: File): Dataset {
        check(formOrigin != null)
        val remoteView = makeFillMatchRemoteView(context, file, formOrigin)
        val intentSender = AutofillDecryptActivity.makeDecryptFileIntentSender(file, context)
        return makePlaceholderDataset(remoteView, intentSender)
    }

    private fun makeSearchAndFillDataset(context: Context): Dataset {
        check(formOrigin != null)
        val remoteView = makeSearchAndFillRemoteView(context, formOrigin)
        val intentSender =
            AutofillFilterView.makeMatchAndDecryptFileIntentSender(context, formOrigin)
        return makePlaceholderDataset(remoteView, intentSender)
    }

    private fun makeGenerateAndFillDataset(context: Context): Dataset {
        check(formOrigin != null)
        val remoteView = makeGenerateAndFillRemoteView(context, formOrigin)
        val intentSender = AutofillSaveActivity.makeSaveIntentSender(context, null, formOrigin)
        return makePlaceholderDataset(remoteView, intentSender)
    }

    private fun makePublisherChangedDataset(context: Context, publisherChangedException: AutofillPublisherChangedException): Dataset {
        val remoteView = makeWarningRemoteView(context)
        val intentSender  = AutofillPublisherChangedActivity.makePublisherChangedIntentSender(context, publisherChangedException)
        return makePlaceholderDataset(remoteView, intentSender)
    }

    private fun makePublisherChangedResponse(context: Context, publisherChangedException: AutofillPublisherChangedException): FillResponse {
        return FillResponse.Builder().run {
            addDataset(makePublisherChangedDataset(context, publisherChangedException))
            setIgnoredIds(*ignoredIds.toTypedArray())
            build()
        }
    }

    private fun makeSaveInfo(): SaveInfo? {
        // TODO: Support multi-step authentication flows
        if (passwordFields.isEmpty()) return null

        val idsToSave = passwordFields.map { it.autofillId }.toMutableList()
        var saveDataTypes = SaveInfo.SAVE_DATA_TYPE_PASSWORD

        usernameField?.let {
            saveDataTypes = saveDataTypes or SaveInfo.SAVE_DATA_TYPE_USERNAME
            idsToSave.add(it.autofillId)
        }

        return SaveInfo.Builder(saveDataTypes, idsToSave.toTypedArray()).run {
            // FIXME
            if (isBrowser) setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
            build()
        }
    }

    private fun detectFieldsToFill() = autofillStrategy.apply(fillableFields, singleOriginMode)

    fun makeFillResponse(context: Context): FillResponse? {
        if (scenario == null) return null
        return FillResponse.Builder().run {
            when (scenario) {
                is PreciseAutofillScenario -> TODO()
                is GenericAutofillScenario -> TODO()
            }
            setClientState(clientState)
            setIgnoredIds(*ignoredIds.toTypedArray())
            build()
        }
    }

    fun fillCredentials(context: Context, callback: FillCallback) {
        check(canBeFilled)
        val matchedFiles = try {
            AutofillMatcher.getMatchesFor(context, formOrigin!!)
        } catch (publisherChangedException: AutofillPublisherChangedException) {
            e(publisherChangedException)
            callback.onSuccess(makePublisherChangedResponse(context, publisherChangedException))
            return
        }
        val fillResponse = FillResponse.Builder().run {
            if (passwordFields.size == 1 || isManualRequest) {
                for (file in matchedFiles) addDataset(makeFillMatchDataset(context, file))
                addDataset(makeSearchAndFillDataset(context))
            }
            if (passwordFields.size == 2 || isManualRequest) {
                addDataset(makeGenerateAndFillDataset(context))
            }
            setClientState(clientState)
            setIgnoredIds(*ignoredIds.toTypedArray())
            val saveInfo = makeSaveInfo()
            if (saveInfo != null) setSaveInfo(saveInfo)
            build()
        }
        callback.onSuccess(fillResponse)
    }

    fun saveCredentials(context: Context, callback: FixedSaveCallback) {
        check(canBeSaved)
        val usernameValue = usernameField?.autofillValue
        val username = if (usernameValue?.isText == true) usernameValue.textValue else null
        val passwordValue = passwordFields.first().autofillValue
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

val AssistStructure.ViewNode.webOrigin: String?
    @RequiresApi(Build.VERSION_CODES.O) get() = webDomain?.let { domain ->
        val scheme = (if (Build.VERSION.SDK_INT >= 28) webScheme else null) ?: "http"
        "$scheme://$domain"
    }

