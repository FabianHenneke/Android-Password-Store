/*
 * Copyright © 2014-2020 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.zeapo.pwdstore.sshkeygen

import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zeapo.pwdstore.R
import com.zeapo.pwdstore.databinding.FragmentSshKeygenBinding
import com.zeapo.pwdstore.utils.PROVIDER_ANDROID_KEY_STORE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

enum class KeyGenType(val algorithm: String, val keyLength: Int) {
    Rsa2048(KeyProperties.KEY_ALGORITHM_RSA, 2048),
    Rsa3072(KeyProperties.KEY_ALGORITHM_RSA, 3072),
    Ecdsa384(KeyProperties.KEY_ALGORITHM_EC, 384)
}

class SshKeyGenFragment : Fragment() {

    private var keyType = KeyGenType.Rsa3072
    private var _binding: FragmentSshKeygenBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentSshKeygenBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        with(binding) {
            generate.setOnClickListener {
                lifecycleScope.launch { generate(passphrase.text.toString(), comment.text.toString()) }
            }
            keyLengthGroup.check(R.id.key_type_rsa_3072)
            keyLengthGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (isChecked) {
                    keyType = when (checkedId) {
                        R.id.key_type_rsa_2048 -> KeyGenType.Rsa2048
                        R.id.key_type_rsa_3072 -> KeyGenType.Rsa3072
                        R.id.key_type_ecdsa_384 -> KeyGenType.Ecdsa384
                        else -> throw IllegalStateException("Invalid key type selection")
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // Invoked when 'Generate' button of SshKeyGenFragment clicked. Generates a
    // private and public key, then replaces the SshKeyGenFragment with a
    // ShowSshKeyFragment which displays the public key.
    private suspend fun generate(passphrase: String, comment: String) {
        binding.generate.text = getString(R.string.ssh_key_gen_generating_progress)
        val e = try {
            withContext(Dispatchers.IO) {
                val parameterSpec = KeyGenParameterSpec.Builder(
                    "ssh_key",
                    KeyProperties.PURPOSE_SIGN
                ).run {
                    setKeySize(keyType.keyLength)
                    when (keyType) {
                        KeyGenType.Rsa2048, KeyGenType.Rsa3072 -> {
                            setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                            setDigests(KeyProperties.DIGEST_SHA1)
                        }
                        KeyGenType.Ecdsa384 -> {
                            setAlgorithmParameterSpec(ECGenParameterSpec("secp384r1"))
                            setDigests(KeyProperties.DIGEST_SHA384)
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        setUnlockedDeviceRequired(true)
                    }
                    build()
                }
                val keyPair = KeyPairGenerator.getInstance(keyType.algorithm, PROVIDER_ANDROID_KEY_STORE).run {
                    initialize(parameterSpec)
                    generateKeyPair()
                }
                val keyType = KeyType.fromKey(keyPair.public)
                val rawPublicKey = Buffer.PlainBuffer().run {
                    keyType.putPubKeyIntoBuffer(keyPair.public, this)
                    compactData
                }
                val encodedPublicKey = Base64.encodeToString(rawPublicKey, Base64.NO_WRAP)
                val sshPublicKey = "$keyType $encodedPublicKey $comment"
                File(requireActivity().filesDir, ".ssh_key").writeText("keystore")
                File(requireActivity().filesDir, ".ssh_key.pub").writeText(sshPublicKey)
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
            e
        }
        val activity = requireActivity()
        binding.generate.text = getString(R.string.ssh_keygen_generating_done)
        if (e == null) {
            val df = ShowSshKeyFragment()
            df.show(requireActivity().supportFragmentManager, "public_key")
            val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
            prefs.edit { putBoolean("use_generated_key", true) }
        } else {
            MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.error_generate_ssh_key))
                .setMessage(activity.getString(R.string.ssh_key_error_dialog_text) + e.message)
                .setPositiveButton(activity.getString(R.string.dialog_ok)) { _, _ ->
                    requireActivity().finish()
                }
                .show()
        }
        hideKeyboard()
    }

    private fun hideKeyboard() {
        val activity = activity ?: return
        val imm = activity.getSystemService<InputMethodManager>() ?: return
        var view = activity.currentFocus
        if (view == null) {
            view = View(activity)
        }
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }
}
