package io.legado.app.help.source

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.CacheManager
import io.legado.app.help.IntentData
import io.legado.app.ui.association.VerificationCodeDialog
import io.legado.app.ui.browser.WebViewActivity
import io.legado.app.utils.isMainThread
import io.legado.app.utils.startActivity
import splitties.init.appCtx
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.time.Duration.Companion.minutes

/**
 * 源验证
 */
object SourceVerificationHelp {

    private val waitTime = 1.minutes.inWholeNanoseconds
    private val verificationStateLock = Any()
    private val activeVerification = AtomicReference<String?>(null)
    private val activeSearchSessions = AtomicInteger()
    private val verificationShownDuringSearch = AtomicBoolean()

    private fun getVerificationResultKey(source: BaseSource) =
        getVerificationResultKey(source.getKey())

    private fun getVerificationResultKey(sourceKey: String) = "${sourceKey}_verificationResult"

    /**
     * 获取书源验证结果
     * 图片验证码 防爬 滑动验证码 点击字符 等等
     */
    fun getVerificationResult(
        source: BaseSource?,
        url: String,
        title: String,
        useBrowser: Boolean,
        refetchAfterSuccess: Boolean = true
    ): String {
        source
            ?: throw NoStackTraceException("getVerificationResult parameter source cannot be null")
        require(url.length < 64 * 1024) { "getVerificationResult parameter url too long" }
        check(!isMainThread) { "getVerificationResult must be called on a background thread" }

        val sourceKey = source.getKey()
        val ownsVerification = acquireVerification(sourceKey)
        if (!ownsVerification) {
            AppLog.putDebug("已有书源处于人机验证，已跳过: ${source.getTag()}")
            throw NoStackTraceException("已有书源处于人机验证，已跳过当前书源")
        }

        try {
            clearResult(sourceKey)
            IntentData.put(getVerificationResultKey(source), Thread.currentThread())

            if (!useBrowser) {
                VerificationCodeDialog.display(
                    url,
                    sourceKey,
                    source.getTag(),
                    source.getSourceType()
                )
            } else {
                startBrowser(source, url, title, true, refetchAfterSuccess)
            }

            var waitUserInput = false
            while (getResult(sourceKey) == null) {
                if (!waitUserInput) {
                    AppLog.putDebug("等待返回验证结果...")
                    waitUserInput = true
                }
                LockSupport.parkNanos(this, waitTime)
            }

            val result = getResult(sourceKey)!!
            clearResult(sourceKey)
            result.ifBlank {
                throw NoStackTraceException("验证结果为空")
            }

            return result
        } finally {
            synchronized(verificationStateLock) {
                activeVerification.compareAndSet(sourceKey, null)
            }
        }
    }

    private fun acquireVerification(sourceKey: String): Boolean =
        synchronized(verificationStateLock) {
            if (activeVerification.get() != null) {
                return@synchronized false
            }
            if (activeSearchSessions.get() > 0 && verificationShownDuringSearch.get()) {
                return@synchronized false
            }
            activeVerification.set(sourceKey)
            if (activeSearchSessions.get() > 0) {
                verificationShownDuringSearch.set(true)
            }
            true
        }

    fun beginSearchSession() = synchronized(verificationStateLock) {
        if (activeSearchSessions.incrementAndGet() == 1) {
            verificationShownDuringSearch.set(false)
        }
    }

    fun endSearchSession() = synchronized(verificationStateLock) {
        val remaining = activeSearchSessions.decrementAndGet()
        if (remaining <= 0) {
            activeSearchSessions.set(0)
            verificationShownDuringSearch.set(false)
        }
    }

    /**
     * 启动内置浏览器
     * @param saveResult 保存网页源代码到数据库
     */
    fun startBrowser(
        source: BaseSource?,
        url: String,
        title: String,
        saveResult: Boolean? = false,
        refetchAfterSuccess: Boolean? = true
    ) {
        source ?: throw NoStackTraceException("startBrowser parameter source cannot be null")
        require(url.length < 64 * 1024) { "startBrowser parameter url too long" }
        appCtx.startActivity<WebViewActivity> {
            putExtra("title", title)
            putExtra("url", url)
            putExtra("sourceOrigin", source.getKey())
            putExtra("sourceName", source.getTag())
            putExtra("sourceType", source.getSourceType())
            putExtra("sourceVerificationEnable", saveResult)
            putExtra("refetchAfterSuccess", refetchAfterSuccess)
        }
    }


    fun checkResult(sourceKey: String) {
        getResult(sourceKey) ?: setResult(sourceKey, "")
        val thread = IntentData.get<Thread>(getVerificationResultKey(sourceKey))
        LockSupport.unpark(thread)
    }

    fun setResult(sourceKey: String, result: String?) {
        CacheManager.putMemory(getVerificationResultKey(sourceKey), result ?: "")
    }

    fun getResult(sourceKey: String): String? {
        return CacheManager.getFromMemory(getVerificationResultKey(sourceKey)) as? String
    }

    fun clearResult(sourceKey: String) {
        CacheManager.delete(getVerificationResultKey(sourceKey))
    }
}
