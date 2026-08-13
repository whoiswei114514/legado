package io.legado.app.ui.book.changesource

import android.app.Application
import android.os.Bundle
import androidx.annotation.CallSuper
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppConst.timeLimit
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.primaryStr
import io.legado.app.help.book.releaseHtmlData
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.SourceConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.internString
import io.legado.app.utils.mapParallel
import io.legado.app.utils.mapParallelSafe
import io.legado.app.utils.onEachIndexed
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

@Suppress("MemberVisibilityCanBePrivate")
open class ChangeBookSourceViewModel(application: Application) : BaseViewModel(application) {
    private val threadCount = AppConfig.threadCount
    private var searchPool: ExecutorCoroutineDispatcher? = null
    val searchStateData = MutableLiveData<Boolean>()
    var searchFinishCallback: ((isEmpty: Boolean) -> Unit)? = null
    var name: String = ""
    var author: String = ""
    private var fromReadBookActivity = false
    private var oldBook: Book? = null
    private var screenKey: String = ""
    private var bookSources = arrayListOf<BookSource>()
    val totalSourceCount: Int
        get() = bookSources.size
    private var searchBookList = arrayListOf<SearchBook>()
    private val searchBooks = arrayListOf<SearchBook>()
    private val searchBooksLock = Any()
    private val cachedSourceOrigins = ConcurrentHashMap.newKeySet<String>()
    private val tocMap = ConcurrentHashMap<String, List<BookChapter>>()
    private val _changeSourceProgress = MutableStateFlow(0 to "")
    val changeSourceProgress = _changeSourceProgress.asStateFlow()
    private var tocMapChapterCount = 0
    private val contentProcessor by lazy {
        ContentProcessor.get(oldBook!!)
    }
    private var searchCallback: SourceCallback? = null
    private val chapterNumRegex = "^\\[(\\d+)]".toRegex()
    private val comparatorBase by lazy {
        compareByDescending<SearchBook> { getBookScore(it) }.thenByDescending {
            SourceConfig.getSourceScore(
                it.origin
            )
        }
    }
    private val defaultComparator by lazy {
        comparatorBase.thenBy { it.originOrder }
    }
    private val wordCountComparator by lazy {
        comparatorBase.thenByDescending { it.chapterWordCount > 1000 }
            .thenByDescending { getChapterNum(it.chapterWordCountText) }
            .thenByDescending { it.chapterWordCount }.thenBy { it.originOrder }
    }
    private var task: Job? = null
    private val searchSessionId = AtomicLong()
    val bookMap = ConcurrentHashMap<String, Book>()
    val searchDataFlow = callbackFlow<List<SearchBook>> {

        searchCallback = object : SourceCallback {

            override fun searchSuccess(searchBook: SearchBook) {
                searchBook.releaseHtmlData()
                synchronized(searchBooksLock) {
                    searchBooks.removeAll { it.origin == searchBook.origin }
                    searchBooks.add(searchBook)
                }
                trySend(sortedSearchBooksSnapshot())
            }

            override fun upAdapter() {
                trySend(sortedSearchBooksSnapshot())
            }

        }

        val initialBooks = sortedSearchBooksSnapshot()
        trySend(initialBooks)

        if (initialBooks.isEmpty()) {
            startSearch()
        }

        awaitClose {
            searchCallback = null
        }
    }.flowOn(IO)

    override fun onCleared() {
        searchSessionId.incrementAndGet()
        task?.cancel()
        searchPool?.close()
        searchPool = null
        super.onCleared()
    }

    @CallSuper
    open fun initData(arguments: Bundle?, book: Book?, fromReadBookActivity: Boolean) {
        arguments?.let { bundle ->
            bundle.getString("name")?.let {
                name = it
            }
            bundle.getString("author")?.let {
                author = it.replace(AppPattern.authorRegex, "")
            }
        }
        if (name.isBlank()) name = book?.name.orEmpty()
        if (author.isBlank()) author = book?.author
            ?.replace(AppPattern.authorRegex, "")
            .orEmpty()
        this.fromReadBookActivity = fromReadBookActivity
        oldBook = book
        preloadSourceBooks(book)
    }

