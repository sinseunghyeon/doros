package com.edencrew.shiptime

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * 선박 시간 변경의 단일 진입점. 위젯 버튼과 Flutter 앱 모두 여기를 호출한다.
 *
 * 시스템 시간대 변경 전략 (위에서부터 시도, 각 단계 후 persist.sys.timezone 으로 검증):
 *  0. Device Owner 로 등록돼 있으면 DevicePolicyManager.setTimeZone (공식 API)
 *  1. ADB(무선 디버깅) / Root 로 셸 권한
 *  2. `cmd time_zone_detector suggest_manual_time_zone` (Android 12+)
 *  3. `service call alarm <code> s16 <zone>`
 *  4. Root 전용: setprop + TIMEZONE_CHANGED 브로드캐스트 (최후 수단)
 */
object ShipTimeController {
    private val lock = Any()

    /** waiting: 권한 경로(ADB)가 없어 적용하지 못함 → 연결되면 자동 적용 */
    data class Outcome(val ok: Boolean, val message: String, val waiting: Boolean = false)

    private class Strategy(val name: String, val run: () -> String)

    // 현재 오프셋 읽기까지 잠금 안에서 해야 위젯 연타 시 두 스레드가 같은 값을 읽지 않는다
    /** deltaMin: 분 단위 (±60, ±30) */
    fun adjust(ctx: Context, deltaMin: Int, budgetMs: Long = 8000): Outcome = synchronized(lock) {
        setOffset(ctx, Prefs(ctx).offsetMinutes + deltaMin, budgetMs)
    }

    /** newOffsetMin: 분 단위 UTC 오프셋 */
    fun setOffset(ctx: Context, newOffsetMin: Int, budgetMs: Long = 8000): Outcome = synchronized(lock) {
        val app = ctx.applicationContext
        val p = Prefs(app)
        val o = Zones.clamp(newOffsetMin)
        p.offsetMinutes = o
        if (!p.sync) {
            p.pendingApply = false
            p.setStatus(Prefs.STATE_NONE, "표시 전용")
            WidgetUpdater.updateAll(app)
            return Outcome(true, "선박 시간 ${Zones.label(o)} (시스템 미연동)")
        }
        val target = Zones.zoneId(o)
        if (target == null) {
            // +1:30 같은 값은 tzdata 에 고정 시간대가 없어 시스템에 적용할 수 없다
            p.pendingApply = false
            p.setStatus(Prefs.STATE_NONE, "표시 전용 (시스템 시간대 없음)")
            p.appendLog(listOf("${Zones.label(o)}: 해당하는 시스템 시간대가 없어 표시만 함"))
            WidgetUpdater.updateAll(app)
            return Outcome(true, "선박 시간 ${Zones.label(o)} (시스템 시간대 없음 – 표시 전용)")
        }
        p.setStatus(Prefs.STATE_PENDING, "적용 중…")
        WidgetUpdater.updateAll(app)
        val out = applySystemZone(app, o, target, budgetMs)
        p.pendingApply = out.waiting
        // 대기가 생기면 Wi-Fi 감시를 (다시) 건다 → Wi-Fi 가 돌아오는 순간 WifiAvailableReceiver 가 적용
        if (out.waiting && WirelessAdb.autoAllowed(app)) WirelessAdb.watchWifi(app)
        p.setStatus(
            when {
                out.ok -> Prefs.STATE_OK
                out.waiting -> Prefs.STATE_WAITING
                else -> Prefs.STATE_FAIL
            },
            out.message,
        )
        WidgetUpdater.updateAll(app)
        out
    }

    /** 현재 저장된 오프셋을 시스템에 다시 적용 */
    fun reapply(ctx: Context, budgetMs: Long = 15000): Outcome = setOffset(ctx, Prefs(ctx).offsetMinutes, budgetMs)

