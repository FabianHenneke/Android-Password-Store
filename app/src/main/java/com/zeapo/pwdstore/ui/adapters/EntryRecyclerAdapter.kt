/*
 * Copyright © 2014-2020 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.zeapo.pwdstore.ui.adapters

import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.preference.PreferenceManager
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.widget.RecyclerView
import com.zeapo.pwdstore.R
import com.zeapo.pwdstore.SearchableRepositoryAdapter
import com.zeapo.pwdstore.utils.PasswordItem
import java.io.File

open class EntryRecyclerAdapter :
    SearchableRepositoryAdapter<EntryRecyclerAdapter.PasswordItemViewHolder>(
        R.layout.password_row_layout,
        ::PasswordItemViewHolder,
        PasswordItemViewHolder::bind
    ) {

    companion object {
        fun makeTracker(recyclerView: RecyclerView) =
            makeTracker(recyclerView, ::PasswordItemDetailsLookup)
    }

    class PasswordItemViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val name: AppCompatTextView = itemView.findViewById(R.id.label)
        private val typeImage: AppCompatImageView = itemView.findViewById(R.id.type_image)
        private val childCount: AppCompatTextView = itemView.findViewById(R.id.child_count)
        private val folderIndicator: AppCompatImageView =
            itemView.findViewById(R.id.folder_indicator)

        fun bind(item: PasswordItem) {
            val settings =
                PreferenceManager.getDefaultSharedPreferences(itemView.context.applicationContext)
            val showHidden = settings.getBoolean("show_hidden_folders", false)
            name.text = item.toString()
            if (item.type == PasswordItem.TYPE_CATEGORY) {
                typeImage.setImageResource(R.drawable.ic_multiple_files_24dp)
                folderIndicator.visibility = View.VISIBLE
                val children = item.file.listFiles { pathname ->
                    !(!showHidden && (pathname.isDirectory && pathname.isHidden))
                } ?: emptyArray<File>()
                val count = children.size
                childCount.visibility = if (count > 0) View.VISIBLE else View.GONE
                childCount.text = "$count"
            } else {
                typeImage.setImageResource(R.drawable.ic_action_secure_24dp)
                val parentPath = item.fullPathToParent.replace("(^/)|(/$)".toRegex(), "")
                val source = "$parentPath\n$item"
                val spannable = SpannableString(source)
                spannable.setSpan(RelativeSizeSpan(0.7f), 0, parentPath.length, 0)
                name.text = spannable
                childCount.visibility = View.GONE
                folderIndicator.visibility = View.GONE
            }
        }

        val itemDetails = object : ItemDetailsLookup.ItemDetails<Long>() {
            override fun getSelectionKey() = itemId
            override fun getPosition() = absoluteAdapterPosition
        }
    }

    class PasswordItemDetailsLookup(private val recyclerView: RecyclerView) :
        ItemDetailsLookup<Long>() {
        override fun getItemDetails(event: MotionEvent): ItemDetails<Long>? {
            val view = recyclerView.findChildViewUnder(event.x, event.y) ?: return null
            return (recyclerView.getChildViewHolder(view) as PasswordItemViewHolder).itemDetails
        }
    }
}