    private fun preloadSourceBooks(book: Book?) {
        val cachedBooks = book?.sourceBooks.orEmpty()
            .asSequence()
            .filter { it.origin.isNotBlank() }
            .distinctBy { it.origin }
            .map { it.copyForSearch() }
            .toList()
        if (cachedBooks.isEmpty()) return
        synchronized(searchBooksLock) {
            searchBooks.clear()
            searchBooks.addAll(cachedBooks)
        }
        cachedSourceOrigins.clear()
        cachedSourceOrigins.addAll(cachedBooks.map { it.origin })
        cachedBooks.forEach { searchBook ->
            bookMap[searchBook.primaryStr()] = searchBook.toBook().apply {
                sourceBooks = cachedBooks
            }
        }
    }

    private fun matchesCurrentFilter(searchBook: SearchBook): Boolean {
        if (AppConfig.changeSourceCheckAuthor &&
            searchBook.origin !in cachedSourceOrigins &&
            searchBook.author != author
        ) return false
        if (screenKey.isEmpty()) return true
        return searchBook.name.contains(screenKey, ignoreCase = true) ||
            searchBook.author.contains(screenKey, ignoreCase = true)
    }

    private fun searchBooksSnapshot(): List<SearchBook> = synchronized(searchBooksLock) {
        searchBooks.toList()
    }

    private fun sortedSearchBooksSnapshot(): List<SearchBook> {
        val snapshot = searchBooksSnapshot().filter(::matchesCurrentFilter)
        return kotlin.runCatching {
            val comparator = if (AppConfig.changeSourceLoadWordCount) {
                wordCountComparator
            } else {
                defaultComparator
            }
            snapshot.sortedWith(comparator)
        }.onFailure {
            AppLog.put("换源排序出错\n${it.localizedMessage}", it)
        }.getOrDefault(snapshot)
    }

    private fun initSearchPool(): ExecutorCoroutineDispatcher {
        return Executors.newFixedThreadPool(min(threadCount, AppConst.MAX_THREAD))
            .asCoroutineDispatcher()
            .also { searchPool = it }
    }

    fun refresh(): Boolean {
        val isEmpty = sortedSearchBooksSnapshot().isEmpty()
        searchCallback?.upAdapter()
        return isEmpty
    }

    /**
     * 搜索书籍
     */
    fun startSearch() {
        execute {
            val sessionId = beginSearchSession()
            synchronized(searchBooksLock) { searchBooks.clear() }
            cachedSourceOrigins.clear()
            searchCallback?.upAdapter()
            bookSources.clear()
            tocMap.clear()
            bookMap.clear()
            tocMapChapterCount = 0
            _changeSourceProgress.value = 0 to ""
            val searchGroup = AppConfig.searchGroup
            val sources = if (searchGroup.isBlank()) {
                appDb.bookSourceDao.allEnabled
            } else {
                appDb.bookSourceDao.getEnabledByGroup(searchGroup).ifEmpty {
                    AppConfig.searchGroup = ""
                    appDb.bookSourceDao.allEnabled
                }
            }
            if (searchSessionId.get() != sessionId) return@execute
            bookSources.addAll(sources)
            val pool = initSearchPool()
            if (searchSessionId.get() != sessionId) {
                pool.close()
                if (searchPool == pool) searchPool = null
                return@execute
            }
            search(sessionId, sources, pool)
        }
    }

    fun startSearch(origin: String) {
        execute {
            val sessionId = beginSearchSession()
            bookSources.clear()
            tocMap.clear()
            bookMap.clear()
            tocMapChapterCount = 0
            val source = appDb.bookSourceDao.getBookSource(origin) ?: return@execute
            if (searchSessionId.get() != sessionId) return@execute
            bookSources.add(source)
            synchronized(searchBooksLock) {
                searchBooks.removeAll { it.origin == origin }
            }
            cachedSourceOrigins.remove(origin)
            val pool = initSearchPool()
            if (searchSessionId.get() != sessionId) {
                pool.close()
                if (searchPool == pool) searchPool = null
                return@execute
            }
            search(sessionId, listOf(source), pool)
        }
    }

