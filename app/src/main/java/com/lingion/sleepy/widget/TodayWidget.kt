package com.lingion.sleepy.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import com.lingion.sleepy.R
import com.lingion.sleepy.SleepyApp
import com.lingion.sleepy.util.DateUtils
import com.lingion.sleepy.util.TimeTableUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

/**
 * 桌面 Today 小组件 — 同步 RemoteViews + Canvas (v1.0.29 起, 从 Glance 移植)。
 *
 * 之前是 GlanceAppWidgetReceiver → provideGlance 异步 SessionWorker → OPPO OplusHansManager
 * 冻结进程 → RemoteViews 从不生成 → 卡在 widget_loading 紫色布局 → 不跟随主题。
 * 现在克隆 WeekGridWidgetProvider 模式: goAsync → 加载 → 画 bitmap → awm.updateAppWidget,
 * 全程在冻结窗口前完成 → 秒刷 + 主题正确。
 *
 * v1.0.36: 内容装得下走静态 renderAndPush(与主分支一致); 装不下走 pushScrollable
 * (壳图+条带 ListView, 条带与静态渲染同源 → 顶部像素一致, 可滚动)。
 *
 * Glance 版 TodayWidget 类已删除(决策 D5-11); loadDataSync 自 Glance companion 迁入本类。
 */
open class TodayWidgetReceiver : AppWidgetProvider() {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 小组件排版档位 — 基类默认 REGULAR(现有变体); 「今日课程 · 小」子类覆写为 SMALL */
    open val variantHint: WidgetVariant = WidgetVariant.REGULAR

    private fun push(context: Context, awm: AppWidgetManager, id: Int) {
        pushTodayData(context, awm, id, variantHint, loadDataSync(context, id),
            showNav = true, navCls = this::class.java)
    }

