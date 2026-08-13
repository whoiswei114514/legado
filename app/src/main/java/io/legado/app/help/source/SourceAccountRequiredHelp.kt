package io.legado.app.help.source

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource

object SourceAccountRequiredHelp {

    private const val quarantineGroup = "需登录或激活"

    private val requiredMarkers = listOf(
        "需要登录",
        "请先登录",
        "尚未登录",
        "未登录",
        "登录后",
        "登录ui",
        "登录 ui",
        "需要激活",
        "请先激活",
        "账号未激活",
        "尚未激活",
        "未激活",
        "激活后"
    )

    fun quarantine(source: BaseSource?, message: String): Boolean {
        val bookSource = source as? BookSource ?: return false
        if (!requiresAccount(message)) return false

        bookSource.addGroup(quarantineGroup)
        bookSource.enabled = false
        appDb.bookSourceDao.quarantine(
            bookSource.bookSourceUrl,
            bookSource.bookSourceGroup.orEmpty()
        )
        AppLog.put("已自动禁用并分组书源 ${bookSource.bookSourceName}: $message")
        return true
    }

    internal fun requiresAccount(message: String): Boolean {
        return requiredMarkers.any { message.contains(it, ignoreCase = true) }
    }
}
