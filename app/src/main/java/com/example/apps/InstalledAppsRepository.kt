package com.example.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledAppInfo(
  val packageName: String,
  val appName: String,
  val isSystemApp: Boolean,
  val uid: Int,
  val icon: Drawable?,
)

class InstalledAppsRepository(private val context: Context) {

  suspend fun loadInstalledApps(): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val selfPackage = context.packageName
    val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
    } else {
      @Suppress("DEPRECATION")
      pm.getInstalledApplications(PackageManager.GET_META_DATA)
    }

    apps
      .asSequence()
      .filter { it.packageName != selfPackage }
      .map { info ->
        val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
          (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
        val label = runCatching { pm.getApplicationLabel(info).toString() }
          .getOrDefault(info.packageName)
          .ifBlank { info.packageName }
        val icon = runCatching { pm.getApplicationIcon(info) }.getOrNull()
        InstalledAppInfo(
          packageName = info.packageName,
          appName = label,
          isSystemApp = isSystem,
          uid = info.uid,
          icon = icon,
        )
      }
      .sortedWith(compareBy<InstalledAppInfo> { it.isSystemApp }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.appName })
      .toList()
  }
}
