package io.legado.app.model.webBook

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppConst.timeLimit
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.releaseHtmlData
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.SourceAccountRequiredHelp
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.mapParallelSafe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import splitties.init.appCtx
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

class SearchModel(private val scope: CoroutineScope, private val callBack: CallBack) {
    val threadCount = AppConfig.searchConcurrency
    private var searchPool: ExecutorCoroutineDispatcher? = null
    private var mSearchId = 0L
    private var searchPage = 1
    private var searchKey: String = ""
    private var bookSources = emptyList<BookSource>()
    private var searchBooks = arrayListOf<SearchBook>()
    private var searchJob: Job? = null

    private fun initSearchPool(): ExecutorCoroutineDispatcher {
        return Executors
            .newFixedThreadPool(min(threadCount, AppConst.MAX_THREAD)).asCoroutineDispatcher()
    }

    fun search(searchId: Long, key: String) {
        if (searchId != mSearchId) {
            if (key.isEmpty()) {
                return
            }
            searchKey = key
            if (mSearchId != 0L) {
                close()
            }
            searchBooks.clear()
            bookSources = callBack.getSearchScope().getBookSources()
            if (bookSources.isEmpty()) {
                callBack.onSearchCancel(NoStackTraceException("启用书源为空"))
                return
            }
            mSearchId = searchId
            searchPage = 1
            searchPool = initSearchPool()
        } else {
            searchPage++
        }
        startSearch()
    }

    private fun startSearch() {
        val precision = appCtx.getPrefBoolean(PreferKey.precisionSearch)
        var hasMore = false
        val pool = searchPool ?: return
        val activeSources = bookSources.filter { it.enabled }
        if (activeSources.isEmpty()) {
            callBack.onSearchCancel(NoStackTraceException("启用书源为空"))
            return
        }
        val completedSources = AtomicInteger()
        val totalSources = activeSources.size
        val isSingleSource = totalSources == 1
        val selectedOptions = callBack.getSearchOptions().associate { it.name to it.selectedValue }
        SourceVerificationHelp.beginSearchSession()
        searchJob = scope.launch(pool) {
            try {
                flow {
                    activeSources.forEach { emit(it) }
                }.onStart {
                    callBack.onSearchStart()
                    callBack.onSearchProgress(0, totalSources)
                }.mapParallelSafe(threadCount, totalSources) { bookSource ->
                    try {
                        withTimeout(timeLimit) {
                            WebBook.getBookListAwait(
                                bookSource, searchKey, searchPage,
                                filter = { name, author ->
                                    !precision || name.contains(searchKey) ||
                                        author.contains(searchKey)
                                },
                                onUrlResolved = if (isSingleSource) { analyzeUrl: AnalyzeUrl ->
                                    val options = parseExploreOptionsFromUrl(analyzeUrl.rawRuleUrl)
                                    if (options.isNotEmpty()) {
                                        callBack.onSearchOptionsResolved(options)
                                    }
                                } else null,
                                selectedOptions = selectedOptions
                            )
                        }
                    } catch (error: Throwable) {
                        currentCoroutineContext().ensureActive()
                        quarantineSourceWhenRequired(bookSource, error)
                        throw error
                    } finally {
                        callBack.onSearchProgress(
                            completedSources.incrementAndGet(),
                            totalSources
                        )
                    }
                }.onEach { items ->
                    for (book in items) {
                        book.releaseHtmlData()
                    }
                    hasMore = hasMore || items.isNotEmpty()
                    mergeItems(items, precision)
                    currentCoroutineContext().ensureActive()
                    callBack.onSearchSuccess(searchBooks)
                }.onCompletion {
                    if (it == null) callBack.onSearchFinish(searchBooks.isEmpty(), hasMore)
                }.catch {
                    AppLog.put("书源搜索出错\n${it.localizedMessage}", it)
                    callBack.onSearchCancel(it)
                }.collect()
            } finally {
                SourceVerificationHelp.endSearchSession()
            }
        }
    }

    private fun quarantineSourceWhenRequired(source: BookSource, error: Throwable) {
        val message = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
        SourceAccountRequiredHelp.quarantine(source, message)
    }

    private suspend fun mergeItems(newDataS: List<SearchBook>, precision: Boolean) {
        if (newDataS.isEmpty()) return

        val equalData = LinkedHashMap<Pair<String, String>, SearchBook>()
        val containsData = LinkedHashMap<Pair<String, String>, SearchBook>()
        val otherData = LinkedHashMap<Pair<String, String>, SearchBook>()

        suspend fun merge(book: SearchBook, mergeOrigin: Boolean) {
            currentCoroutineContext().ensureActive()
            val target = when {
                book.name == searchKey || book.author == searchKey -> equalData
                book.name.contains(searchKey) || book.author.contains(searchKey) -> containsData
                !precision -> otherData
                else -> return
            }
            val key = book.name to book.author
            val previous = target[key]
            if (previous == null) {
                target[key] = book
            } else if (mergeOrigin) {
                previous.addOrigin(book.origin)
            }
        }

        searchBooks.forEach { merge(it, false) }
        newDataS.forEach { merge(it, true) }
        currentCoroutineContext().ensureActive()

        searchBooks = ArrayList<SearchBook>(
            equalData.size + containsData.size + if (precision) 0 else otherData.size
        ).apply {
            addAll(equalData.values.sortedByDescending { it.origins.size })
            addAll(containsData.values.sortedByDescending { it.origins.size })
            if (!precision) addAll(otherData.values)
        }
    }

    fun cancelSearch() {
        close()
        callBack.onSearchCancel()
    }

    fun close() {
        searchJob?.cancel()
        searchPool?.close()
        searchPool = null
        mSearchId = 0L
    }

    interface CallBack {
        fun getSearchScope(): SearchScope
        fun onSearchStart()
        fun onSearchProgress(completed: Int, total: Int)
        fun onSearchSuccess(searchBooks: List<SearchBook>)
        fun onSearchFinish(isEmpty: Boolean, hasMore: Boolean)
        fun onSearchCancel(exception: Throwable? = null)
        fun onSearchOptionsResolved(options: List<ExploreOption>)
        fun getSearchOptions(): List<ExploreOption>
    }

}
