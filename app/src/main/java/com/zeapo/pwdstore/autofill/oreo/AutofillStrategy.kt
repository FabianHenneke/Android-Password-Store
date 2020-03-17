/*
 * Copyright © 2014-2020 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.zeapo.pwdstore.autofill.oreo

import android.os.Build
import androidx.annotation.RequiresApi
import com.zeapo.pwdstore.autofill.oreo.CertaintyLevel.Certain
import com.zeapo.pwdstore.autofill.oreo.CertaintyLevel.Likely

private inline fun <T> Pair<T, T>.all(predicate: (T) -> Boolean) =
    predicate(first) && predicate(second)

private inline fun <T> Pair<T, T>.any(predicate: (T) -> Boolean) =
    predicate(first) || predicate(second)

private inline fun <T> Pair<T, T>.none(predicate: (T) -> Boolean) =
    !predicate(first) && !predicate(second)

@RequiresApi(Build.VERSION_CODES.O)
val autofillStrategy = strategy {
    rule {
        newPassword {
            takePair {
                all { it.passwordCertainty >= Likely && it.hasAutocompleteHintNewPassword }
            }
            breakTieOnPair { all { it.passwordCertainty >= Certain } }
        }
        username(optional = true) {
            takeSingle { alreadyMatched ->
                usernameCertainty >= Likely && directlyPrecedes(alreadyMatched)
            }
        }
    }

    rule {
        currentPassword {
            takeSingle {
                passwordCertainty >= Likely && hasAutocompleteHintCurrentPassword
            }
            breakTieOnSingle { passwordCertainty >= Certain }
        }
        username(optional = true) {
            takeSingle { alreadyMatched ->
                usernameCertainty >= Likely && directlyPrecedes(alreadyMatched.singleOrNull())
            }
        }
    }

    rule(applyInSingleOriginMode = true) {
        newPassword {
            takeSingle {
                passwordCertainty >= Likely && hasAutocompleteHintNewPassword && isFocused
            }
        }
        username(optional = true) {
            takeSingle { alreadyMatched ->
                usernameCertainty >= Likely && directlyPrecedes(alreadyMatched.singleOrNull())
            }
        }
    }

    rule(applyInSingleOriginMode = true) {
        currentPassword {
            takeSingle {
                passwordCertainty >= Likely && hasAutocompleteHintCurrentPassword && isFocused
            }
        }
        username(optional = true) {
            takeSingle { alreadyMatched ->
                usernameCertainty >= Likely && directlyPrecedes(alreadyMatched.singleOrNull())
            }
        }
    }

    rule(applyInSingleOriginMode = true) {
        genericPassword {
            takeSingle {
                passwordCertainty >= Likely && isFocused
            }
        }
        username(optional = true) {
            takeSingle { alreadyMatched ->
                usernameCertainty >= Likely && directlyPrecedes(alreadyMatched.singleOrNull())
            }
        }
    }
}