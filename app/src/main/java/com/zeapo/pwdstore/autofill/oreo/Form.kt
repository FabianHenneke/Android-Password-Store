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
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import androidx.core.os.bundleOf
import com.github.ajalt.timberkt.d
import com.github.ajalt.timberkt.e
import com.zeapo.pwdstore.autofill.oreo.ui.AutofillDecryptActivity
import com.zeapo.pwdstore.autofill.oreo.ui.AutofillFilterView
import com.zeapo.pwdstore.autofill.oreo.ui.AutofillPublisherChangedActivity
import com.zeapo.pwdstore.autofill.oreo.ui.AutofillSaveActivity
import java.io.File

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

    companion object {
        private const val BUNDLE_KEY_WEB_IDENTIFIER = "webIdentifier"
        private const val BUNDLE_KEY_APP_IDENTIFIER = "appIdentifier"

        fun fromBundle(bundle: Bundle): FormOrigin? {
            val webIdentifier = bundle.getString(BUNDLE_KEY_WEB_IDENTIFIER)
            if (webIdentifier != null) {
                return Web(webIdentifier)
            } else {
                return App(bundle.getString(BUNDLE_KEY_APP_IDENTIFIER) ?: return null)
            }
        }
    }

    fun getPrettyIdentifier(context: Context, untrusted: Boolean = true) = when (this) {
        is Web -> identifier
        is App -> {
            val info = context.packageManager.getApplicationInfo(
                identifier, PackageManager.GET_META_DATA
            )
            val label = context.packageManager.getApplicationLabel(info)
            if (untrusted) "“$label”" else "$label"
        }
    }

    fun toBundle() = when (this) {
        is Web -> bundleOf(BUNDLE_KEY_WEB_IDENTIFIER to identifier)
        is App -> bundleOf(BUNDLE_KEY_APP_IDENTIFIER to identifier)
    }
}

