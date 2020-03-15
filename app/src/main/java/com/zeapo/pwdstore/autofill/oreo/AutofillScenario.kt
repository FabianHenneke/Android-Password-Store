package com.zeapo.pwdstore.autofill.oreo

import android.app.assist.AssistStructure
import android.os.Build
import android.os.Bundle
import android.service.autofill.Dataset
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import androidx.annotation.RequiresApi
import com.github.ajalt.timberkt.e

enum class AutofillAction {
    Match, Search, Generate
}

@RequiresApi(Build.VERSION_CODES.O)
sealed class AutofillScenario<out T: Any>() {
    abstract val username: T?
    abstract val fillUsername: Boolean

    companion object {
        const val BUNDLE_KEY_USERNAME_ID = "usernameId"
        const val BUNDLE_KEY_FILL_USERNAME = "fillUsername"
        const val BUNDLE_KEY_CURRENT_PASSWORD_IDS = "currentPasswordIds"
        const val BUNDLE_KEY_NEW_PASSWORD_IDS = "newPasswordIds"
        const val BUNDLE_KEY_GENERIC_PASSWORD_IDS = "genericPasswordIds"

        fun fromBundle(clientState: Bundle): AutofillScenario<AutofillId>? {
            return try {
                Builder<AutofillId>().apply {
                    username = clientState.getParcelable(BUNDLE_KEY_USERNAME_ID)
                    fillUsername = clientState.getBoolean(BUNDLE_KEY_FILL_USERNAME)
                    currentPassword.addAll(
                        clientState.getParcelableArrayList(
                            BUNDLE_KEY_CURRENT_PASSWORD_IDS
                        ) ?: emptyList()
                    )
                    newPassword.addAll(
                        clientState.getParcelableArrayList(
                            BUNDLE_KEY_NEW_PASSWORD_IDS
                        ) ?: emptyList()
                    )
                    genericPassword.addAll(
                        clientState.getParcelableArrayList(
                            BUNDLE_KEY_GENERIC_PASSWORD_IDS
                        ) ?: emptyList()
                    )
                }.build()
            } catch (exception: IllegalArgumentException) {
                e(exception)
                null
            }
        }
    }

    class Builder<T: Any> {
        var username: T? = null
        var fillUsername = false
        val currentPassword = mutableListOf<T>()
        val newPassword = mutableListOf<T>()
        val genericPassword = mutableListOf<T>()

        fun build(): AutofillScenario<T> {
            require(genericPassword.isEmpty() || (currentPassword.isEmpty() && newPassword.isEmpty()))
            return if (currentPassword.isNotEmpty() || newPassword.isNotEmpty()) {
                ClassifiedAutofillScenario(
                    username = username,
                    fillUsername = fillUsername,
                    currentPassword = currentPassword,
                    newPassword = newPassword
                )
            } else {
                GenericAutofillScenario(
                    username = username,
                    fillUsername = fillUsername,
                    genericPassword = genericPassword
                )
            }
        }
    }

    val allFields: List<T> by lazy {
        listOfNotNull(username) + when (this) {
            is ClassifiedAutofillScenario -> currentPassword + newPassword
            is GenericAutofillScenario -> genericPassword
        }
    }

    fun fieldsToFillOn(action: AutofillAction): List<T> = when (action) {
        AutofillAction.Match -> fieldsToFillOnMatch
        AutofillAction.Search -> fieldsToFillOnSearch
        AutofillAction.Generate -> fieldsToFillOnGenerate
    }

    private val fieldsToFillOnMatch: List<T> by lazy {
        listOfNotNull(username.takeIf { fillUsername }) + when (this) {
            is ClassifiedAutofillScenario -> currentPassword
            is GenericAutofillScenario -> {
                if (genericPassword.size == 1) genericPassword
                else emptyList()
            }
        }
    }

    private val fieldsToFillOnSearch: List<T> by lazy {
        listOfNotNull(username.takeIf { fillUsername }) + when (this) {
            is ClassifiedAutofillScenario -> currentPassword
            is GenericAutofillScenario -> {
                if (genericPassword.size == 1) genericPassword
                else emptyList()
            }
        }
    }

    private val fieldsToFillOnGenerate: List<T> by lazy {
        listOfNotNull(username.takeIf { fillUsername }) + when (this) {
            is ClassifiedAutofillScenario -> newPassword
            is GenericAutofillScenario -> genericPassword
        }
    }

