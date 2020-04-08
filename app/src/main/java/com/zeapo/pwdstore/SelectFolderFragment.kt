/*
 * Copyright © 2014-2020 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.zeapo.pwdstore

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.observe
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.zeapo.pwdstore.ui.adapters.FolderRecyclerAdapter
import com.zeapo.pwdstore.utils.PasswordItem
import java.io.File
import me.zhanghai.android.fastscroll.FastScrollerBuilder

class SelectFolderFragment : Fragment() {
    private lateinit var recyclerAdapter: FolderRecyclerAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var listener: OnFragmentInteractionListener

    private val model: SearchableRepositoryViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.password_recycler_view, container, false)
        initializePasswordList(view)
        val fab: FloatingActionButton = view.findViewById(R.id.fab)
        fab.hide()
        return view
    }

    private fun initializePasswordList(rootView: View) {
        recyclerAdapter = FolderRecyclerAdapter(listener)
        recyclerView = rootView.findViewById(R.id.pass_recycler)
        recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            addItemDecoration(
                DividerItemDecoration(
                    requireContext(),
                    DividerItemDecoration.VERTICAL
                )
            )
            itemAnimator = null
            adapter = recyclerAdapter
        }
        recyclerAdapter.makeSelectable(recyclerView)

        FastScrollerBuilder(recyclerView).build()
        registerForContextMenu(recyclerView)

        val path = requireNotNull(requireArguments().getString("Path"))
        model.navigateTo(File(path), pushPreviousLocation = false)
        model.passwordItemsList.observe(this) { list ->
            recyclerAdapter.submitList(list)
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        try {
            listener = object : OnFragmentInteractionListener {
                override fun onFragmentInteraction(item: PasswordItem) {
                    if (item.type == PasswordItem.TYPE_CATEGORY) {
                        model.navigateTo(item.file)
                        (requireActivity() as AppCompatActivity).supportActionBar?.setDisplayHomeAsUpEnabled(true)
                    }
                }
            }
        } catch (e: ClassCastException) {
            throw ClassCastException(
                    "$context must implement OnFragmentInteractionListener")
        }
    }

    val currentDir: File
        get() = model.currentDir.value!!

    interface OnFragmentInteractionListener {
        fun onFragmentInteraction(item: PasswordItem)
    }
}
