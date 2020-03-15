package com.zeapo.pwdstore.autofill.oreo

import android.os.Build
import androidx.annotation.RequiresApi
import com.zeapo.pwdstore.autofill.oreo.CertaintyLevel.*

@RequiresApi(Build.VERSION_CODES.O)
val autofillStrategy = strategy {
    rule(applyInSingleOriginMode = true) {
        newPassword {
            takeSingle {
                passwordCertainty >= Likely && hasAutocompleteHintNewPassword && isFocused
            }
        }
        username {
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
        username {
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
        username {
            takeSingle { alreadyMatched ->
                usernameCertainty >= Likely && directlyPrecedes(alreadyMatched.singleOrNull())
            }
        }
    }
}