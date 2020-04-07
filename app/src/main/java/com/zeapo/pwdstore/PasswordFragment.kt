/*
 * Copyright © 2014-2020 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.zeapo.pwdstore

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import androidx.preference.PreferenceManager
import androidx.recyclerview.selection.SelectionTracker
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
import com.zeapo.pwdstore.utils.PasswordRepository.Companion.getPasswords
import com.zeapo.pwdstore.utils.PasswordRepository.Companion.getRepositoryDirectory
import com.zeapo.pwdstore.utils.PasswordRepository.PasswordSortOrder.Companion.getSortOrder
import kotlinx.android.synthetic.main.password_recycler_view.*
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import java.io.File
import java.util.Stack

/**
 * A fragment representing a list of Items.
 *
 * Large screen devices (such as tablets) are supported by replacing the ListView with a
 * GridView.
 *
 */

class PasswordFragment : Fragment() {
    private var pathStack: Stack<File> = Stack()
    private var scrollPosition: Stack<Int> = Stack()
    private lateinit var recyclerAdapter: PasswordRecyclerAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var selectionTracker: SelectionTracker<Long>
    private lateinit var listener: OnFragmentInteractionListener
    private lateinit var settings: SharedPreferences
    private lateinit var swipeRefresher: SwipeRefreshLayout

    private val model: SearchableRepositoryViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = PreferenceManager.getDefaultSharedPreferences(requireActivity())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.password_recycler_view, container, false)
        // use a linear layout manager
        val layoutManager = LinearLayoutManager(requireContext())
        swipeRefresher = view.findViewById(R.id.swipe_refresher)
        swipeRefresher.setOnRefreshListener {
            if (!PasswordRepository.isGitRepo()) {
                Snackbar.make(view, getString(R.string.clone_git_repo), Snackbar.LENGTH_SHORT).show()
                swipeRefresher.isRefreshing = false
            } else {
                val intent = Intent(context, GitActivity::class.java)
                intent.putExtra("Operation", GitActivity.REQUEST_SYNC)
                startActivityForResult(intent, GitActivity.REQUEST_SYNC)
            }
        }
        recyclerView = view.findViewById(R.id.pass_recycler)
        recyclerView.layoutManager = layoutManager
        // use divider
        recyclerView.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))
        // Set the adapter
        recyclerAdapter =
            PasswordRecyclerAdapter(requireActivity() as PasswordStore, listener)

        recyclerView.adapter = recyclerAdapter
        recyclerAdapter.setSelectionTracker(makeTracker(recyclerView))
        // Setup fast scroller
        FastScrollerBuilder(recyclerView).build()
        val fab = view.findViewById<FloatingActionButton>(R.id.fab)
        fab.setOnClickListener {
            toggleFabExpand(fab)
        }

        view.findViewById<FloatingActionButton>(R.id.create_folder).setOnClickListener {
            (requireActivity() as PasswordStore).createFolder()
            toggleFabExpand(fab)
        }

        view.findViewById<FloatingActionButton>(R.id.create_password).setOnClickListener {
            (requireActivity() as PasswordStore).createPassword()
            toggleFabExpand(fab)
        }
        registerForContextMenu(recyclerView)
        val currentDir = File(requireNotNull(requireArguments().getString("Path")))
        pathStack.push(currentDir)
        model.list(currentDir)
        model.passwordItemsList.observe(this, Observer { list -> recyclerAdapter.submitList(list) })
        return view
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
                        // push the category were we're going
                        pathStack.push(item.file)
                        // scrollPosition.push((recyclerView.layoutManager as LinearLayoutManager).findLastCompletelyVisibleItemPosition())
                        model.list(item.file)
                        (requireActivity() as AppCompatActivity).supportActionBar?.setDisplayHomeAsUpEnabled(true)
                    } else {
                        if (requireArguments().getBoolean("matchWith", false)) {
                            (requireActivity() as PasswordStore).matchPasswordWithApp(item)
                        } else {
                            (requireActivity() as PasswordStore).decryptPassword(item)
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

    /** clears the adapter content and sets it back to the root view  */
    fun updateAdapter() {
        pathStack.clear()
        // scrollPosition.clear()
        model.list()
        (requireActivity() as AppCompatActivity).supportActionBar?.setDisplayHomeAsUpEnabled(false)
    }

    /** refreshes the adapter with the latest opened category  */
    fun refreshAdapter() {
        model.forceRefresh()
    }

    /** Goes back one level back in the path  */
    fun popBack() {
        if (pathStack.isEmpty()) return
        model.list(pathStack.pop())
    }

    /**
     * gets the current directory
     *
     * @return the current directory
     */
    val currentDir: File?
        get() = if (pathStack.isEmpty()) getRepositoryDirectory(requireContext()) else pathStack.peek()

    val isNotEmpty: Boolean
        get() = !pathStack.isEmpty()

    fun dismissActionMode() {
        recyclerAdapter.actionMode?.finish()
    }

    interface OnFragmentInteractionListener {
        fun onFragmentInteraction(item: PasswordItem)
    }
}
