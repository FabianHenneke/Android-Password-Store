package com.zeapo.pwdstore.autofill.oreo

import android.content.Context

/*
    In order to add a new browser, do the following:

    1. Obtain the .apk from a trusted source. For example, download it from the Play Store on your
    phone and use adb pull to get it onto your computer. We will assume that it is called
    browser.apk.

    2. Run

    aapt dump badging browser.apk | grep package: | grep -Eo " name='[a-zA-Z0-9_\.]*" | cut -c8-

    to obtain the package name (actually, the application ID) of the app in the .apk.

    3. Run

    apksigner verify --print-certs browser.apk | grep "#1 certificate SHA-256" | grep -Eo "[a-f0-9]{64}" | tr -d '\n' | xxd -r -p | base64

    to calculate the hash of browser.apk's first signing certificate.
    Note: This will only work if the apk has a single signing certificate. Apps with multiple
    signers are very rare, so there is probably no need to add them.
    Refer to computeCertificatesHash to learn how the hash would be computed in this case.

    4. Verify the package name and the hash, for example by asking other people to repeat the steps
    above.

    5. Add an entry with the browser apps's package name and the hash to
    TRUSTED_BROWSER_CERTIFICATE_HASH.

    6. Optionally, try adding the browser's package name to BROWSERS_WITH_SAVE_SUPPORT and check
    whether a save request to Password Store is triggered when you submit a registration form.

    7. Optionally, try adding the browser's package name to BROWSERS_WITH_MULTI_ORIGIN_SUPPORT and
    check whether it correctly distinguishes web origins even if iframes are present on the page.
    You can use https://fabianhenneke.github.io/Android-Password-Store/ as a test form.
 */

// **Security assumption**:
// Browsers on this list correctly report the web origin of the top-level window as part of their
// AssistStructure.
// Note: Browsers can be on this list even if they don't report the correct web origins of all
// fields on the page, e.g. of those in iframes.
private val TRUSTED_BROWSER_CERTIFICATE_HASH = mapOf(
    "com.android.chrome" to "8P1sW0EPJcslw7UzRsiXL64w+O50Ed+RBICtay1g24M=",
    "com.brave.browser" to "nC23BRNRX9v7vFhbPt89cSPU3GfJT/0wY2HB15u/GKw=",
    "com.duckduckgo.mobile.android" to "u3uzHFc8RqHaf8XFKKas9DIQhFb+7FCBDH8zaU6z0tQ=",
    "org.mozilla.firefox" to "p4tipRZbRJSy/q2edqKA0i2Tf+5iUa7OWZRGsuoxmwQ=",
    "org.mozilla.firefox_beta" to "p4tipRZbRJSy/q2edqKA0i2Tf+5iUa7OWZRGsuoxmwQ=",
    "org.mozilla.klar" to "YgOkc7421k7jf4f6UA7bx56rkwYQq5ufpMp9XB8bT/w=",
    "org.mozilla.focus" to "YgOkc7421k7jf4f6UA7bx56rkwYQq5ufpMp9XB8bT/w=",
    "org.mozilla.fenix" to "UAR3kIjn+YjVvFzF+HmP6/T4zQhKGypG79TI7krq8hE=",
    "org.mozilla.fenix.nightly" to "d+rEzu02r++6dheZMd1MwZWrDNVLrzVdIV57vdKOQCo=",
    "org.mozilla.fennec_aurora" to "vASIg40G9Mpr8yOG2qsN2OvPPncweHRZ9i+zzRShuqo=",
    "org.torproject.torbrowser" to "IAYfBF5zfGc3XBd5TP7bQ2oDzsa6y3y5+WZCIFyizsg="
)

fun isTrustedBrowser(context: Context, packageName: String): Boolean {
    val expectedCertificateHash = TRUSTED_BROWSER_CERTIFICATE_HASH[packageName] ?: return false
    val certificateHash = computeCertificatesHash(context, packageName)
    return certificateHash == expectedCertificateHash
}

private val BROWSERS_WITH_SAVE_SUPPORT = listOf(
    // Add known incompatible browsers here so that they can be revisited from time to time
    // "com.android.chrome": currently only provides masked passwords
    // "com.brave.browser": currently only provides masked passwords
    // "org.torproject.torbrowser": no save request
    "com.duckduckgo.mobile.android",
    "org.mozilla.klar",
    "org.mozilla.focus",
    "org.mozilla.fenix",
    "org.mozilla.fenix.nightly",
    "org.mozilla.fennec_aurora"
)

fun isBrowserWithSaveSupport(packageName: String): Boolean {
    return packageName in BROWSERS_WITH_SAVE_SUPPORT
}

// **Security assumption**:
// Browsers on this list correctly distinguish the web origins of form fields, e.g. on a page which
// contains both a first-party login form and an iframe with a (potentially malicious) third-party
// login form.
private val BROWSERS_WITH_MULTI_ORIGIN_SUPPORT = listOf(
    "com.duckduckgo.mobile.android",
    "org.mozilla.klar",
    "org.mozilla.focus",
    "org.mozilla.fenix",
    "org.mozilla.fenix.nightly",
    "org.mozilla.fennec_aurora",
    "org.mozilla.firefox",
    "org.mozilla.firefox_beta",
    "org.torproject.torbrowser"
)

fun isBrowserWithMultiOriginSupport(packageName: String): Boolean {
    return packageName in BROWSERS_WITH_MULTI_ORIGIN_SUPPORT
}