package com.sinseunghyeon.doros

import android.Manifest
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 무선 디버깅 스위치 제어.
 *
 * 처음 ADB 연결에 성공하면 그 셸 권한으로 이 앱에 WRITE_SECURE_SETTINGS 를 부여해 둔다
 * (AdbPriv.onConnected). 이후로는 앱이 직접 `adb_wifi_enabled` 를 켤 수 있어서
 * 재부팅 뒤에도 사용자가 개발자 옵션에 들어갈 필요가 없다.
 *
 * 제약 (AOSP AdbDebuggingManager):
 *  - "이 네트워크에서 항상 허용"은 AP(BSSID)마다 따로 저장된다. 처음 보는 AP 에서는 확인 창이 뜬다.
 *  - Wi-Fi 가 꺼지거나 끊기거나 다른 AP 로 옮기면 시스템이 무선 디버깅을 강제로 끈다.
 *    → "Wi-Fi 없이도 동작"(AdbPriv.ensureTcpMode)이 켜져 있으면 127.0.0.1:5555 로 계속 접속한다.
 */
object WirelessAdb {
    /** Settings.Global.ADB_WIFI_ENABLED (숨김 상수) */
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

    fun hasSecureSettings(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun isEnabled(ctx: Context): Boolean = try {
        Settings.Global.getInt(ctx.contentResolver, ADB_WIFI_ENABLED, 0) == 1
    } catch (_: Throwable) {
        false
    }

    /** 자동 켜기를 써도 되는 상태인지: 한 번이라도 연결에 성공했고, 권한이 있고, 사용자가 끄지 않았다 */
    fun autoAllowed(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val p = Prefs(ctx)
        return p.autoAdb && p.adbEverConnected && hasSecureSettings(ctx)
    }

    /**
     * 꺼져 있으면 켠다.
     * @return 이번 호출로 새로 켰으면 true (adbd 가 뜨고 mDNS 광고까지 잠시 기다려야 함)
     */
    fun enableIfAllowed(ctx: Context): Boolean {
        // Wi-Fi 가 없으면 켜도 시스템이 바로 다시 끈다 (AdbDebuggingManager)
        if (!autoAllowed(ctx) || isEnabled(ctx) || !wifiConnected(ctx)) return false
        return try {
            Settings.Global.putInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
            Settings.Global.putInt(ctx.contentResolver, ADB_WIFI_ENABLED, 1)
        } catch (_: Throwable) {
            false
        }
    }

    /** Wi-Fi 에 연결돼 있는지 (인터넷 여부와 무관 — 선내 Wi-Fi 는 인터넷이 없을 수 있다) */
    fun wifiConnected(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    } catch (_: Throwable) {
        false
    }

    /**
     * Wi-Fi 가 연결될 때마다 WifiAvailableReceiver 를 깨우도록 등록한다 (앱 프로세스가 없어도 동작).
     * 같은 PendingIntent 로 다시 등록하면 이전 등록을 대체하므로 여러 번 불러도 된다.
     * 재부팅·앱 업데이트 때 풀리므로 부팅/앱 실행/위젯 갱신 때 다시 부른다.
     */
    fun watchWifi(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                // 인터넷이 없는 선내 Wi-Fi 도 포함
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, WifiAvailableReceiver::class.java), flags)
            cm.registerNetworkCallback(req, pi)
        } catch (t: Throwable) {
            android.util.Log.w("ShipTime", "Wi-Fi 감시 등록 실패", t)
            ErrorReport.failure("Wi-Fi 감시 등록 실패", t)
        }
    }

    /**
     * ADB 연결 직후 공통 처리 (앱 연결·알림 페어링·재연결 작업):
     * Wi-Fi 없이도 동작 준비(TCP 5555) → Wi-Fi 감시·연결 유지 등록 → 적용 대기 중이면 적용.
     * @return 로그
     */
    fun onAdbReady(ctx: Context, dl: Deadline = Deadline(25_000)): List<String> {
        val log = mutableListOf<String>()
        AdbPriv.ensureTcpMode(dl.cap(10_000))?.let { log += "Wi-Fi 없이도 동작: $it" }
        watchWifi(ctx)
        AdbKeepAliveJob.schedule(ctx)
        val p = Prefs(ctx)
        if (p.pendingApply && p.sync && dl.remaining() > 3_000) {
            val out = ShipTimeController.reapply(ctx, dl.cap(12_000))
            log += "적용 대기 중이던 시간대 적용: ${out.message}"
        }
        return log
    }

    class SyncResult(val ok: Boolean, val log: List<String>, val changed: Boolean)

    /**
     * 연결 확인(필요하면 무선 디버깅 켜기·재연결) → 새로 연결됐으면 onAdbReady (TCP 5555 준비, 적용 대기 처리).
     * 이미 연결돼 있으면 가볍게 끝난다 (echo 확인).
     */
    fun reconnectAndSync(app: Context, title: String, attempts: Int, budgetMs: Long = 45_000): SyncResult {
        val dl = Deadline(budgetMs)
        val log = mutableListOf(title)
        var ok = false
        var changed = false
        try {
            AdbPriv.init(app)
            val wasConnected = AdbPriv.connected
            val wifi = wifiConnected(app)
            if (!wifi) log += "Wi-Fi 없음 → 무선 디버깅 대신 루프백(5555)만 시도"
            // adbd 기동 + mDNS 광고까지 수 초 걸릴 수 있어 몇 번 나눠 시도 (무선 디버깅 켜기는 ensureConnected 가 한다)
            for (attempt in 1..attempts) {
                if (AdbPriv.ensureConnected(dl.cap(8_000))) {
                    ok = true
                    break
                }
                // Wi-Fi 도 루프백도 안 되면 더 기다려도 소용없다
                if (!wifiConnected(app) || dl.remaining() < 15_000) break
                Thread.sleep(3_000)
            }
            log += if (ok) "✓ ADB 연결됨 (${AdbPriv.via ?: "?"})"
            else "✗ 연결 실패: ${AdbPriv.lastError ?: "알 수 없음"}"
            val p = Prefs(app)
            // 새로 연결됐거나, 적용 대기가 있거나, 아직 Wi-Fi 없이도 동작 준비 전(예: 위젯이 무선 디버깅으로 먼저 연결)
            val needTcp = p.adbNoWifi && !AdbPriv.tcpModeReady
            if (ok && (!wasConnected || p.pendingApply || needTcp)) {
                log += onAdbReady(app, Deadline(dl.remaining()))
                changed = true
            } else if (!p.adbNoWifi) {
                AdbPriv.ensureTcpMode(0)?.let { log += it; changed = true } // 앱이 켰던 USB 디버깅 정리 재시도
            }
        } catch (t: Throwable) {
            log += "✗ 예외: ${t.javaClass.simpleName}: ${t.message}"
            changed = true
        }
        return SyncResult(ok, log, changed)
    }

    /** 개발자 옵션의 "무선 디버깅" 항목으로 이동 (항목 강조는 지원하는 기기에서만) */
    fun openSettings(ctx: Context) {
        val i = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            .putExtra(":settings:show_fragment_args", Bundle().apply {
                putString(":settings:fragment_args_key", "toggle_adb_wireless")
            })
        try {
            ctx.startActivity(i)
        } catch (_: Throwable) {
        }
    }
}

