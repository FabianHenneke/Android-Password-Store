/*
 * Copyright © 2014-2019 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.zeapo.pwdstore.autofill.oreo.ui

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
import com.zeapo.pwdstore.autofill.oreo.AutofillMatcher
import com.zeapo.pwdstore.autofill.oreo.FormOrigin
import com.zeapo.pwdstore.utils.PasswordItem
import com.zeapo.pwdstore.utils.PasswordRepository
import kotlinx.android.synthetic.main.activity_oreo_autofill_filter.*
import timber.log.Timber
import java.io.File
import java.util.*

@TargetApi(Build.VERSION_CODES.O)
class AutofillFilterView : AppCompatActivity() {

    companion object {
        private const val TAG = "AutofillFilterView"
        private const val EXTRA_FORM_ORIGIN_WEB = "com.zeapo.pwdstore.autofill.oreo.ui.EXTRA_FORM_ORIGIN_WEB"
        private const val EXTRA_FORM_ORIGIN_APP = "com.zeapo.pwdstore.autofill.oreo.ui.EXTRA_FORM_ORIGIN_APP"
        private var matchAndDecryptFileRequestCode = 1

        fun makeMatchAndDecryptFileIntentSender(context: Context, formOrigin: FormOrigin): IntentSender {
            val intent = Intent(context, AutofillFilterView::class.java).apply {
                when (formOrigin) {
                    is FormOrigin.Web -> putExtra(EXTRA_FORM_ORIGIN_WEB, formOrigin.identifier)
                    is FormOrigin.App -> putExtra(EXTRA_FORM_ORIGIN_APP, formOrigin.identifier)
                }
            }
            return PendingIntent.getActivity(context, matchAndDecryptFileRequestCode++, intent, PendingIntent.FLAG_CANCEL_CURRENT).intentSender
        }
    }

    private val dataSource = dataSourceOf()
    private val preferences by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private val sortOrder
        get() = PasswordRepository.PasswordSortOrder.getSortOrder(preferences)

    private lateinit var formOrigin: FormOrigin

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_oreo_autofill_filter)

        if (intent?.hasExtra(AutofillManager.EXTRA_CLIENT_STATE) != true) {
            Timber.tag(TAG).e("AutofillFilterActivity started without EXTRA_CLIENT_STATE")
            finish()
            return
        }
        formOrigin = when {
            intent?.hasExtra(EXTRA_FORM_ORIGIN_WEB) == true -> {
                FormOrigin.Web(intent!!.getStringExtra(EXTRA_FORM_ORIGIN_WEB)!!)
            }
            intent?.hasExtra(EXTRA_FORM_ORIGIN_APP) == true -> {
                FormOrigin.App(intent!!.getStringExtra(EXTRA_FORM_ORIGIN_APP)!!)
            }
            else -> {
                Timber.tag(TAG).e("AutofillFilterActivity started without EXTRA_FORM_ORIGIN_WEB or EXTRA_FORM_ORIGIN_APP")
                finish()
                return
            }
        }

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
        val initialFilter = formOrigin.getPrettyIdentifier(applicationContext, indicateTrust = false)
        search.setText(initialFilter, TextView.BufferType.EDITABLE)
        recursiveFilter(initialFilter, strict = formOrigin is FormOrigin.Web)

        shouldMatch.apply {
            text = getString(R.string.oreo_autofill_match_with, formOrigin.getPrettyIdentifier(applicationContext))
        }

        overlay.setOnClickListener {
            finish()
        }
    }

    private fun decryptAndFill(item: PasswordItem) {
        if (shouldClear.isChecked)
            AutofillMatcher.clearMatchesFor(applicationContext, formOrigin)
        if (shouldMatch.isChecked)
            AutofillMatcher.addMatchFor(applicationContext, formOrigin, item.file)
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
                "${item.file.absolutePath}/${item.file.nameWithoutExtension}".toLowerCase(Locale.getDefault()).contains(filter.toLowerCase(Locale.getDefault()))

            val inAdapter = dataSource.contains(item)
            if (item.type == PasswordItem.TYPE_PASSWORD && matches && !inAdapter) {
                dataSource.add(item)
            } else if (!matches && inAdapter) {
                dataSource.remove(item)
            }
        }
    }

}
