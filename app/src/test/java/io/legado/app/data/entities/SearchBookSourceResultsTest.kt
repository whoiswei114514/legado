package io.legado.app.data.entities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchBookSourceResultsTest {

    @Test
    fun mergedSearchBookKeepsCompleteResultForEverySource() {
        val merged = searchBook("source-a", "url-a", "第一源")
        merged.addSourceResult(merged)
        merged.addSourceResult(searchBook("source-b", "url-b", "第二源"))

        val results = merged.getSourceResults()

        assertEquals(2, merged.originCount)
        assertEquals(listOf("source-a", "source-b"), results.map { it.origin })
        assertEquals(listOf("url-a", "url-b"), results.map { it.bookUrl })
        assertEquals(listOf("第一源", "第二源"), results.map { it.originName })
    }

    @Test
    fun sourceResultsSurviveSearchCopyAndBookConversion() {
        val merged = searchBook("source-a", "url-a", "第一源")
        merged.addSourceResult(merged)
        merged.addSourceResult(searchBook("source-b", "url-b", "第二源"))

        val copied = merged.copyForSearch()
        val book = copied.toBook()

        assertEquals(2, copied.originCount)
        assertEquals(setOf("source-a", "source-b"), book.sourceBooks.map { it.origin }.toSet())
    }

    @Test
    fun detailSnapshotIsNotChangedByLaterSearchMerges() {
        val merged = searchBook("source-a", "url-a", "第一源")
        merged.addSourceResult(merged)
        val snapshot = merged.copyForSearch()

        merged.addSourceResult(searchBook("source-b", "url-b", "第二源"))

        assertEquals(1, snapshot.originCount)
        assertEquals(listOf("source-a"), snapshot.getSourceResults().map { it.origin })
        assertEquals(2, merged.originCount)
        assertTrue(merged.getSourceResults().any { it.origin == "source-b" })
    }

    private fun searchBook(origin: String, bookUrl: String, originName: String) = SearchBook(
        name = "测试书",
        author = "测试作者",
        origin = origin,
        originName = originName,
        bookUrl = bookUrl,
        tocUrl = "$bookUrl/toc"
    )
}
