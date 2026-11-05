package dev.hatv.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.media.tv.TvContract
import android.media.tv.TvInputManager

data class InstalledApp(val id: String, val name: String, val icon: Drawable?, val intent: Intent)

class AppCatalog(private val context: Context) {
    fun discover(): List<InstalledApp> {
        val pm = context.packageManager
        val tv =
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
                0,
            )
        val standard =
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                0,
            )
        val apps =
            (tv + standard)
                .distinctBy { it.activityInfo.packageName }
                .filter { it.activityInfo.packageName != context.packageName }
                .map {
                    val component = ComponentName(it.activityInfo.packageName, it.activityInfo.name)
                    InstalledApp(
                        component.flattenToString(),
                        it.loadLabel(pm).toString(),
                        it.loadIcon(pm),
                        Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_LAUNCHER)
                            .setComponent(component)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
        val inputs =
            runCatching {
                    (context.getSystemService(Context.TV_INPUT_SERVICE) as TvInputManager)
                        .tvInputList
                        .filter {
                            it.type == android.media.tv.TvInputInfo.TYPE_HDMI &&
                                !it.isHidden(context)
                        }
                        .map {
                            InstalledApp(
                                "hdmi:${it.id}",
                                it.loadLabel(context).toString(),
                                null,
                                Intent(
                                        Intent.ACTION_VIEW,
                                        TvContract.buildChannelUriForPassthroughInput(it.id),
                                    )
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                }
                .getOrDefault(emptyList())
        return apps.sortedBy { it.name.lowercase() } + inputs
    }

    fun launch(app: InstalledApp): Boolean =
        runCatching {
                context.startActivity(app.intent)
                true
            }
            .getOrDefault(false)
}
