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
                "url_bar", // Chrome/Edge/Firefox address bar
                "url_field", // Opera address bar
                "location_bar_edit_text", // Samsung address bar
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
    }

    val autofillId: AutofillId = node.autofillId!!
    val autofillValue: AutofillValue? = node.autofillValue

    // Basic autofill exclusion checks
    private val hasAutofillTypeText = node.autofillType == View.AUTOFILL_TYPE_TEXT
    private val isVisible = node.visibility == View.VISIBLE

    // Information for heuristics and exclusion rules based only on the current field
    private val idEntry = node.idEntry?.toLowerCase(Locale.US) ?: ""
    private val hint = node.hint?.toLowerCase(Locale.US) ?: ""
    private val className: String? = node.className
    private val inputType = node.inputType

    // Information for advanced heuristics taking multiple fields and page context into account
    val isFocused = node.isFocused
    val webOrigin = node.webOrigin

    // Basic type detection for native fields
    private val hasPasswordInputType = isPasswordInputType(inputType)
    private val isAndroidTextField = className in ANDROID_TEXT_FIELD_CLASS_NAMES
    private val isAndroidPasswordField = isAndroidTextField && hasPasswordInputType

    // Basic type detection for HTML fields
    private val htmlTag = node.htmlInfo?.tag
    private val htmlInputType = node.htmlInfo?.attributes?.firstOrNull { it.first == "type" }?.second
    private val isHtmlField = htmlTag == "input"
    private val isHtmlPasswordField = isHtmlField && htmlInputType in HTML_INPUT_FIELD_TYPES_PASSWORD
    private val isHtmlTextField = isHtmlField && htmlInputType in HTML_INPUT_FIELD_TYPES_FILLABLE

    // Autofill hint detection for native fields
    private val autofillHints = node.autofillHints?.filter { isSupportedHint(it) } ?: emptyList()
    private val notExcludedByAutofillHints = if (autofillHints.isEmpty()) true else autofillHints.intersect(HINTS_FILLABLE).isNotEmpty()
    private val hasAutofillHintPassword = autofillHints.intersect(HINTS_PASSWORD).isNotEmpty()
    private val hasAutofillHintUsername = autofillHints.intersect(HINTS_USERNAME).isNotEmpty()

    // FIXME: Detect W3C hints: https://html.spec.whatwg.org/multipage/form-control-infrastructure.html#autofilling-form-controls%3A-the-autocomplete-attribute
    // W3C autocomplete hint detection for HTML fields
    private val htmlAutocomplete = node.htmlInfo?.attributes?.firstOrNull { it.first == "autocomplete" }?.second?.toLowerCase(Locale.US)
    private val notExcludedByAutocompleteHints = htmlAutocomplete != "off"
    private val hasAutocompleteHintUsername = htmlAutocomplete == "username"
    private val hasAutocompleteHintCurrentPassword = htmlAutocomplete == "current-password"
    private val hasAutocompleteHintNewPassword = htmlAutocomplete == "new-password"
    private val hasAutocompleteHintPassword = hasAutocompleteHintCurrentPassword || hasAutocompleteHintNewPassword

    val isFillable = isVisible && (isAndroidTextField || isHtmlTextField) && hasAutofillTypeText && notExcludedByAutofillHints && notExcludedByAutocompleteHints

    // Exclude fields based on hint and resource ID
    // Note: We still report excluded fields as fillable since they allow adjacency heuristics,
    // but ensure that they are never detected as password or username fields.
    private val hasExcludedTerm = EXCLUDED_TERMS.any { idEntry.contains(it) || hint.contains(it) }
    private val shouldBeFilled = isFillable && !hasExcludedTerm

    // Password field heuristics (based only on the current field)
    private val isPossiblePasswordField = shouldBeFilled && (isAndroidPasswordField || isHtmlPasswordField)
    private val isCertainPasswordField = isPossiblePasswordField && (isHtmlPasswordField || hasAutofillHintPassword || hasAutocompleteHintPassword)
    private val isLikelyPasswordField = isCertainPasswordField || (PASSWORD_HEURISTIC_TERMS.any { idEntry.contains(it) || hint.contains(it) })
    val passwordCertainty = if (isCertainPasswordField) CertaintyLevel.Certain else if (isLikelyPasswordField) CertaintyLevel.Likely else if (isPossiblePasswordField) CertaintyLevel.Possible else CertaintyLevel.Impossible

    // Username field heuristics (based only on the current field)
    private val isPossibleUsernameField = shouldBeFilled && !isPossiblePasswordField
    private val isCertainUsernameField = isPossibleUsernameField && (hasAutofillHintUsername && hasAutocompleteHintUsername)
    private val isLikelyUsernameField = isCertainUsernameField || (USERNAME_HEURISTIC_TERMS.any { idEntry.contains(it) || hint.contains(it) })
    val usernameCertainty = if (isCertainUsernameField) CertaintyLevel.Certain else if (isLikelyUsernameField) CertaintyLevel.Likely else if (isPossibleUsernameField) CertaintyLevel.Possible else CertaintyLevel.Impossible

    fun fillWith(builder: Dataset.Builder, value: String) {
        builder.setValue(autofillId, AutofillValue.forText(value))
    }

    override fun toString(): String {
        val field = if (isHtmlTextField) "$htmlTag[type=$htmlInputType]" else className
        val description = "\"$hint\", $idEntry, focused=$isFocused, inputType=$inputType, $webOrigin"
        return "$field ($description): password=$passwordCertainty, username=$usernameCertainty"
    }
}
