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

    6. Optionally, try adding the browsers package name to BROWSERS_WITH_SAVE_SUPPORT and check
    whether a save request to Password Store is triggered when you submit a registration form.
 */
private val TRUSTED_BROWSER_CERTIFICATE_HASH = mapOf(
        "org.mozilla.firefox" to "p4tipRZbRJSy/q2edqKA0i2Tf+5iUa7OWZRGsuoxmwQ=",
        "org.mozilla.firefox_beta" to "p4tipRZbRJSy/q2edqKA0i2Tf+5iUa7OWZRGsuoxmwQ=",
        "org.mozilla.klar" to "YgOkc7421k7jf4f6UA7bx56rkwYQq5ufpMp9XB8bT/w=",
        "org.mozilla.focus" to "YgOkc7421k7jf4f6UA7bx56rkwYQq5ufpMp9XB8bT/w=",
        "org.mozilla.fenix.nightly" to "d+rEzu02r++6dheZMd1MwZWrDNVLrzVdIV57vdKOQCo=",
        "org.mozilla.fennec_aurora" to "vASIg40G9Mpr8yOG2qsN2OvPPncweHRZ9i+zzRShuqo="

)

fun isTrustedBrowser(context: Context, packageName: String): Boolean {
    val expectedCertificateHash = TRUSTED_BROWSER_CERTIFICATE_HASH[packageName] ?: return false
    val certificateHash = computeCertificatesHash(context, packageName)
    return certificateHash == expectedCertificateHash
}

private val BROWSERS_WITH_SAVE_SUPPORT = listOf(
        "org.mozilla.klar",
        "org.mozilla.focus",
        "org.mozilla.fenix.nightly",
        "org.mozilla.fennec_aurora"
)

fun isTrustedBrowserWithSaveSupport(context: Context, packageName: String): Boolean {
    return isTrustedBrowser(context, packageName) && packageName in BROWSERS_WITH_SAVE_SUPPORT
}