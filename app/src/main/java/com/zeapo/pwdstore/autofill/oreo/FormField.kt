package com.zeapo.pwdstore.autofill.oreo

import android.app.assist.AssistStructure
import android.os.Build
import android.text.InputType
import android.view.View
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.O)
private val HINTS_USERNAME = listOf(View.AUTOFILL_HINT_USERNAME)

@RequiresApi(Build.VERSION_CODES.O)
private val HINTS_PASSWORD = listOf(View.AUTOFILL_HINT_PASSWORD)

@RequiresApi(Build.VERSION_CODES.O)
private val HINTS_FILLABLE = HINTS_USERNAME + HINTS_PASSWORD + listOf(
        View.AUTOFILL_HINT_EMAIL_ADDRESS,
        View.AUTOFILL_HINT_NAME,
        View.AUTOFILL_HINT_PHONE)

private val ANDROID_TEXT_FIELD_CLASS_NAMES = listOf(
        "android.widget.EditText",
        "android.widget.AutoCompleteTextView",
        "androidx.appcompat.widget.AppCompatEditText",
        "android.support.v7.widget.AppCompatEditText")

private fun isPasswordInputType(inputType: Int): Boolean {
    val typeClass = inputType and InputType.TYPE_MASK_CLASS
    val typeVariation = inputType and InputType.TYPE_MASK_VARIATION
    return when (typeClass) {
        InputType.TYPE_CLASS_NUMBER -> typeVariation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        InputType.TYPE_CLASS_TEXT -> typeVariation in listOf(
                    InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        else -> false
    }
}

private val HTML_INPUT_FIELD_TYPES_USERNAME = listOf("email", "tel", "text")
private val HTML_INPUT_FIELD_TYPES_PASSWORD = listOf("password")
private val HTML_INPUT_FIELD_TYPES_FILLABLE = HTML_INPUT_FIELD_TYPES_USERNAME + HTML_INPUT_FIELD_TYPES_PASSWORD

@RequiresApi(Build.VERSION_CODES.O)
private fun isSupportedHint(hint: String) = hint in HINTS_USERNAME + HINTS_PASSWORD

@RequiresApi(Build.VERSION_CODES.O)
class FormField(node: AssistStructure.ViewNode) {
    private val TAG = "FormField"

    val id = node.id
    val idEntry = node.idEntry
    val hint = node.hint
    val className = node.className
    val autofillId = node.autofillId
    val hasAutofillTypeText = node.autofillType == View.AUTOFILL_TYPE_TEXT
    val autofillHints = node.autofillHints?.filter { isSupportedHint(it) } ?: emptyList()
    val isCompatibleWithAutofillHints = if (autofillHints.isEmpty()) true else autofillHints.intersect(HINTS_FILLABLE).isNotEmpty()
    val hasAutofillHintPassword = autofillHints.intersect(HINTS_PASSWORD).isNotEmpty()
    val hasAutofillHintUsername = autofillHints.intersect(HINTS_USERNAME).isNotEmpty()
    val inputType = node.inputType
    val hasPasswordInputType = isPasswordInputType(inputType)
    val isVisible = node.visibility == View.VISIBLE

    val htmlTag = node.htmlInfo?.tag
    val htmlInputType = node.htmlInfo?.attributes?.firstOrNull { it.first == "type" }?.second
    val isHtmlField = htmlTag == "input"
    val isHtmlPasswordField = isHtmlField && htmlInputType in HTML_INPUT_FIELD_TYPES_PASSWORD
    val isHtmlTextField = isHtmlField && htmlInputType in HTML_INPUT_FIELD_TYPES_FILLABLE

    val isAndroidTextField = className in ANDROID_TEXT_FIELD_CLASS_NAMES

    val isFillable = isVisible && (isAndroidTextField || isHtmlTextField) && hasAutofillTypeText && isCompatibleWithAutofillHints

    val isPossiblyPasswordField = isFillable && hasPasswordInputType
    val isCertainlyPasswordField = isPossiblyPasswordField && (isHtmlPasswordField || hasAutofillHintPassword)

    val isPossiblyUsernameField = isFillable && !isPossiblyPasswordField
    val isCertainlyUsernameField = isPossiblyUsernameField && hasAutofillHintUsername

    override fun toString(): String {
        val password = if (isCertainlyPasswordField) "certainly" else if (isPossiblyPasswordField) "possibly" else "no"
        val username = if (isCertainlyUsernameField) "certainly" else if (isPossiblyUsernameField) "possibly" else "no"
        val field = if (isHtmlTextField) "$htmlTag[type=$htmlInputType]" else className
        val description = "\"$hint\", $idEntry"
        return "$field ($description): password=$password, username=$username"
    }
}