    private fun search(
        sessionId: Long,
        sources: List<BookSource>,
        pool: ExecutorCoroutineDispatcher
    ) {
        task = viewModelScope.launch(pool) {
            flow {
                sources.forEach { emit(it) }
            }.onStart {
                if (searchSessionId.get() == sessionId) {
                    searchStateData.postValue(true)
                }
            }.mapParallel(threadCount) {
                try {
                    withTimeout(timeLimit) {
                        search(it, sessionId)
                    }
                } catch (_: Throwable) {
                    currentCoroutineContext().ensureActive()
                }
                it
            }.onEachIndexed { index, value ->
                if (searchSessionId.get() == sessionId) {
                    _changeSourceProgress.update { _ ->
                        index + 1 to value.bookSourceName
                    }
                }
            }.onCompletion {
                ensureActive()
                if (searchSessionId.get() == sessionId) {
                    searchStateData.postValue(false)
                    searchFinishCallback?.invoke(searchBooksSnapshot().isEmpty())
                }
            }.catch {
                if (searchSessionId.get() == sessionId) {
                    AppLog.put("换源搜索出错\n${it.localizedMessage}", it)
                }
            }.collect()
        }
    }

    private suspend fun search(source: BookSource, sessionId: Long) {
        val checkAuthor = AppConfig.changeSourceCheckAuthor
        val loadInfo = AppConfig.changeSourceLoadInfo
        val loadToc = AppConfig.changeSourceLoadToc
        val loadWordCount = AppConfig.changeSourceLoadWordCount
        val resultBooks = WebBook.getBookListAwait(
            source, name, filter = { fName, fAuthor ->
                fName == name && (!checkAuthor || fAuthor.contains(author))
            })
        resultBooks.forEach { searchBook ->
            when {
                loadInfo || loadToc || loadWordCount -> {
                    loadBookInfo(source, searchBook.toBook(), sessionId)
                }

                else -> {
                    emitSearchResult(sessionId, searchBook)
                }
            }
        }
    }

    private suspend fun loadBookInfo(source: BookSource, book: Book, sessionId: Long) {
        if (book.tocUrl.isEmpty()) {
            WebBook.getBookInfoAwait(source, book)
        }
        if (AppConfig.changeSourceLoadToc || AppConfig.changeSourceLoadWordCount) {
            loadBookToc(source, book, sessionId)
        } else {
            //从详情页里获取最新章节
            val searchBook = book.toSearchBook()
            emitSearchResult(sessionId, searchBook)
        }
    }

    private suspend fun loadBookToc(source: BookSource, book: Book, sessionId: Long) {
        val chapters = WebBook.getChapterListAwait(source, book).getOrThrow()
        if (searchSessionId.get() != sessionId) return
        for (chapter in chapters) {
            chapter.internString()
        }
        if (tocMapChapterCount < 30000) {
            tocMapChapterCount += chapters.size
            tocMap[book.primaryStr()] = chapters
        }
        bookMap[book.primaryStr()] = book
        book.releaseHtmlData()
        if (AppConfig.changeSourceLoadWordCount) {
            loadBookWordCount(source, book, chapters, sessionId)
        } else {
            val searchBook = book.toSearchBook()
            emitSearchResult(sessionId, searchBook)
        }
    }

