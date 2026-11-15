package com.edencrew.shiptime

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlin.concurrent.thread

open class ShipTimeWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        WidgetUpdater.updateAll(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PLUS, ACTION_MINUS, ACTION_PLUS30, ACTION_MINUS30 -> {
                val delta = when (intent.action) {
                    ACTION_PLUS -> 60
                    ACTION_MINUS -> -60
                    ACTION_PLUS30 -> 30
                    else -> -30
                }
                // 브로드캐스트는 goAsync 로 약 10초까지 연장 가능
                val pending = goAsync()
                thread(name = "ship-time-adjust") {
                    try {
                        ShipTimeController.adjust(ctx, delta, budgetMs = 8000)
                    } catch (_: Throwable) {
                    } finally {
                        pending.finish()
                    }
                }
            }
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED, ACTION_DAY_TICK -> WidgetUpdater.updateAll(ctx)
            else -> super.onReceive(ctx, intent)
        }
    }

    companion object {
        const val ACTION_PLUS = "com.edencrew.shiptime.PLUS"
        const val ACTION_MINUS = "com.edencrew.shiptime.MINUS"
        const val ACTION_PLUS30 = "com.edencrew.shiptime.PLUS30"
        const val ACTION_MINUS30 = "com.edencrew.shiptime.MINUS30"

        /** 선박 시간 자정에 디데이 표시를 갱신 */
        const val ACTION_DAY_TICK = "com.edencrew.shiptime.DAY_TICK"
    }
}

object WidgetUpdater {
    fun updateAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        updateProvider(ctx, mgr, ShipTimeWidget::class.java, R.layout.widget_ship_time)
        updateProvider(ctx, mgr, ShipTimeWidgetLarge::class.java, R.layout.widget_ship_time_large)
        scheduleDayTick(ctx, mgr)
    }

    /**
     * 디데이가 있고 위젯이 하나라도 있으면 다음 선박 시간 자정에 갱신 알람을 건다.
     * 정확한 알람 권한 없이 쓰는 비정확 알람이라 Doze 중에는 몇 분 늦을 수 있다.
     */
    private fun scheduleDayTick(ctx: Context, mgr: AppWidgetManager) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = actionIntent(ctx, ShipTimeWidget.ACTION_DAY_TICK, REQ_DAY_TICK)
        val p = Prefs(ctx)
        val hasWidget = mgr.getAppWidgetIds(ComponentName(ctx, ShipTimeWidget::class.java)).isNotEmpty() ||
            mgr.getAppWidgetIds(ComponentName(ctx, ShipTimeWidgetLarge::class.java)).isNotEmpty()
        if (p.ddayDate == null || !hasWidget) {
            am.cancel(pi)
            return
        }
        // 자정 직후에 깨워 날짜 경계를 확실히 넘긴다
        val at = Dday.nextMidnightMs(p.offsetMinutes) + 5_000
        am.setAndAllowWhileIdle(AlarmManager.RTC, at, pi)
    }

    private fun updateProvider(ctx: Context, mgr: AppWidgetManager, cls: Class<*>, layout: Int) {
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, cls))
        if (ids.isEmpty()) return
        val views = build(ctx, layout)
        for (id in ids) mgr.updateAppWidget(id, views)
    }

    private fun build(ctx: Context, layout: Int): RemoteViews {
        val p = Prefs(ctx)
        val o = p.offsetMinutes
        val v = RemoteViews(ctx.packageName, layout)
        v.setTextViewText(R.id.offset_label, Zones.label(o))
        v.setString(R.id.ship_clock, "setTimeZone", Zones.displayZoneId(o))
        v.setString(R.id.utc_clock, "setTimeZone", "Etc/UTC")
        v.setTextViewText(R.id.header, headerText(p, o))
        v.setInt(R.id.widget_bg, "setImageAlpha", p.widgetOpacity * 255 / 100)

        val (text, color) = statusOf(ctx, p, o)
        v.setTextViewText(R.id.status, text)
        v.setTextColor(R.id.status, ctx.getColor(color))

        v.setOnClickPendingIntent(R.id.btn_minus, actionIntent(ctx, ShipTimeWidget.ACTION_MINUS, 1))
        v.setOnClickPendingIntent(R.id.btn_plus, actionIntent(ctx, ShipTimeWidget.ACTION_PLUS, 2))
        v.setOnClickPendingIntent(R.id.btn_minus30, actionIntent(ctx, ShipTimeWidget.ACTION_MINUS30, 4))
        v.setOnClickPendingIntent(R.id.btn_plus30, actionIntent(ctx, ShipTimeWidget.ACTION_PLUS30, 5))
        val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        v.setOnClickPendingIntent(
            R.id.clock_area,
            PendingIntent.getActivity(ctx, 3, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        )
        return v
    }

    /** 디데이가 있으면 "D-12 입항", 없으면 "SHIP TIME" */
    private fun headerText(p: Prefs, o: Int): String {
        val date = p.ddayDate ?: return "SHIP TIME"
        val days = Dday.daysLeft(date, o) ?: return "SHIP TIME"
        return listOf(Dday.label(days), p.ddayTitle).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun statusOf(ctx: Context, p: Prefs, o: Int): Pair<String, Int> {
        if (!p.sync) return "표시 전용" to R.color.widget_sub
        if (Zones.zoneId(o) == null) return "표시 전용" to R.color.status_warn
        return when (p.statusState) {
            Prefs.STATE_PENDING -> "적용 중…" to R.color.status_warn
            Prefs.STATE_FAIL -> "적용 실패" to R.color.status_fail
            else -> {
                val sysMs = Zones.offsetMsOf(SysTz.current())
                if (sysMs == o * 60_000) "시스템 연동" to R.color.status_ok
                else "기기 시계와 다름" to R.color.status_warn
            }
        }
    }

    private fun actionIntent(ctx: Context, action: String, req: Int): PendingIntent {
        val i = Intent(ctx, ShipTimeWidget::class.java).setAction(action)
        return PendingIntent.getBroadcast(ctx, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private const val REQ_DAY_TICK = 6
}

/** 큰 고양이 위젯 — 동작은 작은 위젯과 동일, 레이아웃만 다르다. */
class ShipTimeWidgetLarge : ShipTimeWidget()

/** adb shell dpm set-device-owner com.edencrew.shiptime/.ShipAdminReceiver 로 등록 시 사용 (선택) */
class ShipAdminReceiver : DeviceAdminReceiver()