    override fun onUpdate(context: Context, awm: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        ioScope.launch {
            try {
                for (id in ids) {
                    try { push(context, awm, id) }
                    catch (e: Throwable) { Log.e(TAG, "render failed $id", e) }
                }
            } finally { pending.finish() }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, awm: AppWidgetManager, id: Int, newOptions: Bundle
    ) {
        val pending = goAsync()
        ioScope.launch {
            try { push(context, awm, id) }
            catch (e: Throwable) { Log.e(TAG, "optionsChanged render failed $id", e) }
            finally { pending.finish() }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TODAY_NAV) {
            val pending = goAsync()
            ioScope.launch {
                try {
                    val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                    val step = intent.getIntExtra(EXTRA_NAV_STEP, 0)
                    if (id >= 0) {
                        val cur = com.lingion.sleepy.util.AppPrefs.getWidgetDayOffset(context, id)
                        com.lingion.sleepy.util.AppPrefs.setWidgetDayOffset(context, id, if (step == 0) 0 else cur + step)
                        push(context, AppWidgetManager.getInstance(context), id)
                    }
                } catch (e: Throwable) { Log.e(TAG, "nav failed", e) }
                finally { pending.finish() }
            }
            return
        }
        super.onReceive(context, intent)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { com.lingion.sleepy.util.AppPrefs.clearWidgetPrefs(context, it) }
    }

    companion object {
        private const val TAG = "TodayWidgetRV"

        /** 日期导航动作 (‹ 前一天 / › 后一天 / 点中间回今天) — RemoteViews 按钮的 PendingIntent broadcast */
        private const val ACTION_TODAY_NAV = "com.lingion.sleepy.widget.action.TODAY_NAV"
        private const val EXTRA_NAV_STEP = "nav_step"

        /**
         * 今日课程推送管线(静态/可滚动闸门) — 网格小最小档与今日课程·小共用,
         * 保证"变成今日课程那个小组件的样子"像素级同源(同一渲染器+同一滚动条带工厂)。
         * 注意 Today 小变体在这里等效直通(REGULAR 也走这条闸), 与改动前行为一致。
         */
        fun pushTodayData(
            context: Context, awm: AppWidgetManager, id: Int,
            variant: WidgetVariant, data: WidgetData,
            showNav: Boolean = false, navCls: Class<*>? = null
        ) {
            val opts = awm.getAppWidgetOptions(id)
            val (wDp, hDp) = RemoteViewsWidgetHelper.computeSizeDp(opts)
            val contentH = WidgetBitmapRenderers.todayContentHeightDp(data)
            if (contentH <= hDp) {
                if (showNav) {
                    // 静态内容 + 日期导航: 布局叠加透明按钮 (Canvas 画完的 bitmap 无法响应点击,
                    // 必须 RemoteViews 层叠 ImageView 按钮热区; 中间整条=回今天, 两端‹›=前一天/后一天)
                    val bmp = WidgetBitmapRenderers.renderToday(
                        context, data, wDp.toFloat(), hDp.toFloat(), variant, showNav = true)
                    val views = RemoteViews(context.packageName, R.layout.widget_today_nav)
                    views.setImageViewBitmap(R.id.widget_bitmap, bmp)
                    // 保留原交互: bitmap 空白区点击打开 App (与 RemoteViewsWidgetHelper.renderAndPush 一致)
                    val pi = android.app.PendingIntent.getActivity(
                        context, id,
                        Intent(context, com.lingion.sleepy.MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        },
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                    views.setOnClickPendingIntent(R.id.widget_bitmap, pi)
                    attachTodayNavActions(context, views, id, navCls)
                    awm.updateAppWidget(id, views)
                    bmp.recycle()
                    return
                }
                RemoteViewsWidgetHelper.renderAndPush(
                    context, awm, id, TAG,
                    loadData = { data },
                    renderBitmap = { d, w, h ->
                        WidgetBitmapRenderers.renderToday(context, d, w, h, variant)
                    }
                )
            } else {
                val shell = WidgetBitmapRenderers.renderToday(
                    context, data, wDp.toFloat(), hDp.toFloat(), variant, showNav)
                RemoteViewsWidgetHelper.pushScrollable(
                    context, awm, id, TAG,
                    layoutRes = if (showNav) R.layout.widget_scroll_today_nav else R.layout.widget_scroll_today,
                    shellBitmap = shell,
                    scopeExtra = if (showNav) ScrollStripService.StripFactory.SCOPE_TODAY_NAV
                        else ScrollStripService.StripFactory.SCOPE_TODAY,
                    afterBuild = if (showNav) { v -> attachTodayNavActions(context, v, id, navCls) } else null
                )
            }
        }

        /** 给 Today 视图叠加 ‹ / › / 回今天 三个透明点击热区 (id 同 widget_today_nav/widget_scroll_today_nav) */
        private fun attachTodayNavActions(context: Context, views: RemoteViews, id: Int, navCls: Class<*>?) {
            val cls = navCls ?: TodayWidgetReceiver::class.java
            views.setOnClickPendingIntent(R.id.today_btn_prev, navPi(context, cls, id, -1))
            views.setOnClickPendingIntent(R.id.today_btn_next, navPi(context, cls, id, 1))
            views.setOnClickPendingIntent(R.id.today_btn_today, navPi(context, cls, id, 0))
        }

        private fun navPi(context: Context, cls: Class<*>, id: Int, step: Int): PendingIntent {
            val pi = PendingIntent.getBroadcast(
                context,
                id * 10 + (step + 5),
                Intent(context, cls).apply {
                    action = ACTION_TODAY_NAV
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    putExtra(EXTRA_NAV_STEP, step)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return pi
        }

        /**
         * 同步版数据加载 (runBlocking DB 读) — 供 RemoteViews Receiver 使用。
         * v1.0.39: appWidgetId ≥ 0 时先解析实例绑定表 + 读取日期偏移(箭头导航);
         * 偏移量累计到 today 得展示日 baseDate; 周次/学期状态/课程均按 baseDate 计算。
         */
        fun loadDataSync(context: Context, appWidgetId: Int = -1): WidgetData {
            val today = LocalDate.now()
            val dayOffset = com.lingion.sleepy.util.AppPrefs.getWidgetDayOffset(context, appWidgetId)
            val baseDate = today.plusDays(dayOffset.toLong())
            val dayOfWeek = baseDate.dayOfWeek.value
            val isSystemDark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            val isDark = com.lingion.sleepy.util.AppPrefs.isDarkMode(context, isSystemDark)
            val themeKey = com.lingion.sleepy.util.AppPrefs.getThemeKey(context)
            val themeMode = com.lingion.sleepy.util.AppPrefs.getThemeMode(context)
            Log.d("TodayWidget", "DIAG: isDark=$isDark isSystemDark=$isSystemDark themeMode=$themeMode themeKey=$themeKey")
            return try {
                runBlocking {
                    val app = SleepyApp.get()
                    val repo = app.repository
                    val table = WidgetTableResolver.resolveForWidget(appWidgetId)
                    if (table == null) {
                        WidgetData(date = baseDate, courses = emptyList(), timeJson = TimeTableUtils.DEFAULT_TIME_JSON, hasTable = false, isDark = isDark, themeKey = themeKey)
                    } else {
                        val week = DateUtils.currentWeek(table.startDate, baseDate)
                        val status = DateUtils.semesterStatus(table.startDate, table.maxWeek, baseDate)
                        val all = repo.getCoursesByDayOnce(table.id, dayOfWeek)
                        // 学期外(前/后)不展示课程 — App 今日页同语义, 避免学期前显示"第1周"的课
                        val visible = if (status != DateUtils.SemesterStatus.IN_RANGE) emptyList() else
                            all.filter { it.inWeek(week) }.sortedBy { it.startNode }
                        WidgetData(date = baseDate, courses = visible, timeJson = table.timeJson, hasTable = true, isDark = isDark, themeKey = themeKey, semesterStatus = status, tableName = table.name)
                    }
                }
            } catch (_: Throwable) {
                WidgetData(date = baseDate, courses = emptyList(), timeJson = TimeTableUtils.DEFAULT_TIME_JSON, hasTable = false, isDark = isDark, themeKey = themeKey)
            }
        }
    }
}
