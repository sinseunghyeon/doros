package com.edencrew.shiptime

import android.content.Context
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 선박 시간은 30분 단위 UTC 오프셋(분)으로 다룬다. (-12:00 ~ +14:00)
 * 정시 값은 Etc/GMT±N 으로, 30분 값은 DST 없는 실제 시간대가 있을 때만 시스템에 적용한다.
 */
object Zones {
    const val MIN = -12 * 60
    const val MAX = 14 * 60
    const val STEP = 30

    /** 범위 안으로 자르고 30분 단위로 반올림 */
    fun clamp(min: Int): Int {
        val c = min.coerceIn(MIN, MAX)
        return Math.floorDiv(c + STEP / 2, STEP) * STEP
    }

    /**
     * 30분 오프셋 중 DST 없이 고정된 tzdata 시간대 (2026 기준).
     * 나머지 30분 값(+1:30 등)은 시스템 시간대가 없어 표시 전용이다.
     */
    private val HALF_HOUR = mapOf(
        -570 to "Pacific/Marquesas",
        210 to "Asia/Tehran",
        270 to "Asia/Kabul",
        330 to "Asia/Kolkata",
        390 to "Asia/Yangon",
        570 to "Australia/Darwin",
    )

    /**
     * 시스템에 적용할 시간대 ID. 없으면 null (표시 전용).
     * POSIX 규칙상 Etc/GMT 의 부호는 반대다. UTC+9 → "Etc/GMT-9"
     * tzdata 에 Etc/GMT-14 ~ Etc/GMT+12 가 모두 존재한다.
     */
    fun zoneId(min: Int): String? {
        if (min % 60 != 0) {
            // 기기 tzdata 가 오래되면 (예: 2022년 이전 tzdata 의 Asia/Tehran 은 여름에 +4:30) 오프셋이 다를 수 있다
            // → 지금 이 기기에서 실제로 같은 오프셋일 때만 시스템에 적용, 아니면 표시 전용
            val id = HALF_HOUR[min] ?: return null
            return id.takeIf { offsetMsOf(it) == min * 60_000 }
        }
        val h = min / 60
        return when {
            h == 0 -> "Etc/GMT"
            h > 0 -> "Etc/GMT-$h"
            else -> "Etc/GMT+${-h}"
        }
    }

    /** 위젯 TextClock 표시용. 시스템 시간대가 없으면 java.util.TimeZone 사용자 지정 ID("GMT+01:30") */
    fun displayZoneId(min: Int): String = zoneId(min) ?: run {
        val a = Math.abs(min)
        String.format(Locale.US, "GMT%s%02d:%02d", if (min < 0) "-" else "+", a / 60, a % 60)
    }

    fun label(min: Int): String {
        val sign = if (min < 0) "-" else "+"
        val a = Math.abs(min)
        return if (a % 60 == 0) "UTC$sign${a / 60}" else "UTC$sign${a / 60}:${(a % 60).toString().padStart(2, '0')}"
    }

    fun offsetMsOf(zoneId: String?): Int? {
        if (zoneId.isNullOrBlank()) return null
        return TimeZone.getTimeZone(zoneId).getOffset(System.currentTimeMillis())
    }

    /** 현재 시스템 오프셋을 분 단위로 (30분 단위 반올림) */
    fun systemOffsetMinutes(): Int {
        val ms = offsetMsOf(SysTz.current()) ?: TimeZone.getDefault().getOffset(System.currentTimeMillis())
        return clamp(ms / 60_000)
    }
}

/** 디데이: 선박 시간 기준 오늘부터 목표일까지 남은 날 수 */
object Dday {
    /** "yyyy-MM-dd" → 남은 날 수 (오늘 = 0, 지났으면 음수). 형식이 틀리면 null */
    fun daysLeft(date: String, offsetMin: Int, nowMs: Long = System.currentTimeMillis()): Long? {
        val target = epochDay(date) ?: return null
        val today = Math.floorDiv(nowMs + offsetMin * 60_000L, 86_400_000L)
        return target - today
    }

    /** 0 → "D-Day", 12 → "D-12", -3 → "D+3" */
    fun label(days: Long): String = when {
        days == 0L -> "D-Day"
        days > 0 -> "D-$days"
        else -> "D+${-days}"
    }

    /** 다음 선박 시간 자정 (위젯 디데이 갱신 시각) */
    fun nextMidnightMs(offsetMin: Int, nowMs: Long = System.currentTimeMillis()): Long {
        val off = offsetMin * 60_000L
        return (Math.floorDiv(nowMs + off, 86_400_000L) + 1) * 86_400_000L - off
    }

