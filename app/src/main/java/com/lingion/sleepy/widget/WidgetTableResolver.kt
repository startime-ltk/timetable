package com.lingion.sleepy.widget

import com.lingion.sleepy.SleepyApp
import com.lingion.sleepy.data.entity.TimeTableEntity
import com.lingion.sleepy.util.AppPrefs

/**
 * Widget 共用：找当前要展示的课表。
 *
 * 策略（与 App 内默认课表一致）：
 * 1. 默认表（isDefault=true）且有课 → 用它
 * 2. 否则任意有课的表（按课程数最多）
 * 3. 否则 null（widget 显示"请先创建课表"）
 *
 * 修复：旧逻辑"优先选非默认表中课程数最多的"，导致只要存在任何非默认表
 *   （如测试/导入副表），widget 就脱离用户在 App 里设的默认表，App 与 widget 不同步。
 *
 * v1.0.39 实例绑定: 添加 widget 时经配置页选表 → prefs 存 widget_table_binding_<id>。
 * resolveForWidget 优先读该绑定; 绑定表已被删除或清空课程时回退 [resolveCurrentTable]。
 */
object WidgetTableResolver {

    suspend fun resolveCurrentTable(): TimeTableEntity? {
        val repo = SleepyApp.get().repository
        val all = repo.getAllTables()
        // 优先：默认表且有课
        val def = all.firstOrNull { it.isDefault }
            ?.takeIf { runCatching { repo.getCourses(it.id).isNotEmpty() }.getOrDefault(false) }
        if (def != null) return def
        // 次选：任意有课的表（课程数最多）
        return all.maxByOrNull { runCatching { repo.getCourses(it.id).size }.getOrDefault(0) }
            ?.takeIf { runCatching { repo.getCourses(it.id).isNotEmpty() }.getOrDefault(false) }
    }

    /**
     * 按 widget 实例解析课表：先读实例绑定（appWidgetId >= 0 且绑定存在且有课），
     * 否则回退 [resolveCurrentTable] 默认逻辑。appWidgetId = -1 直接走默认（旧调用点兼容）。
     */
    suspend fun resolveForWidget(appWidgetId: Int): TimeTableEntity? {
        if (appWidgetId >= 0) {
            val ctx = SleepyApp.get()
            val boundId = AppPrefs.getWidgetTableBinding(ctx, appWidgetId)
            if (boundId != null) {
                val repo = ctx.repository
                val t = repo.getTable(boundId)
                val usable = t != null &&
                    runCatching { repo.getCourses(t!!.id).isNotEmpty() }.getOrDefault(false)
                if (usable) return t
            }
        }
        return resolveCurrentTable()
    }
}