@RequiresApi(Build.VERSION_CODES.O)
// FIXME: Use manual request?
private class Form(
    context: Context, structure: AssistStructure, private val isManualRequest: Boolean
) {

    companion object {
        private val SUPPORTED_SCHEMES = listOf("http", "https")
    }

    private val relevantFields = mutableListOf<FormField>()
    val ignoredIds = mutableListOf<AutofillId>()
    private var fieldIndex = 0

    private var appPackage = structure.activityComponent.packageName

    // FIXME
    //    private val isBrowser = isTrustedBrowser(context, packageName)
    private val isBrowser = appPackage in ALL_BROWSERS

    // FIXME
    //    private val isBrowserSupportingSave = isBrowser && isBrowserWithSaveSupport(packageName)
    private val isBrowserSupportingSave = isBrowser
    private val isBrowserSupportingMultiOrigin =
        isBrowser && isBrowserWithMultiOriginSupport(appPackage)
    private val singleOriginMode = isBrowser && !isBrowserSupportingMultiOrigin

    private val webOrigins = mutableListOf<String>()

    init {
        d { "Request from $appPackage (${computeCertificatesHash(context, appPackage)})" }
        parseStructure(structure)
    }

    val scenario = detectFieldsToFill()

    init {
        d { "Username field: ${scenario?.username}" }
        d { "Will fill username: ${scenario?.fillUsername}" }
        d { "Generic password field(s): ${(scenario as? GenericAutofillScenario)?.genericPassword}" }
        d { "Current password field(s): ${(scenario as? ClassifiedAutofillScenario)?.currentPassword}" }
        d { "New password field(s): ${(scenario as? ClassifiedAutofillScenario)?.newPassword}" }
    }

    val formOrigin = determineFormOrigin()

    init {
        d { "Origin: $formOrigin" }
    }

    val originSupportsSave = formOrigin != null && (!isBrowser || isBrowserSupportingSave)

    private fun parseStructure(structure: AssistStructure) = visitViewNodes(structure) { node ->
        trackOrigin(node)
        val field = FormField(node, fieldIndex)
        // FIXME: Improve origin detection by considering iframes and restricting to the list returned by adb shell settings get global autofill_compat_mode_allowed_packages
        // FIXME: WebView?
        if (field.isFillable || field.isSaveable) {
            d { "$field" }
            relevantFields.add(field)
            fieldIndex++
        } else {
            d { "Found irrelevant field: $field" }
            ignoredIds.add(field.autofillId)
        }
    }

    private fun detectFieldsToFill() = autofillStrategy.apply(relevantFields, singleOriginMode)

    private fun trackOrigin(node: AssistStructure.ViewNode) {
        if (!isBrowser) return
        node.webOrigin?.let {
            if (it !in webOrigins) {
                d { "Origin encountered: $it" }
                webOrigins.add(it)
            }
        }
    }

    private fun webOriginToFormOrigin(origin: String): FormOrigin? {
        val uri = Uri.parse(origin) ?: return null
        val scheme = uri.scheme ?: return null
        if (scheme !in SUPPORTED_SCHEMES) return null
        val host = uri.host ?: return null
        return FormOrigin.Web(getCanonicalDomain(host))
    }

    private fun determineFormOrigin(): FormOrigin? {
        if (scenario == null) return null
        return if (!isBrowser || webOrigins.isEmpty()) {
            // Security assumption: If a trusted browser includes no web origin in the provided
            // AssistStructure, then the form is a native browser form (e.g. for a sync password).
            FormOrigin.App(appPackage)
        } else if (!isBrowserSupportingMultiOrigin) {
            // Security assumption: If a browser is trusted but does not support tracking multiple
            // origins, it is expected to annotate a single field, in most cases its URL bar, with a
            // webOrigin. We err on the side of caution and only trust the reported web origin if it
            // is a single one.
            webOriginToFormOrigin(webOrigins.singleOrNull() ?: return null)
        } else {
            // For browsers with support for multiple origins, we take the single origin among the
            // detected fillable or saveable fields. If this origin is null, but we encountered web
            // origins elsewhere in the AssistStructure, the situation is uncertain and Autofill
            // should not be offered.
            webOriginToFormOrigin(
                scenario.allFields.map { it.webOrigin }.singleOrNull() ?: return null
            )
        }
    }


}