    private suspend fun loadBookWordCount(
        source: BookSource,
        book: Book,
        chapters: List<BookChapter>,
        sessionId: Long
    ) = coroutineScope {
        val chapterIndex = if (fromReadBookActivity) {
            BookHelp.getDurChapter(oldBook!!, chapters)
        } else {
            chapters.lastIndex
        }
        val bookChapter = chapters[chapterIndex]
        var title = bookChapter.title.trim()
        if (title.length > 20) {
            title = title.take(20) + "…"
        }
        val startTime = System.currentTimeMillis()
        val pair = try {
            val nextChapterUrl = chapters.getOrNull(chapterIndex + 1)?.url
            var content = WebBook.getContentAwait(source, book, bookChapter, nextChapterUrl, false)
            content = contentProcessor.getContent(oldBook!!, bookChapter, content, false).toString()
            val len = content.length
            len to "[${chapterIndex + 1}] ${title}\n字数：${len}"
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            -1 to "[${chapterIndex + 1}] ${title}\n获取字数失败：${t.localizedMessage}"
        }
        val endTime = System.currentTimeMillis()
        val searchBook = book.toSearchBook().apply {
            chapterWordCountText = pair.second
            chapterWordCount = pair.first
            respondTime = (endTime - startTime).toInt()
        }
        emitSearchResult(sessionId, searchBook)
    }

    private fun emitSearchResult(sessionId: Long, searchBook: SearchBook) {
        if (searchSessionId.get() == sessionId) {
            searchCallback?.searchSuccess(searchBook)
        }
    }

    fun onLoadWordCountChecked(isChecked: Boolean) {
        if (isChecked) {
            startRefreshList(true)
        }
    }

    /**
     * 刷新列表
     */
    fun startRefreshList(onlyRefreshNoWordCountBook: Boolean = false) {
        execute {
            val sessionId = beginSearchSession()
            searchBookList.clear()
            synchronized(searchBooksLock) {
                if (onlyRefreshNoWordCountBook) {
                    searchBooks.filterTo(searchBookList) {
                        it.chapterWordCountText == null
                    }
                    searchBooks.removeAll { it.chapterWordCountText == null }
                } else {
                    searchBookList.addAll(searchBooks)
                    searchBooks.clear()
                }
            }
            searchCallback?.upAdapter()
            val refreshBooks = searchBookList.toList()
            val pool = initSearchPool()
            if (searchSessionId.get() != sessionId) {
                pool.close()
                if (searchPool == pool) searchPool = null
                return@execute
            }
            refreshList(sessionId, refreshBooks, pool)
        }
    }

    private fun refreshList(
        sessionId: Long,
        books: List<SearchBook>,
        pool: ExecutorCoroutineDispatcher
    ) {
        task = viewModelScope.launch(pool) {
            flow {
                for (searchBook in books) {
                    emit(searchBook)
                }
            }.onStart {
                if (searchSessionId.get() == sessionId) {
                    searchStateData.postValue(true)
                }
            }.mapParallelSafe(threadCount, books.size) {
                val source = appDb.bookSourceDao.getBookSource(it.origin)!!
                withTimeout(timeLimit) {
                    loadBookInfo(source, it.toBook(), sessionId)
                }
            }.onCompletion {
                if (searchSessionId.get() == sessionId) {
                    searchStateData.postValue(false)
                }
            }.catch {
                if (searchSessionId.get() == sessionId) {
                    AppLog.put("换源刷新列表出错\n${it.localizedMessage}", it)
                }
            }.collect()
        }
    }

    /**
     * 筛选
     */
    fun screen(key: String?) {
        screenKey = key?.trim() ?: ""
        execute {
            searchCallback?.upAdapter()
        }
    }

    fun startOrStopSearch() {
        if (task == null || !task!!.isActive) {
            startSearch()
        } else {
            stopSearch()
        }
    }

    fun stopSearch() {
        searchSessionId.incrementAndGet()
        stopSearchTask()
    }

    private fun stopSearchTask() {
        task?.cancel()
        searchPool?.close()
        searchPool = null
        searchStateData.postValue(false)
    }

    private fun beginSearchSession(): Long {
        val sessionId = searchSessionId.incrementAndGet()
        stopSearchTask()
        return sessionId
    }