    private fun applySystemZone(ctx: Context, offset: Int, target: String, budgetMs: Long): Outcome {
        val p = Prefs(ctx)
        val log = mutableListOf<String>()
        val dl = Deadline(budgetMs)
        val targetMs = offset * 60_000

        fun finish(ok: Boolean, msg: String, waiting: Boolean = false): Outcome {
            log += (if (ok) "✓ " else if (waiting) "… " else "✗ ") + msg
            p.appendLog(log)
            return Outcome(ok, msg, waiting)
        }

        Hidden.init()
        log += "적용 ${Zones.label(offset)} → $target | Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) ${Build.MANUFACTURER} ${Build.MODEL}"
        val before = SysTz.current()
        log += "현재 시스템 시간대: $before"
        if (Zones.offsetMsOf(before) == targetMs) {
            return finish(true, "이미 ${Zones.label(offset)} ($before)")
        }

        // 0. Device Owner
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (dpm.isDeviceOwnerApp(ctx.packageName)) {
                val admin = ComponentName(ctx, ShipAdminReceiver::class.java)
                log += "▶ DeviceOwner.setTimeZone"
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) dpm.setAutoTimeZoneEnabled(admin, false)
                    log += "  결과: ${dpm.setTimeZone(admin, target)}"
                    if (verify(target, targetMs, dl, log)) return finish(true, "${Zones.label(offset)} 적용 (DeviceOwner)")
                } catch (t: Throwable) {
                    log += "  예외: ${t.javaClass.simpleName}: ${t.message}"
                }
            }
        }

        // 권한 주체 선택 (ADB 무선 디버깅 우선 — 루트 불필요)
        AdbPriv.init(ctx)
        val priv: Priv? = pickPriv(ctx, p, dl, log)
        if (priv == null) {
            // 오프셋은 저장돼 있으니, 연결이 돌아오면 (Wi-Fi 연결 이벤트·연결 유지 작업) 자동으로 적용한다
            val why = when {
                AdbPriv.needsPairing -> "페어링 필요"
                !WirelessAdb.wifiConnected(ctx) -> "Wi-Fi 없음"
                else -> "무선 디버깅 미연결"
            }
            return finish(false, "${Zones.label(offset)} 적용 대기 – $why (연결되면 자동 적용)", waiting = true)
        }
        log += "권한 주체: ${priv.name}"

        disableAutoTimeZone(ctx, priv, dl, log)

        val code = Hidden.txCode("android.app.IAlarmManager", "setTimeZone")
        log += "IAlarmManager.setTimeZone 트랜잭션 코드: ${code ?: "조회 실패"}"

        val strategies = buildList<Strategy> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Strategy("cmd time_zone_detector suggest_manual_time_zone") {
                    priv.exec("cmd time_zone_detector suggest_manual_time_zone --zone_id $target", dl.cap(3000)).summary()
                })
            }
            if (code != null) {
                add(Strategy("service call alarm $code") {
                    priv.exec("service call alarm $code s16 $target", dl.cap(3000)).summary()
                })
            }
            if (priv === RootPriv) {
                add(Strategy("setprop + TIMEZONE_CHANGED") {
                    priv.exec(
                        "setprop persist.sys.timezone $target; " +
                            "am broadcast -a android.intent.action.TIMEZONE_CHANGED --es time-zone $target",
                        dl.cap(4000),
                    ).summary()
                })
            }
        }

        for (s in strategies) {
            if (dl.expired) {
                log += "시간 초과로 중단"
                break
            }
            log += "▶ ${s.name}"
            try {
                log += "  ${s.run()}"
            } catch (t: Throwable) {
                val c = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
                log += "  예외: ${c.javaClass.simpleName}: ${c.message}"
                continue
            }
            if (verify(target, targetMs, dl, log)) {
                return finish(true, "${Zones.label(offset)} 적용 (${s.name.substringBefore(' ')})")
            }
        }
        return finish(false, "시스템 시간대 변경 실패 – 로그 확인")
    }

    private fun pickPriv(ctx: Context, p: Prefs, dl: Deadline, log: MutableList<String>): Priv? {
        if (AdbPriv.supported()) {
            if (AdbPriv.ensureConnected(dl.cap(5000))) {
                log += "ADB 연결됨 (${AdbPriv.via ?: "?"})"
                // 무선 디버깅으로 붙었고 아직 5555 준비 전이면 Wi-Fi 감시를 건다 → 즉시 발동해
                // WifiAvailableReceiver 가 (위젯 시간 예산과 별개로) Wi-Fi 없이도 동작을 준비한다
                if (p.adbNoWifi && !AdbPriv.tcpModeReady && WirelessAdb.autoAllowed(ctx)) {
                    WirelessAdb.watchWifi(ctx)
                }
                return AdbPriv
            }
            log += "ADB 미연결: ${AdbPriv.lastError ?: "무선 디버깅 확인"}" +
                if (AdbPriv.needsPairing) " (페어링 필요)" else ""
        } else {
            log += "ADB 무선 디버깅: Android 11 미만에서는 미지원"
        }
        if (p.useRoot) {
            if (RootPriv.check(dl.cap(4000))) return RootPriv
            log += "루트(su) 사용 불가"
        }
        return null
    }

    private fun disableAutoTimeZone(ctx: Context, priv: Priv, dl: Deadline, log: MutableList<String>) {
        val cr = ctx.contentResolver
        val cur = try {
            Settings.Global.getInt(cr, Settings.Global.AUTO_TIME_ZONE, 1)
        } catch (_: Throwable) {
            1
        }
        if (cur == 0) {
            log += "자동 시간대: 이미 꺼짐"
            return
        }
        // adb 로 WRITE_SECURE_SETTINGS 를 grant 해 둔 경우
        try {
            Settings.Global.putInt(cr, Settings.Global.AUTO_TIME_ZONE, 0)
            log += "자동 시간대 끔 (앱 권한)"
        } catch (_: Throwable) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val r = priv.exec("cmd time_zone_detector set_auto_detection_enabled false", dl.cap(3000))
            log += "time_zone_detector auto=false: ${r.summary()}"
        }
        val r2 = priv.exec("settings put global auto_time_zone 0", dl.cap(3000))
        log += "settings auto_time_zone=0: ${r2.summary()}"
    }

    private fun verify(target: String, targetMs: Int, dl: Deadline, log: MutableList<String>): Boolean {
        var now = ""
        repeat(8) {
            now = SysTz.current()
            if (now == target || Zones.offsetMsOf(now) == targetMs) {
                log += "  확인됨: $now"
                return true
            }
            if (dl.remaining() < 200) return@repeat
            Thread.sleep(150)
        }
        log += "  미반영 (현재: $now)"
        return false
    }
}