/** 부팅 완료 → Wi-Fi 감시 등록 (Wi-Fi 가 이미 있으면 바로 발동해 재연결) + 연결 유지 작업 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // 재부팅으로 사라진 디데이 자정 갱신 알람을 다시 건다
        WidgetUpdater.updateAll(ctx)
        if (!WirelessAdb.autoAllowed(ctx)) return
        WirelessAdb.watchWifi(ctx)
        AdbKeepAliveJob.schedule(ctx)
    }
}

/**
 * Wi-Fi 연결 이벤트 (WirelessAdb.watchWifi 의 PendingIntent) → 그 자리에서 재연결·적용 대기 처리.
 *
 * - JobScheduler 를 거치지 않는다: 앱을 오래 열지 않으면 대기 버킷 할당량 때문에 작업이 몇 시간씩 밀린다.
 *   백그라운드 브로드캐스트는 goAsync 로 약 60초까지 쓸 수 있다.
 * - PendingIntent 네트워크 콜백은 한 번 보내고 5초 뒤 해제되는 일회성이다 (ConnectivityService.onSendFinished).
 *   Wi-Fi 가 연결된 채로 곧바로 다시 등록하면 즉시 또 발동해 루프가 되므로, 30분 뒤 알람으로 다시 등록한다.
 *   → 새 Wi-Fi 연결 때 즉시 처리 + Wi-Fi 가 있는 동안 30분마다 점검.
 */
class WifiAvailableReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        if (!WirelessAdb.autoAllowed(app)) return
        if (intent.action == ACTION_REWATCH) {
            WirelessAdb.watchWifi(app)
            return
        }
        @Suppress("DEPRECATION")
        val net = intent.getParcelableExtra<android.net.Network>(ConnectivityManager.EXTRA_NETWORK)
        val isNew = net == null || net.networkHandle != lastHandle
        lastHandle = net?.networkHandle
        // 등록이 겹쳐 콜백이 여러 번 와도 한 번만 처리 (같은 잠금에서 줄 서서 60초를 넘기지 않게)
        if (!running.compareAndSet(false, true)) {
            scheduleRewatch(app)
            return
        }
        val pending = goAsync()
        thread(name = "wifi-reconnect") {
            try {
                val r = WirelessAdb.reconnectAndSync(app, if (isNew) "Wi-Fi 연결됨" else "Wi-Fi 정기 점검", attempts = 2)
                // 같은 Wi-Fi 의 정기 점검은 무언가 바뀌었을 때만 기록 (30분마다 로그가 쌓이지 않게)
                if (isNew || r.changed) Prefs(app).appendLog(r.log)
                WidgetUpdater.updateAll(app)
            } catch (t: Throwable) {
                android.util.Log.w("ShipTime", "Wi-Fi 재연결 예외", t)
                ErrorReport.failure("Wi-Fi 재연결 예외", t)
            } finally {
                running.set(false)
                scheduleRewatch(app)
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REWATCH = "com.sinseunghyeon.doros.REWATCH_WIFI"
        private const val REWATCH_MS = 30 * 60_000L

        /** 마지막으로 처리한 Wi-Fi 네트워크 (프로세스가 죽으면 초기화 → 새 연결로 취급) */
        @Volatile private var lastHandle: Long? = null
        private val running = java.util.concurrent.atomic.AtomicBoolean(false)

        fun scheduleRewatch(ctx: Context) {
            val am = ctx.getSystemService(android.app.AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(
                ctx, 1,
                Intent(ctx, WifiAvailableReceiver::class.java).setAction(ACTION_REWATCH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            am.set(android.app.AlarmManager.ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime() + REWATCH_MS, pi)
        }
    }
}

/**
 * 하루 두 번 ADB 에 접속해 둔다. 시스템은 7일 동안 접속이 없는 키를 지워 버리므로
 * (AdbDebuggingManager.filterOutOldKeys, adb_allowed_connection_time 기본값) 긴 항해 중 페어링이 풀리지 않게 한다.
 * Wi-Fi 가 없어도 "Wi-Fi 없이도 동작"으로 adbd 가 5555 에 떠 있으면 접속된다.
 */
class AdbKeepAliveJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        if (!WirelessAdb.autoAllowed(app)) return false
        thread(name = "adb-keepalive") {
            val r = WirelessAdb.reconnectAndSync(app, "연결 유지 점검", attempts = 2)
            // 성공은 조용히, 실패나 변화만 기록 (로그가 하루 두 줄씩 쌓이지 않게)
            if (!r.ok || r.changed) Prefs(app).appendLog(r.log)
            WidgetUpdater.updateAll(app)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val JOB_ID = 4212

        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, AdbKeepAliveJob::class.java))
                .setPeriodic(TimeUnit.HOURS.toMillis(12))
                .setPersisted(true)
                .build()
            js.schedule(job)
        }
    }
}
