/*
 * Copyright © 2014-2019 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.zeapo.pwdstore.autofill.oreo.ui

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.view.WindowManager
import android.view.autofill.AutofillManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.preference.PreferenceManager
import com.afollestad.recyclical.datasource.dataSourceOf
import com.afollestad.recyclical.setup
import com.afollestad.recyclical.withItem
import com.zeapo.pwdstore.R
import com.zeapo.pwdstore.utils.PasswordItem
import com.zeapo.pwdstore.utils.PasswordRepository
import kotlinx.android.synthetic.main.activity_autofill_filter.*
import timber.log.Timber
import java.io.File

@TargetApi(Build.VERSION_CODES.O)
class AutofillFilterView : AppCompatActivity() {

    companion object {
        private const val TAG = "AutofillFilterView"
        private const val EXTRA_CANONICAL_DOMAIN = "com.zeapo.pwdstore.autofill.oreo.ui.EXTRA_CANONICAL_DOMAIN"
        private var matchAndDecryptFileRequestCode = 1

        fun makeMatchAndDecryptFileIntentSender(context: Context, canonicalDomain: String? = null): IntentSender {
            val intent = Intent(context, AutofillFilterView::class.java).apply {
                canonicalDomain?.let { putExtra(EXTRA_CANONICAL_DOMAIN, it) }
            }
            return PendingIntent.getActivity(context, matchAndDecryptFileRequestCode++, intent, PendingIntent.FLAG_CANCEL_CURRENT).intentSender
        }
    }

    private val dataSource = dataSourceOf()
    private val preferences by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private val sortOrder
        get() = PasswordRepository.PasswordSortOrder.getSortOrder(preferences)
    private lateinit var initialFilter: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_autofill_filter)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        // TODO: TaskAffinity?
        if (intent?.extras?.containsKey(AutofillManager.EXTRA_CLIENT_STATE) != true) {
            Timber.tag(TAG).e("AutofillFilterActivity started without EXTRA_CLIENT_STATE")
            finish()
            return
        }
        initialFilter = intent?.extras?.getString(EXTRA_CANONICAL_DOMAIN) ?: ""

        supportActionBar?.hide()
        bindUI()
        setResult(RESULT_CANCELED)
    }

    private fun bindUI() {
        // setup{} is an extension method on RecyclerView
        rvPassword.setup {
            withDataSource(dataSource)
            withItem<PasswordItem, PasswordViewHolder>(R.layout.password_row_layout) {
                onBind(::PasswordViewHolder) { _, item ->
                    val source = "${item.fullPathToParent}\n${item.name.dropLast(4)}"
                    val spannable = SpannableString(source).apply {
                        setSpan(RelativeSizeSpan(0.7f), 0, item.fullPathToParent.length, 0)
                    }
                    this.label.text = spannable
                    this.typeImage.setImageResource(R.drawable.ic_action_secure_24dp)
                }
                onClick { decryptAndFill(item) }
            }
        }

        search.addTextChangedListener { recursiveFilter(it.toString()) }
        search.setText(initialFilter, TextView.BufferType.EDITABLE)
        recursiveFilter(initialFilter, strict = true)

        overlay.setOnClickListener { finish() }
    }

    private fun decryptAndFill(item: PasswordItem) {
        // intent?.extras? is checked to be non-null in onCreate
        startActivityForResult(AutofillDecryptActivity.makeDecryptFileIntent(item.file, intent!!.extras!!, this), 1)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1) {
            if (resultCode == RESULT_OK)
                setResult(RESULT_OK, data)
            finish()
        }
    }

    @SuppressLint("DefaultLocale")
    private fun recursiveFilter(filter: String, dir: File? = null, strict: Boolean = true) {
        // on the root the pathStack is empty
        val passwordItems = if (dir == null) {
            PasswordRepository.getPasswords(PasswordRepository.getRepositoryDirectory(this), sortOrder)
        } else {
            PasswordRepository.getPasswords(dir, PasswordRepository.getRepositoryDirectory(this), sortOrder)
        }

        for (item in passwordItems) {
            if (item.type == PasswordItem.TYPE_CATEGORY) {
                recursiveFilter(filter, item.file, strict = strict)
            }

            val matches = if (strict)
                item.file.parentFile.name.let { it == filter || it.endsWith(".$filter") || it.endsWith("://$filter") }
            else
                "${item.file.absolutePath}/${item.file.nameWithoutExtension}".toLowerCase().contains(filter.toLowerCase())

            val inAdapter = dataSource.contains(item)
            if (item.type == PasswordItem.TYPE_PASSWORD && matches && !inAdapter) {
                dataSource.add(item)
            } else if (!matches && inAdapter) {
                dataSource.remove(item)
            }
        }
    }

}

