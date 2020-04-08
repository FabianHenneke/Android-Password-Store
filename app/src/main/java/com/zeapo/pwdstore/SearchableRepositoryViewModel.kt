/*
 * Copyright © 2014-2020 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.zeapo.pwdstore

import android.app.Application
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asFlow
import androidx.lifecycle.asLiveData
import androidx.preference.PreferenceManager
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.SelectionPredicates
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.selection.StableIdKeyProvider
import androidx.recyclerview.selection.StorageStrategy
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.zeapo.pwdstore.autofill.oreo.AutofillPreferences
import com.zeapo.pwdstore.autofill.oreo.DirectoryStructure
import com.zeapo.pwdstore.utils.PasswordItem
import com.zeapo.pwdstore.utils.PasswordRepository
import java.io.File
import java.text.Collator
import java.util.Locale
import java.util.Stack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.yield
import me.zhanghai.android.fastscroll.PopupTextProvider

private fun File.toPasswordItem(root: File) = if (isFile)
    PasswordItem.newPassword(name, this, root)
else
    PasswordItem.newCategory(name, this, root)

private fun PasswordItem.fuzzyMatch(filter: String): Int {
    var i = 0
    var j = 0
    var score = 0
    var bonus = 0
    var bonusIncrement = 0

    val toMatch = longName

    while (i < filter.length && j < toMatch.length) {
        when {
            filter[i].isWhitespace() -> i++
            filter[i].toLowerCase() == toMatch[j].toLowerCase() -> {
                i++
                bonusIncrement += 1
                bonus += bonusIncrement
                score += bonus
            }
            else -> {
                bonus = 0
                bonusIncrement = 0
            }
        }
        j++
    }
    return if (i == filter.length) score else 0
}

private val CaseInsensitiveComparator = Collator.getInstance().apply {
    strength = Collator.PRIMARY
}

private fun PasswordItem.Companion.makeComparator(
    typeSortOrder: PasswordRepository.PasswordSortOrder,
    directoryStructure: DirectoryStructure
): Comparator<PasswordItem> {
    return when (typeSortOrder) {
        PasswordRepository.PasswordSortOrder.FOLDER_FIRST -> compareBy { it.type }
        PasswordRepository.PasswordSortOrder.INDEPENDENT -> compareBy<PasswordItem>()
        PasswordRepository.PasswordSortOrder.FILE_FIRST -> compareByDescending { it.type }
    }
        .then(compareBy(nullsLast(CaseInsensitiveComparator)) {
            directoryStructure.getIdentifierFor(
                it.file
            )
        })
        .then(compareBy(CaseInsensitiveComparator) { directoryStructure.getUsernameFor(it.file) })
}

enum class FilterMode {
    NoFilter,
    StrictDomain,
    Fuzzy
}

enum class SearchMode {
    RecursivelyInSubdirectories,
    InCurrentDirectoryOnly
}

private data class SearchAction(
    val currentDir: File,
    val filter: String,
    val filterMode: FilterMode,
    val searchMode: SearchMode,
    val listFilesOnly: Boolean,
    val updateCounter: Int
)

@ExperimentalCoroutinesApi
@FlowPreview
class SearchableRepositoryViewModel(application: Application) : AndroidViewModel(application) {

    private var _updateCounter = 0
    private val updateCounter: Int
        get() = _updateCounter

    private fun increaseUpdateCounter() {
        _updateCounter++
    }

    private val root = PasswordRepository.getRepositoryDirectory(application)
    private val settings = PreferenceManager.getDefaultSharedPreferences(getApplication())
    private val showHiddenDirs = settings.getBoolean("show_hidden_folders", false)
    private val defaultSearchMode = if (settings.getBoolean("filter_recursively", true)) {
        SearchMode.RecursivelyInSubdirectories
    } else {
        SearchMode.InCurrentDirectoryOnly
    }

    private val typeSortOrder = PasswordRepository.PasswordSortOrder.getSortOrder(settings)
    private val directoryStructure = AutofillPreferences.directoryStructure(application)
    private val itemComparator = PasswordItem.makeComparator(typeSortOrder, directoryStructure)

    private val searchAction = MutableLiveData(
        SearchAction(
            currentDir = root,
            filter = "",
            filterMode = FilterMode.NoFilter,
            searchMode = SearchMode.InCurrentDirectoryOnly,
            listFilesOnly = true,
            updateCounter = updateCounter
        )
    )
    private val searchActionFlow = searchAction.asFlow()
        .map { it.copy(filter = it.filter.trim()) }
        .distinctUntilChanged()

    private val passwordItemsFlow = searchActionFlow
        .mapLatest { searchAction ->
            val listResultFlow = when (searchAction.searchMode) {
                SearchMode.RecursivelyInSubdirectories -> listFilesRecursively(searchAction.currentDir)
                SearchMode.InCurrentDirectoryOnly -> listFiles(searchAction.currentDir)
            }
            val prefilteredResultFlow =
                if (searchAction.listFilesOnly) listResultFlow.filter { it.isFile } else listResultFlow
            val filterModeToUse =
                if (searchAction.filter == "") FilterMode.NoFilter else searchAction.filterMode
            when (filterModeToUse) {
                FilterMode.NoFilter -> {
                    prefilteredResultFlow
                        .map { it.toPasswordItem(root) }
                        .toList()
                        .sortedWith(itemComparator)
                }
                FilterMode.StrictDomain -> {
                    check(searchAction.listFilesOnly) { "Searches with StrictDomain search mode can only list files" }
                    prefilteredResultFlow
                        .filter { file ->
                            val toMatch =
                                directoryStructure.getIdentifierFor(file) ?: return@filter false
                            // In strict domain mode, we match
                            // * the search term exactly,
                            // * subdomains of the search term,
                            // * or the search term plus an arbitrary protocol.
                            toMatch == searchAction.filter ||
                                toMatch.endsWith(".${searchAction.filter}") ||
                                toMatch.endsWith("://${searchAction.filter}")
                        }
                        .map { it.toPasswordItem(root) }
                        .toList()
                        .sortedWith(itemComparator)
                }
                FilterMode.Fuzzy -> {
                    prefilteredResultFlow
                        .map {
                            val item = it.toPasswordItem(root)
                            Pair(item.fuzzyMatch(searchAction.filter), item)
                        }
                        .filter { it.first > 0 }
                        .toList()
                        .sortedWith(
                            compareByDescending<Pair<Int, PasswordItem>> { it.first }.thenBy(
                                itemComparator
                            ) { it.second })
                        .map { it.second }
                }
            }
        }

    private fun shouldTake(file: File) = with(file) {
        if (isDirectory) {
            !isHidden || showHiddenDirs
        } else {
            !isHidden && file.extension == "gpg"
        }
    }

    private fun listFiles(dir: File): Flow<File> {
        return dir.listFiles { file -> shouldTake(file) }?.asFlow() ?: emptyFlow()
    }

    private fun listFilesRecursively(dir: File): Flow<File> {
        return dir
            .walkTopDown().onEnter { file -> shouldTake(file) }
            .asFlow()
            .map {
                yield()
                it
            }
            .filter { file -> shouldTake(file) }
    }

    private val passwordItemsNavigation = MutableLiveData<List<PasswordItem>>()
    val passwordItemsList = listOf(passwordItemsFlow, passwordItemsNavigation.asFlow()).merge()
        .asLiveData(Dispatchers.IO)

    var currentDir = root
        private set

    data class NavigationStackEntry(
        val dir: File,
        val items: List<PasswordItem>?,
        val recyclerViewState: Parcelable?
    )

    private val navigationStack = Stack<NavigationStackEntry>()

    fun navigateTo(
        newDirectory: File = root,
        recyclerViewState: Parcelable? = null,
        pushPreviousLocation: Boolean = true
    ) {
        require(newDirectory.isDirectory) { "Can only navigate to a directory" }
        if (pushPreviousLocation) {
            // We cache the current list entries only if the current list has not been filtered,
            // otherwise it will be regenerated when moving back.
            if (searchAction.value?.filterMode == FilterMode.NoFilter)
                navigationStack.push(
                    NavigationStackEntry(
                        currentDir,
                        passwordItemsList.value,
                        recyclerViewState
                    )
                )
            else
                navigationStack.push(NavigationStackEntry(currentDir, null, recyclerViewState))
        }
        currentDir = newDirectory
        searchAction.postValue(
            SearchAction(
                filter = "",
                currentDir = currentDir,
                filterMode = FilterMode.NoFilter,
                searchMode = SearchMode.InCurrentDirectoryOnly,
                listFilesOnly = false,
                updateCounter = updateCounter
            )
        )
    }

    val canNavigateBack
        get() = navigationStack.isNotEmpty()

    /**
     * Navigate back to the last location on the [navigationStack] using a cached list of entries
     * if possible.
     *
     * Returns the old RecyclerView state as a [Parcelable] if it was cached.
     */
    fun navigateBack(): Parcelable? {
        if (!canNavigateBack) return null
        val (oldDir, oldPasswordItems, oldRecyclerViewState) = navigationStack.pop()
        return if (oldPasswordItems != null) {
            // We cached the contents of oldDir and restore them directly without file operations.
            passwordItemsNavigation.postValue(oldPasswordItems)
            currentDir = oldDir
            oldRecyclerViewState
        } else {
            navigateTo(oldDir, pushPreviousLocation = false)
            null
        }
    }

    fun reset() {
        navigationStack.clear()
        navigateTo(pushPreviousLocation = false)
    }

    fun search(
        filter: String,
        currentDir: File? = null,
        filterMode: FilterMode = FilterMode.Fuzzy,
        searchMode: SearchMode? = null,
        listFilesOnly: Boolean = false
    ) {
        require(currentDir?.isDirectory != false) { "Can only search in a directory" }
        val action = SearchAction(
            filter = filter.trim(),
            currentDir = currentDir ?: searchAction.value!!.currentDir,
            filterMode = filterMode,
            searchMode = searchMode ?: defaultSearchMode,
            listFilesOnly = listFilesOnly,
            updateCounter = updateCounter
        )
        searchAction.postValue(action)
    }

    fun forceRefresh() {
        increaseUpdateCounter()
        searchAction.postValue(searchAction.value!!.copy(updateCounter = updateCounter))
    }
}