    val passwordFields: List<T> by lazy {
        when (this) {
            is ClassifiedAutofillScenario -> {
                // Save only the new password if there are both new and current passwords
                if (newPassword.isNotEmpty()) newPassword else currentPassword
            }
            is GenericAutofillScenario -> genericPassword
        }
    }

    val fieldsToSave: List<T> by lazy {
        listOfNotNull(username) + passwordFields
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@JvmName("fillWithAutofillId")
fun Dataset.Builder.fillWith(
    scenario: AutofillScenario<AutofillId>, action: AutofillAction, credentials: Credentials?
) {
    val credentialsToFill = credentials ?: Credentials(
        "USERNAME",
        "PASSWORD"
    )
    for (field in scenario.fieldsToFillOn(action)) {
        val value = if (field == scenario.username) {
            credentialsToFill.username
        } else {
            credentialsToFill.password
        } ?: continue
        setValue(field, AutofillValue.forText(value))
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@JvmName("fillWithFormField")
fun Dataset.Builder.fillWith(
    scenario: AutofillScenario<FormField>, action: AutofillAction, credentials: Credentials?
) {
    fillWith(scenario.map { it.autofillId }, action, credentials)
}

@RequiresApi(Build.VERSION_CODES.O)
data class ClassifiedAutofillScenario<T: Any>(
    override val username: T?,
    override val fillUsername: Boolean,
    val currentPassword: List<T>,
    val newPassword: List<T>
) : AutofillScenario<T>()

@RequiresApi(Build.VERSION_CODES.O)
data class GenericAutofillScenario<T: Any>(
    override val username: T?, override val fillUsername: Boolean, val genericPassword: List<T>
) : AutofillScenario<T>()

inline fun <T: Any, S: Any> AutofillScenario<T>.map(transform: (T) -> S): AutofillScenario<S> {
    val builder = AutofillScenario.Builder<S>()
    builder.username = username?.let(transform)
    builder.fillUsername = fillUsername
    when (this) {
        is ClassifiedAutofillScenario -> {
            builder.currentPassword.addAll(currentPassword.map(transform))
            builder.newPassword.addAll(newPassword.map(transform))
        }
        is GenericAutofillScenario -> {
            builder.genericPassword.addAll(genericPassword.map(transform))
        }
    }
    return builder.build()
}

@RequiresApi(Build.VERSION_CODES.O)
@JvmName("toBundleAutofillId")
private fun AutofillScenario<AutofillId>.toBundle(): Bundle = when (this) {
    is ClassifiedAutofillScenario<AutofillId> -> {
        Bundle(4).apply {
            putParcelable(AutofillScenario.BUNDLE_KEY_USERNAME_ID, username)
            putBoolean(AutofillScenario.BUNDLE_KEY_FILL_USERNAME, fillUsername)
            putParcelableArrayList(
                AutofillScenario.BUNDLE_KEY_CURRENT_PASSWORD_IDS, ArrayList(currentPassword)
            )
            putParcelableArrayList(
                AutofillScenario.BUNDLE_KEY_NEW_PASSWORD_IDS, ArrayList(newPassword)
            )
        }
    }
    is GenericAutofillScenario<AutofillId> -> {
        Bundle(3).apply {
            putParcelable(AutofillScenario.BUNDLE_KEY_USERNAME_ID, username)
            putBoolean(AutofillScenario.BUNDLE_KEY_FILL_USERNAME, fillUsername)
            putParcelableArrayList(
                AutofillScenario.BUNDLE_KEY_GENERIC_PASSWORD_IDS, ArrayList(genericPassword)
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@JvmName("toBundleFormField")
fun AutofillScenario<FormField>.toBundle(): Bundle = map { it.autofillId }.toBundle()

@RequiresApi(Build.VERSION_CODES.O)
fun AutofillScenario<AutofillId>.recoverNodes(structure: AssistStructure): AutofillScenario<AssistStructure.ViewNode>? {
    return map { autofillId ->
        structure.findNodeByAutofillId(autofillId) ?: return null
    }
}

val AutofillScenario<AssistStructure.ViewNode>.usernameValue: String?
    @RequiresApi(Build.VERSION_CODES.O) get() {
        val value = username?.autofillValue ?: return null
        return if (value.isText) value.textValue.toString() else null
    }
val AutofillScenario<AssistStructure.ViewNode>.passwordValue: String?
    @RequiresApi(Build.VERSION_CODES.O) get() {
        val distinctValues = passwordFields.map {
            if (it.autofillValue?.isText == true) {
                it.autofillValue?.textValue?.toString()
            } else {
                null
            }
        }.toSet()
        return distinctValues.singleOrNull()
    }