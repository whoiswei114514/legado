package io.legado.app.ui.association

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.databinding.DialogVerificationCodeViewBinding
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.help.source.CaptchaAiRecognizer
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.customView
import io.legado.app.lib.dialogs.noButton
import io.legado.app.lib.dialogs.onDismiss
import io.legado.app.lib.dialogs.yesButton
import io.legado.app.model.ImageProvider
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.utils.applyTint
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import splitties.init.appCtx

/**
 * 图片验证码对话框
 * 重构为使用 alert DSL 实现，菜单保持在右上角
 */
object VerificationCodeDialog {

    fun display(
        imageUrl: String,
        sourceOrigin: String? = null,
        sourceName: String? = null,
        sourceType: Int
    ) {
        val activity = io.legado.app.help.LifecycleHelp.currentActivity as? AppCompatActivity
        if (activity == null) {
            AppLog.put("验证码对话框未显示: 当前没有可用 Activity")
            appCtx.toastOnUi("无法在后台显示验证码对话框")
            sourceOrigin?.let { SourceVerificationHelp.checkResult(it) }
            return
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            AppLog.put("验证码对话框已调度到主线程: source=${sourceName.orEmpty()}")
            activity.runOnUiThread {
                displayOnMain(activity, imageUrl, sourceOrigin, sourceName, sourceType)
            }
            return
        }
        displayOnMain(activity, imageUrl, sourceOrigin, sourceName, sourceType)
    }

    private fun displayOnMain(
        activity: AppCompatActivity,
        imageUrl: String,
        sourceOrigin: String?,
        sourceName: String?,
        sourceType: Int
    ) {
        if (activity.isFinishing || activity.isDestroyed) {
            AppLog.put("验证码对话框未显示: Activity 已结束, source=${sourceName.orEmpty()}")
            sourceOrigin?.let { SourceVerificationHelp.checkResult(it) }
            return
        }

        val binding = DialogVerificationCodeViewBinding.inflate(activity.layoutInflater)
        var recognitionJob: Job? = null

        // 配置 Toolbar 以保持右上角菜单
        binding.toolBar.setTitle(R.string.verification_code)
        binding.toolBar.subtitle = sourceName
        binding.toolBar.inflateMenu(R.menu.verification_code)
        binding.toolBar.menu.applyTint(activity)

        val dialog = activity.alert {
            customView { binding.root }

            onDismiss {
                recognitionJob?.cancel()
                ImageProvider.remove(imageUrl)
                AppLog.put("验证码图片缓存已释放: source=${sourceName.orEmpty()}")
                sourceOrigin?.let { SourceVerificationHelp.checkResult(it) }
            }
        }

        // 菜单点击事件
        binding.toolBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_ok -> {
                    val verificationCode = binding.verificationCode.text.toString()
                    sourceOrigin?.let { SourceVerificationHelp.setResult(it, verificationCode) }
                    dialog.dismiss()
                }

                R.id.menu_disable_source -> {
                    sourceOrigin?.let { SourceHelp.enableSource(it, sourceType, false) }
                    dialog.dismiss()
                }

                R.id.menu_delete_source -> {
                    activity.alert(R.string.draw) {
                        setMessage(activity.getString(R.string.sure_del) + "\n" + sourceName)
                        noButton()
                        yesButton {
                            sourceOrigin?.let { SourceHelp.deleteSource(it, sourceType) }
                            dialog.dismiss()
                        }
                    }
                }
            }
            true
        }

        loadImage(
            activity = activity,
            binding = binding,
            url = imageUrl,
            sourceUrl = sourceOrigin,
            sourceName = sourceName,
            isDialogShowing = { dialog.isShowing },
            onRecognitionStarted = { recognitionJob = it }
        )

        binding.verificationCodeImageView.setOnClickListener {
            activity.showDialogFragment(PhotoDialog(imageUrl, sourceOrigin))
        }
    }

    @SuppressLint("CheckResult")
    private fun loadImage(
        activity: AppCompatActivity,
        binding: DialogVerificationCodeViewBinding,
        url: String,
        sourceUrl: String?,
        sourceName: String?,
        isDialogShowing: () -> Boolean,
        onRecognitionStarted: (Job) -> Unit
    ) {
        val displayName = sourceName.orEmpty()
        AppLog.put("验证码图片开始加载: source=$displayName")
        ImageProvider.remove(url)
        ImageLoader.loadBitmap(activity, url).apply {
            sourceUrl?.let {
                apply(RequestOptions().set(OkHttpModelLoader.sourceOriginOption, it))
            }
        }.error(R.drawable.image_loading_error)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .skipMemoryCache(true)
            .listener(object : RequestListener<Bitmap> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Bitmap?>,
                    isFirstResource: Boolean
                ): Boolean {
                    AppLog.put(
                        "验证码图片加载失败: source=$displayName, ${e?.localizedMessage.orEmpty()}",
                        e
                    )
                    return false
                }

                override fun onResourceReady(
                    resource: Bitmap,
                    model: Any,
                    target: Target<Bitmap?>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    val bitmap = resource.copy(resource.config ?: Bitmap.Config.ARGB_8888, true)
                    ImageProvider.put(url, bitmap)
                    AppLog.put(
                        "验证码图片加载成功: source=$displayName, " +
                            "width=${bitmap.width}, height=${bitmap.height}, dataSource=$dataSource"
                    )
                    if (CaptchaAiRecognizer.isConfigured()) {
                        val job = activity.lifecycleScope.launch {
                            activity.toastOnUi(R.string.captcha_ai_recognizing)
                            try {
                                val result = CaptchaAiRecognizer.recognize(
                                    activity.applicationContext,
                                    bitmap
                                )
                                if (isDialogShowing()) {
                                    binding.verificationCode.setText(result)
                                    AppLog.put("验证码 AI 已自动填入: source=$displayName")
                                }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                activity.toastOnUi(
                                    activity.getString(
                                        R.string.captcha_ai_failed,
                                        error.localizedMessage
                                    )
                                )
                            }
                        }
                        onRecognitionStarted(job)
                    } else {
                        AppLog.put("验证码 AI 已跳过: 功能未启用或 API Key 为空, source=$displayName")
                    }
                    return false
                }
            })
            .into(binding.verificationCodeImageView)
    }

}