private object PasswordItemDiffCallback : DiffUtil.ItemCallback<PasswordItem>() {
    override fun areItemsTheSame(oldItem: PasswordItem, newItem: PasswordItem) =
        oldItem.file.absolutePath == newItem.file.absolutePath

    override fun areContentsTheSame(oldItem: PasswordItem, newItem: PasswordItem) = oldItem == newItem
}

open class SearchableRepositoryAdapter<T : RecyclerView.ViewHolder>(
    private val layoutRes: Int,
    private val viewHolderCreator: (view: View) -> T,
    private val viewHolderBinder: T.(item: PasswordItem) -> Unit
) : ListAdapter<PasswordItem, T>(PasswordItemDiffCallback), PopupTextProvider {

    companion object {
        fun <T : ItemDetailsLookup<Long>> makeTracker(
            recyclerView: RecyclerView,
            itemDetailsLookupCreator: (recyclerView: RecyclerView) -> T
        ): SelectionTracker<Long> {
            return SelectionTracker.Builder(
                "SearchableRepositoryAdapter",
                recyclerView,
                StableIdKeyProvider(recyclerView),
                itemDetailsLookupCreator(recyclerView),
                StorageStrategy.createLongStorage()
            ).withSelectionPredicate(SelectionPredicates.createSelectAnything()).build()
        }
    }

    open fun onItemClicked(holder: T, item: PasswordItem) {}

    open fun onSelectionChanged() {}

    private var selectionTracker: SelectionTracker<Long>? = null
    fun requireSelectionTracker() = selectionTracker!!
    fun setSelectionTracker(value: SelectionTracker<Long>) {
        value.addObserver(object : SelectionTracker.SelectionObserver<Long>() {
            override fun onSelectionChanged() {
                this@SearchableRepositoryAdapter.onSelectionChanged()
            }
        })
        selectionTracker = value
    }

    init {
        setHasStableIds(true)
    }

    final override fun setHasStableIds(hasStableIds: Boolean) = super.setHasStableIds(hasStableIds)

    final override fun getItemId(position: Int) = position.toLong()

    final override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): T {
        val view = LayoutInflater.from(parent.context)
            .inflate(layoutRes, parent, false)
        return viewHolderCreator(view)
    }

    final override fun onBindViewHolder(holder: T, position: Int) {
        val item = getItem(position)
        holder.apply {
            viewHolderBinder.invoke(this, item)
            selectionTracker?.let { itemView.isSelected = it.isSelected(position.toLong()) }
            itemView.setOnClickListener {
                // Do not emit custom click events while the user is selecting items.
                if (selectionTracker?.hasSelection() != true)
                    onItemClicked(holder, item)
            }
        }
    }

    final override fun getPopupText(position: Int): String {
        return getItem(position).name[0].toString().toUpperCase(Locale.getDefault())
    }
}
