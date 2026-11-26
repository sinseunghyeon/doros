package com.sinseunghyeon.doros

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors

class MainActivity : FlutterActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var pendingPairResult: MethodChannel.Result? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdbPriv.init(applicationContext)
        if (WirelessAdb.autoAllowed(applicationContext)) {
            WirelessAdb.watchWifi(applicationContext)
            AdbKeepAliveJob.schedule(applicationContext)
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            val ctx = applicationContext
            val p = Prefs(ctx)
            when (call.method) {
                "getState" -> background(result) { state(ctx) }
                "adjust" -> background(result) {
                    val o = ShipTimeController.adjust(ctx, call.argument<Int>("deltaMinutes") ?: 0, 15000)
                    state(ctx) + mapOf("lastOk" to o.ok, "lastMessage" to o.message)
                }
                "setOffset" -> background(result) {
                    val o = ShipTimeController.setOffset(ctx, call.argument<Int>("offsetMinutes") ?: 0, 15000)
                    state(ctx) + mapOf("lastOk" to o.ok, "lastMessage" to o.message)
                }
                "reapply" -> background(result) {
                    val o = ShipTimeController.reapply(ctx)
                    state(ctx) + mapOf("lastOk" to o.ok, "lastMessage" to o.message)
                }
                "adoptSystemOffset" -> background(result) {
                    p.offsetMinutes = Zones.systemOffsetMinutes()
                    p.setStatus(Prefs.STATE_NONE, "")
                    WidgetUpdater.updateAll(ctx)
                    state(ctx)
                }
                "setSync" -> background(result) {
                    p.sync = call.argument<Boolean>("value") ?: true
                    p.setStatus(Prefs.STATE_NONE, "")
                    WidgetUpdater.updateAll(ctx)
                    state(ctx)
                }
                "setDday" -> background(result) {
                    // date 가 null/빈 문자열이면 디데이 삭제
                    val date = call.argument<String>("date")
                    if (date != null && date.isNotBlank() && Dday.daysLeft(date, 0) == null) {
                        throw IllegalArgumentException("날짜 형식 오류: $date")
                    }
                    p.setDday(call.argument<String>("title") ?: "", date)
                    WidgetUpdater.updateAll(ctx)
                    state(ctx)
                }
                "setWidgetOpacity" -> background(result) {
                    p.widgetOpacity = call.argument<Int>("value") ?: 90
                    WidgetUpdater.updateAll(ctx)
                    state(ctx)
                }
                "setUseRoot" -> background(result) {
                    val v = call.argument<Boolean>("value") ?: false
                    p.useRoot = v
                    if (v) RootPriv.check()
                    state(ctx)
                }
                "adbConnect" -> background(result) {
                    AdbPriv.init(ctx)
                    val ok = AdbPriv.connect(10_000)
                    if (ok) afterConnected(ctx)
                    state(ctx) + mapOf("adbConnectOk" to ok, "adbError" to AdbPriv.lastError)
                }
                "adbDisconnect" -> background(result) {
                    AdbPriv.disconnect()
                    state(ctx)
                }
                "adbDiscoverPairingPort" -> background(result) {
                    AdbPriv.init(ctx)
                    mapOf("port" to AdbPriv.discoverPairingPort(20_000))
                }
                "adbPair" -> background(result) {
                    AdbPriv.init(ctx)
                    val port = call.argument<Int>("port") ?: 0
                    val code = call.argument<String>("code") ?: ""
                    val ok = AdbPriv.pair(port, code)
                    if (ok) afterConnected(ctx)
                    state(ctx) + mapOf("adbPairOk" to ok, "adbError" to AdbPriv.lastError)
                }
                "openDateSettings" -> {
                    startActivity(Intent(Settings.ACTION_DATE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    result.success(true)
                }
                "openDeveloperSettings" -> {
                    try {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        result.success(true)
                    } catch (_: Throwable) {
                        result.success(false)
                    }
                }
                "startNotificationPairing" -> startNotificationPairing(result)
                "openWirelessDebugging" -> {
                    WirelessAdb.openSettings(this)
                    result.success(true)
                }
                "setAdbNoWifi" -> background(result) {
                    val v = call.argument<Boolean>("value") ?: true
                    p.adbNoWifi = v
                    AdbPriv.init(ctx)
                    val note = if (v) {
                        if (AdbPriv.connected) AdbPriv.ensureTcpMode(10_000) else null
                    } else {
                        AdbPriv.disableTcpMode(10_000)
                    }
                    note?.let { p.appendLog(listOf("Wi-Fi 없이도 동작 ${if (v) "켬" else "끔"}: $it")) }
                    state(ctx)
                }
                "setAutoAdb" -> background(result) {
                    p.autoAdb = call.argument<Boolean>("value") ?: true
                    state(ctx)
                }
                "getLog" -> result.success(p.log)
                "clearLog" -> {
                    p.clearLog()
                    result.success(true)
                }
                "reportError" -> {
                    val message = call.argument<String>("message") ?: "flutter"
                    val stack = call.argument<String>("stack")
                    val text = if (stack.isNullOrBlank()) message else "$message\n$stack"
                    ErrorReport.failure(text)
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        }
    }

    /**
     * 알림 페어링 시작: (Android 13+) 알림 권한 확보 → 서비스 시작 → 무선 디버깅 설정 열기.
     * 결과: {started: bool, reason?: "notification_denied"}
     */
    private fun startNotificationPairing(result: MethodChannel.Result) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingPairResult?.success(mapOf("started" to false, "reason" to "superseded"))
            pendingPairResult = result
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        launchPairing(result)
    }

    private fun launchPairing(result: MethodChannel.Result) {
        try {
            PairingService.start(this)
            WirelessAdb.openSettings(this)
            result.success(mapOf("started" to true))
        } catch (t: Throwable) {
            result.success(mapOf("started" to false, "reason" to (t.message ?: t.javaClass.simpleName)))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_NOTIF) return
        val r = pendingPairResult ?: return
        pendingPairResult = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            launchPairing(r)
        } else {
            r.success(mapOf("started" to false, "reason" to "notification_denied"))
        }
    }

    private fun background(result: MethodChannel.Result, block: () -> Any?) {
        io.execute {
            try {
                val v = block()
                main.post { result.success(v) }
            } catch (t: Throwable) {
                main.post { result.error("native", t.message ?: t.javaClass.simpleName, null) }
            }
        }
    }

    private fun afterConnected(ctx: Context) {
        Prefs(ctx).appendLog(WirelessAdb.onAdbReady(ctx))
    }

    private fun state(ctx: Context): Map<String, Any?> {
        AdbPriv.init(ctx)
        val p = Prefs(ctx)
        val sysZone = SysTz.current()
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val autoTz = try {
            Settings.Global.getInt(ctx.contentResolver, Settings.Global.AUTO_TIME_ZONE, -1)
        } catch (_: Throwable) {
            -1
        }
        val o = p.offsetMinutes
        val ddayDate = p.ddayDate
        return mapOf(
            "offsetMinutes" to o,
            "label" to Zones.label(o),
            "zoneId" to Zones.zoneId(o),
            "systemSupported" to (Zones.zoneId(o) != null),
            "ddayTitle" to p.ddayTitle,
            "ddayDate" to ddayDate,
            "ddayDays" to ddayDate?.let { Dday.daysLeft(it, o) },
            "ddayLabel" to ddayDate?.let { Dday.daysLeft(it, o) }?.let { Dday.label(it) },
            "widgetOpacity" to p.widgetOpacity,
            "sync" to p.sync,
            "useRoot" to p.useRoot,
            "rootVerified" to RootPriv.verified,
            "statusState" to p.statusState,
            "statusText" to p.statusText,
            "systemZone" to sysZone,
            "systemOffsetMinutes" to ((Zones.offsetMsOf(sysZone) ?: 0) / 60000),
            "autoTimeZone" to autoTz,
            "adbSupported" to AdbPriv.supported(),
            "adbConnected" to AdbPriv.connected,
            "adbNeedsPairing" to AdbPriv.needsPairing,
            "adbError" to AdbPriv.lastError,
            "adbVia" to AdbPriv.via.takeIf { AdbPriv.connected },
            "adbViaLoopback" to AdbPriv.viaLoopback,
            "adbNoWifi" to p.adbNoWifi,
            "pendingApply" to p.pendingApply,
            "wifiConnected" to WirelessAdb.wifiConnected(ctx),
            "deviceOwner" to dpm.isDeviceOwnerApp(ctx.packageName),
            "autoAdb" to p.autoAdb,
            "secureSettings" to WirelessAdb.hasSecureSettings(ctx),
            "adbEverConnected" to p.adbEverConnected,
            "adbWifiEnabled" to WirelessAdb.isEnabled(ctx),
            "sentryEnabled" to (!BuildConfig.DEBUG && BuildConfig.SENTRY_DSN.isNotEmpty()),
            "sdk" to Build.VERSION.SDK_INT,
            "release" to Build.VERSION.RELEASE,
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        )
    }

    companion object {
        private const val CHANNEL = "shiptime/native"
        private const val REQ_NOTIF = 4213
    }
}
