package com.zeapo.pwdstore.autofill.oreo

import android.app.assist.AssistStructure
import android.os.Build
import android.service.autofill.Dataset
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import androidx.annotation.RequiresApi
import java.util.*


enum class CertaintyLevel {
    Impossible,
    Possible,
    Likely,
    Certain
}

@RequiresApi(Build.VERSION_CODES.O)
class FormField(node: AssistStructure.ViewNode) {

    companion object {

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
                "android.support.v7.widget.AppCompatEditText",
                "com.google.android.material.textfield.TextInputEditText")

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

        private val EXCLUDED_TERMS = listOf(
                "url_bar", // Chrome/Edge address bar
                "search",
                "find"
        )
        private val PASSWORD_HEURISTIC_TERMS = listOf(
                "password",
                "pwd",
                "pswd",
                "passwort"
        )
        private val USERNAME_HEURISTIC_TERMS = listOf(
                "user",
                "name",
                "email"
        )

        private val SUPPORTED_SCHEMES = listOf("http", "https")
    }

    val autofillId: AutofillId = node.autofillId!!
    val isFocused = node.isFocused
    val autofillValue: AutofillValue? = node.autofillValue

    private val idEntry = node.idEntry?.toLowerCase(Locale.US) ?: ""
    private val hint = node.hint?.toLowerCase(Locale.US) ?: ""
    private val className: String? = node.className
    private val hasAutofillTypeText = node.autofillType == View.AUTOFILL_TYPE_TEXT
    private val autofillHints = node.autofillHints?.filter { isSupportedHint(it) } ?: emptyList()
    private val isCompatibleWithAutofillHints = if (autofillHints.isEmpty()) true else autofillHints.intersect(HINTS_FILLABLE).isNotEmpty()
    private val hasAutofillHintPassword = autofillHints.intersect(HINTS_PASSWORD).isNotEmpty()
    private val hasAutofillHintUsername = autofillHints.intersect(HINTS_USERNAME).isNotEmpty()
    private val inputType = node.inputType
    private val hasPasswordInputType = isPasswordInputType(inputType)
    private val isVisible = node.visibility == View.VISIBLE

    private val htmlTag = node.htmlInfo?.tag
    private val htmlInputType = node.htmlInfo?.attributes?.firstOrNull { it.first == "type" }?.second
    private val isHtmlField = htmlTag == "input"
    private val isHtmlPasswordField = isHtmlField && htmlInputType in HTML_INPUT_FIELD_TYPES_PASSWORD
    private val isHtmlTextField = isHtmlField && htmlInputType in HTML_INPUT_FIELD_TYPES_FILLABLE
    // FIXME: Detect W3C hints: https://html.spec.whatwg.org/multipage/form-control-infrastructure.html#autofilling-form-controls%3A-the-autocomplete-attribute

    val webOrigin = node.webDomain?.let { domain ->
        val scheme = (if (Build.VERSION.SDK_INT >= 28) node.webScheme else null) ?: "http"
        "$scheme://$domain"
    }

    private val isAndroidTextField = className in ANDROID_TEXT_FIELD_CLASS_NAMES
    private val isAndroidPasswordField = isAndroidTextField && hasPasswordInputType

    val isFillable = isVisible && (isAndroidTextField || isHtmlTextField) && hasAutofillTypeText && isCompatibleWithAutofillHints
    private val hasExcludedTerm = EXCLUDED_TERMS.any { idEntry.contains(it) || hint.contains(it) }
    private val shouldBeFilled = isFillable && !hasExcludedTerm

    private val isPossiblePasswordField = shouldBeFilled && (isAndroidPasswordField || isHtmlPasswordField)
    private val isCertainPasswordField = isPossiblePasswordField && (isHtmlPasswordField || hasAutofillHintPassword)
    private val isLikelyPasswordField = isCertainPasswordField || (PASSWORD_HEURISTIC_TERMS.any { idEntry.contains(it) || hint.contains(it) })
    val passwordCertainty = if (isCertainPasswordField) CertaintyLevel.Certain else if (isLikelyPasswordField) CertaintyLevel.Likely else if (isPossiblePasswordField) CertaintyLevel.Possible else CertaintyLevel.Impossible

    private val isPossibleUsernameField = shouldBeFilled && !isPossiblePasswordField
    private val isCertainUsernameField = isPossibleUsernameField && hasAutofillHintUsername
    private val isLikelyUsernameField = isCertainUsernameField || (USERNAME_HEURISTIC_TERMS.any { idEntry.contains(it) || hint.contains(it) })
    val usernameCertainty = if (isCertainUsernameField) CertaintyLevel.Certain else if (isLikelyUsernameField) CertaintyLevel.Likely else if (isPossibleUsernameField) CertaintyLevel.Possible else CertaintyLevel.Impossible

    fun fillWith(builder: Dataset.Builder, value: String) {
        builder.setValue(autofillId, AutofillValue.forText(value))
    }

    override fun toString(): String {
        val field = if (isHtmlTextField) "$htmlTag[type=$htmlInputType]" else className
        val description = "\"$hint\", $idEntry, focused=$isFocused, inputType=$inputType"
        return "$field ($description): password=$passwordCertainty, username=$usernameCertainty"
    }
}
