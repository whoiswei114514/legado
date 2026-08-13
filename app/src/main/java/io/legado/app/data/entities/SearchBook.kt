package io.legado.app.data.entities

import android.content.Context
import android.os.Parcelable
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

@Parcelize
data class SearchBook(
    override var bookUrl: String = "",
    /** 书源 */
    override var origin: String = "",
    override var originName: String = "",
    /** BookType */
    override var type: Int = BookType.text,
    override var name: String = "",
    override var author: String = "",
    override var kind: String? = null,
    override var coverUrl: String? = null,
    override var intro: String? = null,
    override var wordCount: String? = null,
    override var latestChapterTitle: String? = null,
    /** 目录页Url (toc=table of Contents) */
    override var tocUrl: String = "",
    var time: Long = System.currentTimeMillis(),
    override var variable: String? = null,
    override var originOrder: Int = 0,
    var chapterWordCountText: String? = null,
    var chapterWordCount: Int = -1,
    var respondTime: Int = -1
) : Parcelable, BaseBook, Comparable<SearchBook> {

    @IgnoredOnParcel
    override var infoHtml: String? = null

    @IgnoredOnParcel
    override var tocHtml: String? = null

    override fun equals(other: Any?) = other is SearchBook && other.bookUrl == bookUrl

    override fun hashCode() = bookUrl.hashCode()

    override fun compareTo(other: SearchBook): Int {
        return other.originOrder - this.originOrder
    }

    @delegate:Transient
    @IgnoredOnParcel
    override val variableMap: HashMap<String, String> by lazy {
        GSON.fromJsonObject<HashMap<String, String>>(variable).getOrNull() ?: HashMap()
    }

    @delegate:Transient
    @IgnoredOnParcel
    val origins: LinkedHashSet<String> by lazy { linkedSetOf(origin) }

    /**
     * 同名同作者的搜索结果合并后，保留每个书源返回的原始书籍结果。
     *
     * 该字段只用于当前进程内的搜索结果传递，不参与 Parcelable/JSON 持久化；
     * 这样进入详情页时可以直接打开已经搜索到的书源，而不必再次发起搜索。
     */
    @delegate:Transient
    @IgnoredOnParcel
    private val sourceResults: LinkedHashMap<String, SearchBook> by lazy {
        linkedMapOf()
    }

    fun addOrigin(origin: String) {
        if (origin.isNotBlank()) synchronized(origins) {
            origins.add(origin)
        }
    }

    val originCount: Int
        get() = synchronized(origins) { origins.size }

    fun addSourceResult(book: SearchBook) {
        synchronized(sourceResults) {
            sourceResults[book.origin] = book.copy()
        }
        addOrigin(book.origin)
    }

    fun addSourceResults(books: Iterable<SearchBook>) {
        books.forEach { addSourceResult(it) }
    }

    fun getSourceResults(): List<SearchBook> {
        synchronized(sourceResults) {
            if (sourceResults.isEmpty()) return listOf(copy())
            return sourceResults.values.map { it.copy() }
        }
    }

    /** 创建一个不会再和旧搜索快照共享来源状态的副本。 */
    fun copyForSearch(): SearchBook {
        return copy().also { it.addSourceResults(getSourceResults()) }
    }

    fun getDisplayLastChapterTitle(): String {
        latestChapterTitle?.let {
            if (it.isNotEmpty()) {
                return it
            }
        }
        return "无最新章节"
    }

    fun trimIntro(context: Context): String {
        val trimIntro = intro?.trim()
        return if (trimIntro.isNullOrEmpty()) {
            context.getString(R.string.intro_show_null)
        } else {
            context.getString(R.string.intro_show, trimIntro)
        }
    }

    fun sameBookTypeLocal(bookType: Int): Boolean {
        return type and BookType.allBookTypeLocal == bookType and BookType.allBookTypeLocal
    }

    fun toBook() = Book(
        name = name,
        author = author,
        kind = kind,
        bookUrl = bookUrl,
        origin = origin,
        originName = originName,
        type = type,
        wordCount = wordCount,
        latestChapterTitle = latestChapterTitle,
        coverUrl = coverUrl,
        intro = intro,
        tocUrl = tocUrl,
        originOrder = originOrder,
        variable = variable
    ).apply {
        this.infoHtml = this@SearchBook.infoHtml
        this.tocHtml = this@SearchBook.tocHtml
        this.sourceBooks = this@SearchBook.getSourceResults()
    }
}
