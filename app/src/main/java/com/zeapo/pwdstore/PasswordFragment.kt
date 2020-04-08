/*
 * Copyright © 2014-2020 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.zeapo.pwdstore

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.zeapo.pwdstore.git.GitActivity
import com.zeapo.pwdstore.ui.adapters.EntryRecyclerAdapter.Companion.makeTracker
import com.zeapo.pwdstore.ui.adapters.PasswordRecyclerAdapter
import com.zeapo.pwdstore.utils.PasswordItem
import com.zeapo.pwdstore.utils.PasswordRepository
import java.io.File
import me.zhanghai.android.fastscroll.FastScrollerBuilder

class PasswordFragment : Fragment() {
    private lateinit var recyclerAdapter: PasswordRecyclerAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var listener: OnFragmentInteractionListener
    private lateinit var swipeRefresher: SwipeRefreshLayout

    private var recyclerViewStateToRestore: Parcelable? = null

    private val model: SearchableRepositoryViewModel by activityViewModels()

    private fun requireStore() = requireActivity() as PasswordStore

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.password_recycler_view, container, false)
        initializePasswordList(view)
        val fab = view.findViewById<FloatingActionButton>(R.id.fab)
        fab.setOnClickListener {
            toggleFabExpand(fab)
        }
        view.findViewById<FloatingActionButton>(R.id.create_folder).setOnClickListener {
            requireStore().createFolder()
            toggleFabExpand(fab)
        }
        view.findViewById<FloatingActionButton>(R.id.create_password).setOnClickListener {
            requireStore().createPassword()
            toggleFabExpand(fab)
        }
        return view
    }

    private fun initializePasswordList(rootView: View) {
        swipeRefresher = rootView.findViewById(R.id.swipe_refresher)
        swipeRefresher.setOnRefreshListener {
            if (!PasswordRepository.isGitRepo()) {
                Snackbar.make(rootView, getString(R.string.clone_git_repo), Snackbar.LENGTH_SHORT)
                    .show()
                swipeRefresher.isRefreshing = false
            } else {
                val intent = Intent(context, GitActivity::class.java)
                intent.putExtra("Operation", GitActivity.REQUEST_SYNC)
                startActivityForResult(intent, GitActivity.REQUEST_SYNC)
            }
        }

        recyclerAdapter = PasswordRecyclerAdapter(requireStore(), listener)
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
        recyclerAdapter.setSelectionTracker(makeTracker(recyclerView))

        FastScrollerBuilder(recyclerView).build()
        registerForContextMenu(recyclerView)

        val path = requireNotNull(requireArguments().getString("Path"))
        model.navigateTo(File(path), pushPreviousLocation = false)
        model.passwordItemsList.observe(
            this,
            Observer { list ->
                recyclerAdapter.submitList(list) {
                    recyclerViewStateToRestore?.let {
                        recyclerView.layoutManager?.onRestoreInstanceState(it)
                    }
                    recyclerViewStateToRestore = null
                }
            })
    }

    private fun toggleFabExpand(fab: FloatingActionButton) = with(fab) {
        isExpanded = !isExpanded
        isActivated = isExpanded
        animate().rotationBy(if (isExpanded) -45f else 45f).setDuration(100).start()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        try {
            listener = object : OnFragmentInteractionListener {
                override fun onFragmentInteraction(item: PasswordItem) {
                    if (item.type == PasswordItem.TYPE_CATEGORY) {
                        requireStore().clearSearch()
                        model.navigateTo(
                            item.file,
                            recyclerViewState = recyclerView.layoutManager?.onSaveInstanceState()
                        )
                        requireStore().supportActionBar?.setDisplayHomeAsUpEnabled(true)
                    } else {
                        if (requireArguments().getBoolean("matchWith", false)) {
                            requireStore().matchPasswordWithApp(item)
                        } else {
                            requireStore().decryptPassword(item)
                        }
                    }
                }
            }
        } catch (e: ClassCastException) {
            throw ClassCastException("$context must implement OnFragmentInteractionListener")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        swipeRefresher.isRefreshing = false
    }

    /**
     * Returns true if the back press was handled by the [Fragment].
     */
    fun onBackPressedInActivity(): Boolean {
        if (!model.canNavigateBack)
            return false
        // The RecyclerView state is restored when the asynchronous update operation on the
        // adapter is completed.
        recyclerViewStateToRestore = model.navigateBack()
        if (!model.canNavigateBack)
            requireStore().disableNavigationIndicator()
        return true
    }

    /** clears the adapter content and sets it back to the root view  */
    fun resetAdapter() {
        model.reset()
        requireStore().supportActionBar?.setDisplayHomeAsUpEnabled(false)
    }

    /** refreshes the adapter with the latest opened category  */
    fun refreshAdapter() {
        model.forceRefresh()
    }

    /**
     * gets the current directory
     *
     * @return the current directory
     */
    val currentDir: File
        get() = model.currentDir

    fun dismissActionMode() {
        recyclerAdapter.actionMode?.finish()
    }

    interface OnFragmentInteractionListener {
        fun onFragmentInteraction(item: PasswordItem)
    }
}
