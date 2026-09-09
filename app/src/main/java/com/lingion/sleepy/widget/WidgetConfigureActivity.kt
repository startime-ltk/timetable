package com.lingion.sleepy.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.lingion.sleepy.SleepyApp
import com.lingion.sleepy.data.entity.TimeTableEntity
import com.lingion.sleepy.util.AppPrefs
import kotlinx.coroutines.runBlocking

/**
 * v1.0.39 Widget 配置页 — 添加/编辑 widget 时由 Launcher 启动 (android:configure)。
 *
 * 职责:
 *  1. 读 EXTRA_APPWIDGET_ID, 列出 App 内全部课表 + 当前已绑定表高亮;
 *  2. 单选一个课表 → 存 AppPrefs.widget_table_binding_<id> 并重置日期偏移为今天;
 *  3. 向该 widget 的 provider 广播 APPWIDGET_UPDATE 强制刷新(长按编辑场景 Host 不会自动刷);
 *  4. setResult(RESULT_OK) 通知 Host 配置完成。
 *
 * 未选直接返回 = 取消添加; 无课表时展示空态提示。
 * 纯原生 View 构建, 不引入 Compose 依赖, 最小侵入。
 */
class WidgetConfigureActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
        if (widgetId < 0) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val tables = runBlocking { SleepyApp.get().repository.getAllTables() }
        val boundId = AppPrefs.getWidgetTableBinding(this, widgetId)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        root.addView(TextView(this).apply {
            text = "选择课表"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF1A1A1A.toInt())
        })
        root.addView(TextView(this).apply {
            text = "为这个桌面小组件选择要展示的课程表。添加后可长按小组件 → 编辑重新选择；未选择则跟随 App 内默认课表。"
            textSize = 13f
            setTextColor(0xFF666666.toInt())
            setPadding(0, dp(6), 0, dp(14))
            setLineSpacing(dp(2).toFloat(), 1.0f)
        })

        if (tables.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "还没有可用的课程表，请先在 App 中创建。"
                textSize = 15f
                setTextColor(0xFF666666.toInt())
                setPadding(0, dp(24), 0, dp(24))
                gravity = Gravity.CENTER
            })
        } else {
            tables.forEach { table ->
                val selected = boundId != null && boundId == table.id
                val row = makeRow(table, selected) {
                    // 选中: 持久化绑定 + 回到今天 + 刷新该 widget + 通知 Host 成功
                    AppPrefs.setWidgetTableBinding(this, widgetId, table.id)
                    AppPrefs.setWidgetDayOffset(this, widgetId, 0)
                    refreshWidget(widgetId)
                    val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    setResult(RESULT_OK, resultValue)
                    finish()
                }
                root.addView(row)
            }
        }

        // 底部提示: 返回 = 取消
        root.addView(TextView(this).apply {
            text = if (tables.isEmpty()) "返回取消添加" else "不选择直接返回，可随时长按小组件重新编辑"
            textSize = 12f
            setTextColor(0xFF999999.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        })

        setContentView(
            ScrollView(this).apply {
                addView(root, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        )
    }

    /** 构建单个课表行(卡片样式, 默认表带标注; 已绑定表高亮描边) */
    private fun makeRow(table: TimeTableEntity, selected: Boolean, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (selected) 0x1A3D5AFE.toInt() else 0xFFF5F5F5.toInt())
                if (selected) {
                    setStroke(dp(1), 0xFF3D5AFE.toInt())
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = table.name.ifBlank { "未命名课表" } + if (selected) "  ✓" else ""
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF1A1A1A.toInt())
        })
        val desc = buildString {
            if (table.isDefault) append("默认课表 · ")
            append("每周 ")
            append(table.maxWeek)
            append(" 周")
        }
        row.addView(TextView(this).apply {
            text = desc
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(0, dp(2), 0, 0)
        })
        return row
    }

    /** 配置完成后显式广播刷新该 widget 实例 (长按编辑时 Host 不自动发 onUpdate, 必须手动) */
    private fun refreshWidget(widgetId: Int) {
        runCatching {
            val awm = AppWidgetManager.getInstance(this)
            val info = awm.getAppWidgetInfo(widgetId) ?: return
            val provider = info.provider ?: return
            val update = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                component = provider
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(widgetId))
            }
            sendBroadcast(update)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