    fun getToc(
        book: Book,
        onSuccess: (toc: List<BookChapter>, source: BookSource) -> Unit,
        onError: (e: Throwable) -> Unit
    ): Coroutine<Pair<List<BookChapter>, BookSource>> {
        return execute {
            val toc = tocMap[book.primaryStr()]
            if (toc != null) {
                val source = appDb.bookSourceDao.getBookSource(book.origin)
                return@execute Pair(toc, source!!)
            }
            val result = getToc(book).getOrThrow()
            tocMap[book.primaryStr()] = result.first
            return@execute result
        }.onSuccess {
            onSuccess.invoke(it.first, it.second)
        }.onError {
            onError.invoke(it)
        }
    }

    suspend fun getToc(book: Book): Result<Pair<List<BookChapter>, BookSource>> {
        return kotlin.runCatching {
            val source = appDb.bookSourceDao.getBookSource(book.origin)
                ?: throw NoStackTraceException("书源不存在")
            if (book.tocUrl.isEmpty()) {
                WebBook.getBookInfoAwait(source, book)
            }
            val toc = WebBook.getChapterListAwait(source, book).getOrThrow()
            Pair(toc, source)
        }
    }

    fun disableSource(searchBook: SearchBook) {
        execute {
            appDb.bookSourceDao.getBookSource(searchBook.origin)?.let { source ->
                source.enabled = false
                appDb.bookSourceDao.update(source)
            }
            synchronized(searchBooksLock) { searchBooks.remove(searchBook) }
            searchCallback?.upAdapter()
        }
    }

    fun topSource(searchBook: SearchBook) {
        execute {
            appDb.bookSourceDao.getBookSource(searchBook.origin)?.let { source ->
                val minOrder = appDb.bookSourceDao.minOrder - 1
                source.customOrder = minOrder
                searchBook.originOrder = source.customOrder
                appDb.bookSourceDao.update(source)
            }
            searchCallback?.upAdapter()
        }
    }

    fun bottomSource(searchBook: SearchBook) {
        execute {
            appDb.bookSourceDao.getBookSource(searchBook.origin)?.let { source ->
                val maxOrder = appDb.bookSourceDao.maxOrder + 1
                source.customOrder = maxOrder
                searchBook.originOrder = source.customOrder
                appDb.bookSourceDao.update(source)
            }
            searchCallback?.upAdapter()
        }
    }

    fun del(searchBook: SearchBook) {
        execute {
            SourceHelp.deleteBookSource(searchBook.origin)
        }
        synchronized(searchBooksLock) { searchBooks.remove(searchBook) }
        searchCallback?.upAdapter()
    }

    fun autoChangeSource(
        bookType: Int?, onSuccess: (book: Book, toc: List<BookChapter>, source: BookSource) -> Unit
    ) {
        execute {
            searchBooksSnapshot().forEach {
                if (it.type == bookType) {
                    val book = it.toBook()
                    val result = getToc(book).getOrNull()
                    if (result != null) {
                        return@execute Triple(book, result.first, result.second)
                    }
                }
            }
            throw NoStackTraceException("没有有效源")
        }.onSuccess {
            onSuccess.invoke(it.first, it.second, it.third)
        }.onError {
            context.toastOnUi("自动换源失败\n${it.localizedMessage}")
        }
    }

    fun setBookScore(searchBook: SearchBook, score: Int) {
        execute {
            SourceConfig.setBookScore(searchBook.origin, searchBook.name, searchBook.author, score)
            searchCallback?.upAdapter()
        }
    }

    fun getBookScore(searchBook: SearchBook): Int {
        return SourceConfig.getBookScore(searchBook.origin, searchBook.name, searchBook.author)
    }

    private fun getChapterNum(wordCountText: String?): Int {
        wordCountText ?: return -1
        return chapterNumRegex.find(wordCountText)?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    interface SourceCallback {

        fun searchSuccess(searchBook: SearchBook)

        fun upAdapter()

    }

}
