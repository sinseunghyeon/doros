package com.edencrew.shiptime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread

/**
 * debug 빌드 전용. 위젯 버튼과 똑같은 경로(ShipTimeController, 8초 예산)로 명령을 실행하고
 * 결과를 logcat 한 줄로 남긴다. tool/emulator_test.sh 가 이 줄을 파싱한다.
 *
 * cmd: plus | minus | plus30 | minus30 | set(--ei offset N 시간, 또는 --ei offset_min N 분)
 *      | reapply | sync_on | sync_off | state
 *      | pair_start(알림 페어링 시작 — 앱이 포그라운드일 때) | pair_code(--es code NNNNNN: 알림 입력과 같은 경로)
 *      | boot(부팅 시와 같이 Wi-Fi 감시 등록 — Wi-Fi 가 있으면 바로 재연결)
 *      | reconnect(Wi-Fi 연결 시와 같은 재연결·대기 적용을 바로 실행)
 *      | tcp_on / tcp_off(Wi-Fi 없이도 동작 켜기/끄기) | connect(ADB 연결만)
 * 출력: RESULT id=.. cmd=.. ok=.. offset=(9 | 9:30 | -3:30) target=.. sys=.. adb=connected sdk=.. msg=..
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        val cmd = intent.getStringExtra("cmd") ?: "state"
        val id = intent.getStringExtra("id") ?: "-"
        val pending = goAsync()
        thread(name = "ship-time-debug") {
            try {
                val p = Prefs(app)
                val out: ShipTimeController.Outcome? = when (cmd) {
                    "plus" -> ShipTimeController.adjust(app, 60, 8000)
                    "minus" -> ShipTimeController.adjust(app, -60, 8000)
                    "plus30" -> ShipTimeController.adjust(app, 30, 8000)
                    "minus30" -> ShipTimeController.adjust(app, -30, 8000)
                    "set" -> {
                        val min = if (intent.hasExtra("offset_min")) intent.getIntExtra("offset_min", 0)
                        else intent.getIntExtra("offset", 0) * 60
                        ShipTimeController.setOffset(app, min, 8000)
                    }
                    "reapply" -> ShipTimeController.reapply(app, 8000)
                    "sync_on" -> { p.sync = true; null }
                    "sync_off" -> { p.sync = false; null }
                    "pair_start" -> { PairingService.start(app); null }
                    "pair_code" -> {
                        app.startService(
                            Intent(app, PairingService::class.java)
                                .setAction(PairingService.ACTION_SUBMIT)
                                .putExtra(PairingService.KEY_CODE, intent.getStringExtra("code")),
                        )
                        null
                    }
                    "boot" -> { WirelessAdb.watchWifi(app); AdbKeepAliveJob.schedule(app); null }
                    "reconnect" -> {
                        val r = WirelessAdb.reconnectAndSync(app, "debug 재연결", attempts = 2)
                        p.appendLog(r.log)
                        ShipTimeController.Outcome(r.ok, r.log.last())
                    }
                    "connect" -> {
                        AdbPriv.init(app)
                        val ok = AdbPriv.connect(8000)
                        ShipTimeController.Outcome(ok, AdbPriv.lastError ?: "connected")
                    }
                    "tcp_on" -> {
                        AdbPriv.init(app)
                        p.adbNoWifi = true
                        if (!AdbPriv.connected) AdbPriv.connect(8000)
                        ShipTimeController.Outcome(AdbPriv.connected, AdbPriv.ensureTcpMode(10_000) ?: "할 일 없음")
                    }
                    "tcp_off" -> {
                        AdbPriv.init(app)
                        p.adbNoWifi = false
                        ShipTimeController.Outcome(true, AdbPriv.disableTcpMode(10_000))
                    }
                    else -> null
                }
                val o = p.offsetMinutes
                Log.i(
                    TAG,
                    "RESULT id=$id cmd=$cmd ok=${out?.ok} offset=${Zones.label(o).removePrefix("UTC").removePrefix("+")} " +
                        "target=${Zones.zoneId(o)} " +
                        "sys=${SysTz.current()} adb=${AdbPriv.connected} via=${AdbPriv.via?.replace(' ', '_')} " +
                        "wifi=${WirelessAdb.wifiConnected(app)} pending=${p.pendingApply} state=${p.statusState} " +
                        "sdk=${android.os.Build.VERSION.SDK_INT} msg=${out?.message}",
                )
            } catch (t: Throwable) {
                Log.e(TAG, "RESULT id=$id cmd=$cmd ok=false error=$t")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ShipTime"
    }
}