@RequiresApi(Build.VERSION_CODES.O)
class FillableForm private constructor(
    private val formOrigin: FormOrigin,
    private val scenario: AutofillScenario<FormField>,
    private val ignoredIds: List<AutofillId>,
    private val originSupportsSave: Boolean
) {
    companion object {
        fun makeFillInDataset(
            context: Context, credentials: Credentials, clientState: Bundle, action: AutofillAction
        ): Dataset {
            val remoteView = makePlaceholderRemoteView(context)
            // FIXME
            val scenario = AutofillScenario.fromBundle(clientState)!!
            return Dataset.Builder(remoteView).run {
                fillWith(scenario, action, credentials)
                build()
            }
        }

        fun parseAssistStructure(
            context: Context, structure: AssistStructure, isManualRequest: Boolean
        ): FillableForm? {
            val form = Form(context, structure, isManualRequest)
            if (form.formOrigin == null || form.scenario == null) return null
            return FillableForm(
                form.formOrigin, form.scenario, form.ignoredIds, form.originSupportsSave
            )
        }
    }

    private val clientState = scenario.toBundle().apply {
        putAll(formOrigin.toBundle())
    }

    // We do not offer save when the only relevant field is a username field or there is no field.
    private val scenarioSupportsSave =
        scenario.fieldsToSave.minus(listOfNotNull(scenario.username)).isNotEmpty()
    private val canBeSaved = originSupportsSave && scenarioSupportsSave

    private fun makePlaceholderDataset(
        remoteView: RemoteViews, intentSender: IntentSender, action: AutofillAction
    ): Dataset {
        return Dataset.Builder(remoteView).run {
            fillWith(scenario, action, credentials = null)
            setAuthentication(intentSender)
            build()
        }
    }

    private fun makeMatchDataset(context: Context, file: File): Dataset? {
        if (scenario.fieldsToFillOn(AutofillAction.Match).isEmpty()) return null
        val remoteView = makeFillMatchRemoteView(context, file, formOrigin)
        val intentSender = AutofillDecryptActivity.makeDecryptFileIntentSender(file, context)
        return makePlaceholderDataset(remoteView, intentSender, AutofillAction.Match)
    }

    private fun makeSearchDataset(context: Context): Dataset? {
        if (scenario.fieldsToFillOn(AutofillAction.Search).isEmpty()) return null
        val remoteView = makeSearchAndFillRemoteView(context, formOrigin)
        val intentSender =
            AutofillFilterView.makeMatchAndDecryptFileIntentSender(context, formOrigin)
        return makePlaceholderDataset(remoteView, intentSender, AutofillAction.Search)
    }

    private fun makeGenerateDataset(context: Context): Dataset? {
        if (scenario.fieldsToFillOn(AutofillAction.Generate).isEmpty()) return null
        val remoteView = makeGenerateAndFillRemoteView(context, formOrigin)
        val intentSender = AutofillSaveActivity.makeSaveIntentSender(context, null, formOrigin)
        return makePlaceholderDataset(remoteView, intentSender, AutofillAction.Generate)
    }

    private fun makePublisherChangedDataset(
        context: Context, publisherChangedException: AutofillPublisherChangedException
    ): Dataset {
        val remoteView = makeWarningRemoteView(context)
        val intentSender = AutofillPublisherChangedActivity.makePublisherChangedIntentSender(
            context, publisherChangedException
        )
        return makePlaceholderDataset(remoteView, intentSender, AutofillAction.Match)
    }

    private fun makePublisherChangedResponse(
        context: Context, publisherChangedException: AutofillPublisherChangedException
    ): FillResponse {
        return FillResponse.Builder().run {
            addDataset(makePublisherChangedDataset(context, publisherChangedException))
            setIgnoredIds(*ignoredIds.toTypedArray())
            build()
        }
    }

    // TODO: Support multi-step authentication flows in apps via FLAG_DELAY_SAVE
    // See: https://developer.android.com/reference/android/service/autofill/SaveInfo#FLAG_DELAY_SAVE
    private fun makeSaveInfo(): SaveInfo? {
        if (!canBeSaved) return null
        val idsToSave = scenario.fieldsToSave.map { it.autofillId }.toTypedArray()
        if (idsToSave.isEmpty()) return null
        var saveDataTypes = SaveInfo.SAVE_DATA_TYPE_PASSWORD
        // FIXME: Can we always save the username? It may mean moving a file...
        if (scenario.username != null) {
            saveDataTypes = saveDataTypes or SaveInfo.SAVE_DATA_TYPE_USERNAME
        }
        return SaveInfo.Builder(saveDataTypes, idsToSave).build()
    }


    private fun makeFillResponse(context: Context, matchedFiles: List<File>): FillResponse? {
        var hasDataset = false
        return FillResponse.Builder().run {
            for (file in matchedFiles) {
                makeMatchDataset(context, file)?.let {
                    hasDataset = true
                    addDataset(it)
                }
            }
            makeSearchDataset(context)?.let {
                hasDataset = true
                addDataset(it)
            }
            makeGenerateDataset(context)?.let {
                hasDataset = true
                addDataset(it)
            }
            if (!hasDataset) return null
            makeSaveInfo()?.let { setSaveInfo(it) }
            setClientState(clientState)
            setIgnoredIds(*ignoredIds.toTypedArray())
            build()
        }
    }

    fun fillCredentials(context: Context, callback: FillCallback) {
        val matchedFiles = try {
            AutofillMatcher.getMatchesFor(context, formOrigin)
        } catch (publisherChangedException: AutofillPublisherChangedException) {
            e(publisherChangedException)
            callback.onSuccess(makePublisherChangedResponse(context, publisherChangedException))
            return
        }
        callback.onSuccess(makeFillResponse(context, matchedFiles))
    }
}
