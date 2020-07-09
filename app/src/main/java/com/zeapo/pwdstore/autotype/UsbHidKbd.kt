package com.zeapo.pwdstore.autotype

import java.util.HashMap
import java.util.NoSuchElementException

/**
 * Authorizer
 *
 *  Copyright 2016 by Tjado Mäcke <tjado@maecke.de>
 *  Licensed under GNU General Public License 3.0.
 *
 * @license GPL-3.0 <https://opensource.org/licenses/GPL-3.0>
 */


abstract class UsbHidKbd {

    // ToDo: replace byte with ByteArray... eveywhere
    protected var kbdVal: MutableMap<String?, ByteArray> = HashMap()
    fun getScancode(key: String?): ByteArray {
        return kbdVal[key]
            ?: throw NoSuchElementException("Scancode for '" + key + "' not found (" + kbdVal.size + ")")
    }
}