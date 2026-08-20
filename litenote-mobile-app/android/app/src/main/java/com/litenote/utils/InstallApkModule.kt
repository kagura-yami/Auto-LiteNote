package com.litenote.utils

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.litenote.BuildConfig
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import java.io.File

class InstallApkModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String {
        return "InstallApk"
    }

    /**
     * 返回当前已安装 APK 的真实版本号。
     * 不从 JS bundle 读取 package.json，避免热更新包携带旧版本号时造成循环更新。
     */
    @ReactMethod
    fun getAppVersion(promise: Promise) {
        promise.resolve(BuildConfig.VERSION_NAME)
    }

    @ReactMethod
    fun install(filePath: String) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                android.util.Log.e("InstallApk", "APK 文件不存在: $filePath")
                return
            }

            val context = reactApplicationContext
            val intent = Intent(Intent.ACTION_VIEW)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            // Android 7.0+ 需要使用 FileProvider
            val apkUri: Uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
            } else {
                Uri.fromFile(file)
            }

            intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
            context.startActivity(intent)

            android.util.Log.d("InstallApk", "启动 APK 安装: $filePath")
        } catch (e: Exception) {
            android.util.Log.e("InstallApk", "安装 APK 失败", e)
        }
    }
}
