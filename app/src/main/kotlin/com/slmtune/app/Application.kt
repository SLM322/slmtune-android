package com.slmtune.app

import android.app.Application as AndroidApplication
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import com.slmtune.app.core.Store
import com.slmtune.app.core.WifiRules
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.io.File

class Application : AndroidApplication() {
    companion object {
        lateinit var application: Application
        val connectivity: ConnectivityManager by lazy { application.getSystemService(ConnectivityManager::class.java) }
        val packageManager: PackageManager by lazy { application.packageManager }
        val wifiManager: WifiManager by lazy { application.applicationContext.getSystemService(WifiManager::class.java) }
    }

    override fun onCreate() {
        super.onCreate()
        application = this
        Store.init(this)
        val working = getExternalFilesDir(null) ?: File(filesDir, "work").also { it.mkdirs() }
        com.slmtune.app.core.ServerScripts.source = { name -> assets.open("server/$name.sh").use { String(it.readBytes()) } }
        Libbox.setup(SetupOptions().also {
            it.basePath = filesDir.path
            it.workingPath = working.path
            it.tempPath = cacheDir.path
            it.fixAndroidStack = true
            it.logMaxLines = 2000
            it.debug = BuildConfig.DEBUG
            it.crashReportSource = "Application"
            it.appVersion = BuildConfig.VERSION_CODE.toString()
            it.appMarketingVersion = BuildConfig.VERSION_NAME
        })
        WifiRules.start(this)
    }
}