    private fun epochDay(date: String): Long? {
        val m = Regex("""(\d{4})-(\d{2})-(\d{2})""").matchEntire(date.trim()) ?: return null
        val (y, mo, d) = m.destructured
        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            isLenient = false
            clear()
            set(y.toInt(), mo.toInt() - 1, d.toInt())
        }
        return try {
            Math.floorDiv(cal.timeInMillis, 86_400_000L)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** 숨김 API 접근 도우미 */
object Hidden {
    @Volatile private var inited = false

    fun init() {
        if (inited) return
        inited = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("L")
            } catch (_: Throwable) {
            }
        }
    }

    /** 예: txCode("android.app.IAlarmManager", "setTimeZone") → 버전별 트랜잭션 코드 */
    fun txCode(iface: String, method: String): Int? = try {
        init()
        val f = Class.forName("$iface\$Stub").getDeclaredField("TRANSACTION_$method")
        f.isAccessible = true
        f.getInt(null)
    } catch (_: Throwable) {
        null
    }

    fun sysProp(key: String): String? = try {
        init()
        val c = Class.forName("android.os.SystemProperties")
        c.getMethod("get", String::class.java).invoke(null, key) as String
    } catch (_: Throwable) {
        null
    }
}

/** 실제 시스템 시간대 읽기 (앱 프로세스의 TimeZone.getDefault() 는 캐시라 늦게 바뀔 수 있음) */
object SysTz {
    fun current(): String {
        Hidden.sysProp("persist.sys.timezone")?.takeIf { it.isNotBlank() }?.let { return it }
        try {
            val p = Runtime.getRuntime().exec(arrayOf("getprop", "persist.sys.timezone"))
            val v = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            if (v.isNotBlank()) return v
        } catch (_: Throwable) {
        }
        return TimeZone.getDefault().id
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("ship_time", Context.MODE_PRIVATE)

    /** 선박 시간 오프셋 (분). 예전 버전의 시간 단위 값(K_OFFSET_HOURS)이 있으면 분으로 옮긴다. */
    var offsetMinutes: Int
        get() = when {
            sp.contains(K_OFFSET_MIN) -> Zones.clamp(sp.getInt(K_OFFSET_MIN, 0))
            sp.contains(K_OFFSET_HOURS) -> Zones.clamp(sp.getInt(K_OFFSET_HOURS, 0) * 60)
            else -> Zones.systemOffsetMinutes()
        }
        set(v) {
            sp.edit().putInt(K_OFFSET_MIN, Zones.clamp(v)).remove(K_OFFSET_HOURS).commit()
        }

    /** 디데이 제목 (예: 입항). 빈 문자열 가능 */
    val ddayTitle: String get() = sp.getString(K_DDAY_TITLE, "") ?: ""

    /** 디데이 날짜 "yyyy-MM-dd". 없으면 null */
    val ddayDate: String? get() = sp.getString(K_DDAY_DATE, null)

    fun setDday(title: String, date: String?) {
        val e = sp.edit()
        if (date.isNullOrBlank()) e.remove(K_DDAY_TITLE).remove(K_DDAY_DATE)
        else e.putString(K_DDAY_TITLE, title.trim()).putString(K_DDAY_DATE, date.trim())
        e.commit()
    }

    /** 위젯 배경 불투명도 (0~100 %). 기본 90 = 예전 배경색 #E6xxxxxx */
    var widgetOpacity: Int
        get() = sp.getInt(K_WIDGET_OPACITY, 90).coerceIn(0, 100)
        set(v) {
            sp.edit().putInt(K_WIDGET_OPACITY, v.coerceIn(0, 100)).commit()
        }

    /** 위젯 버튼이 시스템 시간대도 같이 바꿀지 */
    var sync: Boolean
        get() = sp.getBoolean(K_SYNC, true)
        set(v) {
            sp.edit().putBoolean(K_SYNC, v).commit()
        }

    /** ADB 가 안 될 때 su 로 시도할지 */
    var useRoot: Boolean
        get() = sp.getBoolean(K_ROOT, false)
        set(v) {
            sp.edit().putBoolean(K_ROOT, v).commit()
        }

    /** 재부팅/무선 디버깅 꺼짐 시 앱이 무선 디버깅을 자동으로 켤지 */
    var autoAdb: Boolean
        get() = sp.getBoolean(K_AUTO_ADB, true)
        set(v) {
            sp.edit().putBoolean(K_AUTO_ADB, v).commit()
        }

    /**
     * Wi-Fi 없이도 동작 (실험): 연결될 때 USB 디버깅을 켜고 adbd 를 TCP 5555 로도 띄워 둔다.
     * 시스템은 Wi-Fi 가 끊기면 무선 디버깅을 강제로 끄지만, USB 디버깅이 켜져 있으면 adbd 는 살아 있어
     * 127.0.0.1:5555 로 계속 접속할 수 있다. 5555 는 모든 네트워크에 열리고 재부팅하면 풀린다.
     * USB 디버깅(adb_enabled)은 Settings.Global 이라 재부팅 후에도 남는다 → 끌 때 usbDebugByApp 으로 되돌린다.
     */
    var adbNoWifi: Boolean
        get() = sp.getBoolean(K_ADB_NO_WIFI, true)
        set(v) {
            sp.edit().putBoolean(K_ADB_NO_WIFI, v).commit()
        }

    /** USB 디버깅을 이 앱이 켰는지 — "Wi-Fi 없이도 동작"을 끌 때 원래대로 끄기 위해 */
    var usbDebugByApp: Boolean
        get() = sp.getBoolean(K_USB_BY_APP, false)
        set(v) {
            sp.edit().putBoolean(K_USB_BY_APP, v).commit()
        }

    /** 연결이 없어 시스템에 적용하지 못한 변경이 남아 있는지 — 다시 연결되면 자동 적용 */
    var pendingApply: Boolean
        get() = sp.getBoolean(K_PENDING_APPLY, false)
        set(v) {
            sp.edit().putBoolean(K_PENDING_APPLY, v).commit()
        }

    /** ADB 연결에 한 번이라도 성공했는지 (= 페어링 완료) — 자동 켜기의 전제 */
    var adbEverConnected: Boolean
        get() = sp.getBoolean(K_ADB_EVER, false)
        set(v) {
            sp.edit().putBoolean(K_ADB_EVER, v).commit()
        }

    var statusState: Int
        get() = sp.getInt(K_STATUS_STATE, STATE_NONE)
        private set(v) {
            sp.edit().putInt(K_STATUS_STATE, v).commit()
        }

    var statusText: String
        get() = sp.getString(K_STATUS_TEXT, "") ?: ""
        private set(v) {
            sp.edit().putString(K_STATUS_TEXT, v).commit()
        }

    fun setStatus(state: Int, text: String) {
        sp.edit().putInt(K_STATUS_STATE, state).putString(K_STATUS_TEXT, text).commit()
    }

    val log: String get() = sp.getString(K_LOG, "") ?: ""

    fun appendLog(lines: List<String>) {
        if (lines.isEmpty()) return
        // logcat 으로도 남김 (adb logcat -s ShipTime)
        lines.forEach { android.util.Log.i("ShipTime", it) }
        ErrorReport.diagnostic(lines)
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val merged = (log.lines().filter { it.isNotBlank() } +
            lines.mapIndexed { i, l -> if (i == 0) "[$stamp UTC] $l" else "    $l" })
            .takeLast(MAX_LOG_LINES)
        sp.edit().putString(K_LOG, merged.joinToString("\n")).commit()
    }

    fun clearLog() {
        sp.edit().remove(K_LOG).commit()
    }

    companion object {
        const val STATE_NONE = 0
        const val STATE_OK = 1
        const val STATE_FAIL = 2
        const val STATE_PENDING = 3

        /** 연결이 없어 적용 대기 중 (연결되면 자동 적용) */
        const val STATE_WAITING = 4

        private const val K_OFFSET_HOURS = "offset"
        private const val K_OFFSET_MIN = "offset_min"
        private const val K_DDAY_TITLE = "dday_title"
        private const val K_DDAY_DATE = "dday_date"
        private const val K_WIDGET_OPACITY = "widget_opacity"
        private const val K_SYNC = "sync"
        private const val K_ROOT = "use_root"
        private const val K_AUTO_ADB = "auto_adb"
        private const val K_ADB_EVER = "adb_ever_connected"
        private const val K_ADB_NO_WIFI = "adb_no_wifi"
        private const val K_PENDING_APPLY = "pending_apply"
        private const val K_USB_BY_APP = "usb_debug_by_app"
        private const val K_STATUS_STATE = "status_state"
        private const val K_STATUS_TEXT = "status_text"
        private const val K_LOG = "log"
        private const val MAX_LOG_LINES = 400
    }
}

/** 남은 시간 예산 (위젯 브로드캐스트는 goAsync 로 약 10초 제한) */
class Deadline(budgetMs: Long) {
    private val end = System.currentTimeMillis() + budgetMs
    fun remaining(): Long = (end - System.currentTimeMillis()).coerceAtLeast(0)
    val expired get() = remaining() <= 0
    fun cap(ms: Long) = minOf(ms, remaining()).coerceAtLeast(1)
}
