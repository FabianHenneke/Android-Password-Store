package com.zeapo.pwdstore.autofill.oreo

import android.os.Build
import androidx.annotation.RequiresApi
import com.zeapo.pwdstore.autofill.oreo.CertaintyLevel.*

@RequiresApi(Build.VERSION_CODES.O)
val autofillStrategy = strategy {
    fillRule {
        stopOnMatch = true
        requiresMultiOriginSupport = false
        genericPassword {
            takeSingle {
                passwordCertainty == Certain && hasAutocompleteHintCurrentPassword && isFocused
            }
        }
        username {
            takeSingle { alreadyMatched ->
                usernameCertainty >= Likely && precedes(alreadyMatched.singleOrNull())
            }
        }
    }

    generateRule {
        requiresMultiOriginSupport = false
        genericPassword {
            takeSingle {
                passwordCertainty == Certain && hasAutocompleteHintNewPassword && isFocused
            }
        }
        username {
            takeSingle { alreadyMatched ->
                usernameCertainty >= Likely && precedes(alreadyMatched.singleOrNull())
            }
        }
    }

    fillRule {
        requiresMultiOriginSupport = false
        genericPassword {
            takeSingle { passwordCertainty >= Possible && isFocused }
            breakTieOnSingle { passwordCertainty >= Likely }
            breakTieOnSingle { passwordCertainty >= Certain }
        }
        username {
            takeSingle { alreadyMatched ->
                usernameCertainty >= Likely && precedes(alreadyMatched.singleOrNull())
            }
        }
    }
}