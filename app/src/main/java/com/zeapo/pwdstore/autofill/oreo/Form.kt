package com.zeapo.pwdstore.autofill.oreo

import android.app.assist.AssistStructure
import android.content.Context
import android.os.Build
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.util.Log
import android.view.autofill.AutofillId
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import com.zeapo.pwdstore.R

private val AUTOFILL_BROWSERS = listOf("org.mozilla.focus",
        "org.mozilla.klar",
        "com.duckduckgo.mobile.android")
private val ACCESSIBILITY_BROWSERS = listOf("org.mozilla.firefox",
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
}

@RequiresApi(Build.VERSION_CODES.O)
class Form(structure: AssistStructure, context: Context) {
    private val TAG = "Form"

    private val fillableFields = mutableListOf<FormField>()
    private val ignoredIds = mutableListOf<AutofillId>()
    private val passwordFields by lazy { identifyPasswordFields() }
    private val usernameField by lazy { identifyUsernameField() }

    private var packageName = structure.activityComponent.packageName
    // TODO: Verify signature
    private val isBrowser = packageName in ALL_BROWSERS
    private var originToFill: String? = null

    val canBeFilled by lazy { usernameField != null || passwordFields.isNotEmpty() }
    val origin = if (isBrowser && originToFill != null) FormOrigin.Web(originToFill!!) else FormOrigin.App(packageName)

    init {
        Log.d(TAG, "Request from $packageName (${context.getPackageVerificationId(packageName)})")
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
        if (field.shouldBeFilled && shouldContinueBasedOnOrigin(node)) {
            Log.d("Form", "$field")
            fillableFields.add(field)
        } else {
            // Log.d("Form", "Found non-fillable field: $field")
            field.autofillId?.let { ignoredIds.add(it) }
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
        return Math.abs(id0 - id1) == 1
    }

    private fun isFocusedOrFollowsFocusedUsernameField(field: FormField): Boolean {
        if (field.isFocused)
            return true

        val ownIndex = fillableFields.indexOf(field)
        if (ownIndex == 0)
            return false
        val potentialUsernameField = fillableFields[ownIndex - 1]
        if (!potentialUsernameField.isFocused || !potentialUsernameField.isLikelyUsernameField)
            return false
        return true
    }

    private fun identifyPasswordFields(): List<FormField> {
        val possiblePasswordFields = fillableFields.filter { it.passwordCertainty >= CertaintyLevel.Possible }
        if (possiblePasswordFields.isEmpty())
            return emptyList()
        val certainPasswordFields = fillableFields.filter { it.passwordCertainty >= CertaintyLevel.Certain }
        if (isSingleFieldOrAdjacentPair(certainPasswordFields))
            return certainPasswordFields
        if (certainPasswordFields.count { isFocusedOrFollowsFocusedUsernameField(it) } == 1)
            return certainPasswordFields.filter { isFocusedOrFollowsFocusedUsernameField(it) }
        val likelyPasswordFields = fillableFields.filter { it.passwordCertainty >= CertaintyLevel.Likely }
        if (isSingleFieldOrAdjacentPair(likelyPasswordFields))
            return likelyPasswordFields
        if (likelyPasswordFields.count { isFocusedOrFollowsFocusedUsernameField(it) } == 1)
            return likelyPasswordFields.filter { isFocusedOrFollowsFocusedUsernameField(it) }
        if (isSingleFieldOrAdjacentPair(possiblePasswordFields))
            return possiblePasswordFields
        return emptyList()
    }

    private fun takeSingleFieldOrFirstBeforePasswordFields(fields: List<FormField>): FormField? {
        if (fields.isEmpty())
            return null
        if (fields.size == 1)
            return fields.first()

        if (passwordFields.isEmpty())
            return null
        val firstPasswordIndex = fillableFields.indexOf(passwordFields.first())
        return fields.last { fillableFields.indexOf(it) < firstPasswordIndex }
    }

    private fun identifyUsernameField(): FormField? {
        val possibleUsernameFields = fillableFields.filter { it.usernameCertainty >= CertaintyLevel.Possible }
        if (possibleUsernameFields.isEmpty())
            return null
        val certainUsernameFields = fillableFields.filter { it.usernameCertainty >= CertaintyLevel.Certain }
        var result = takeSingleFieldOrFirstBeforePasswordFields(certainUsernameFields)
        if (result != null)
            return result
        val likelyUsernameFields = fillableFields.filter { it.usernameCertainty >= CertaintyLevel.Likely }
        result = takeSingleFieldOrFirstBeforePasswordFields(likelyUsernameFields)
        if (result != null)
            return result
        return takeSingleFieldOrFirstBeforePasswordFields(possibleUsernameFields)
    }

    fun fillWith(username: String?, password: String, context: Context): FillResponse {
        check(canBeFilled)
        return FillResponse.Builder().run {
            val remoteView = RemoteViews(context.packageName, R.layout.oreo_autofill_dataset)
            remoteView.setTextViewText(R.id.text1, origin.identifier)
            remoteView.setTextViewText(R.id.text2, username)
            val dataset = Dataset.Builder(remoteView).run {
                if (username != null && usernameField != null)
                    usernameField!!.fillWith(this, username)
                for (passwordField in passwordFields) {
                    passwordField.fillWith(this, password)
                }
                build()
            }
            addDataset(dataset)
            setIgnoredIds(*ignoredIds.toTypedArray())
            build()
        }
    }
}