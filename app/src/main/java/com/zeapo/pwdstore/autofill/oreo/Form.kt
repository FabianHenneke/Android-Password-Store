package com.zeapo.pwdstore.autofill.oreo

import android.app.assist.AssistStructure
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.autofill.AutofillId
import androidx.annotation.RequiresApi

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

@RequiresApi(Build.VERSION_CODES.O)
class Form(structure: AssistStructure) {
    private val TAG = "Form"

    val fillableFields = mutableListOf<FormField>()
    val ignoredIds = mutableListOf<AutofillId>()

    var packageName = structure.activityComponent.packageName
    // TODO: Verify signature
    val isBrowser = packageName in ALL_BROWSERS
    var originToFill: String? = null

    init {
        Log.d(TAG, "Request from $packageName")
        parseStructure(structure)
    }

    private fun parseStructure(structure: AssistStructure) {
        for (i in 0 until structure.windowNodeCount) {
            parseViewNode(structure.getWindowNodeAt(i).rootViewNode)
        }
    }

    private fun parseViewNode(node: AssistStructure.ViewNode) {
        val field = FormField(node)
        if (field.isFillable && shouldContinueBasedOnOrigin(node)) {
            Log.d("Form", "Found fillable field: $field")
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
}