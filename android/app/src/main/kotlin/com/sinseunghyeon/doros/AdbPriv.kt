package com.sinseunghyeon.doros

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.android.AdbMdns
import io.github.muntashirakon.adb.android.AndroidUtils
import android.provider.Settings
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * 무선 디버깅(ADB)으로 셸 명령을 실행한다. 루트/Device Owner 없이
 * 구글 계정이 있는 기기에서도 시스템 시간대를 바꿀 수 있는 권한 경로.
 *
 * 전제: 개발자 옵션 > 무선 디버깅 ON (Android 11+).
 * 첫 연결 후에는 WRITE_SECURE_SETTINGS 를 스스로 부여해 두고, 무선 디버깅이 꺼져 있으면
 * 앱이 직접 켠다 (WirelessAdb) — 재부팅 후에도 사용자가 다시 켤 필요가 없다.
 *
 * Wi-Fi 없이도 동작 (ensureTcpMode): 시스템은 Wi-Fi 가 끊기거나 AP 가 바뀌면 무선 디버깅을 강제로 끈다.
 * 연결돼 있을 때 USB 디버깅을 켜고 adbd 를 TCP 5555 로도 띄워 두면 (AdbService 는 USB·무선이 둘 다
 * 꺼질 때만 adbd 를 멈춘다) Wi-Fi 가 없어도 127.0.0.1:5555 로 접속할 수 있다. 재부팅하면 풀린다.
 */
object AdbPriv : Priv {
    override val name = "ADB(무선 디버깅)"

    @Volatile private var appCtx: Context? = null
    @Volatile var connected: Boolean = false
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var needsPairing: Boolean = false
        private set

    /** 현재 연결 경로 (예: "127.0.0.1:5555", "무선 디버깅 192.168.0.5:41234") */
    @Volatile var via: String? = null
        private set

    /** 루프백(127.0.0.1:5555)으로 연결돼 있는지 = Wi-Fi 없이도 동작 중 */
    val viaLoopback: Boolean get() = connected && via == LOOPBACK_VIA

    /** 이번 부팅에서 adbd 가 5555 로 대기 중인 것을 확인했는지 (service.adb.tcp.port 는 재부팅 때만 풀린다) */
    @Volatile var tcpModeReady: Boolean = false
        private set

    private val lock = Any()

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun ensureConnected(timeoutMs: Long): Boolean = synchronized(lock) {
        val ctx = appCtx ?: return false.also { lastError = "Context 없음" }
        if (!supported()) {
            lastError = "Android 11 이상 필요"
            return false
        }
        if (connected) {
            try {
                // 살아 있는지 가벼운 명령으로 확인
                val r = execUnlocked("echo ok", minOf(1500, timeoutMs))
                if (r.out.contains("ok")) {
                    onConnected(ctx) // 권한이 (OTA 등으로) 빠졌으면 다시 부여
                    return true
                }
            } catch (_: Throwable) {
            }
            connected = false
        }
        // 재부팅 등으로 무선 디버깅이 꺼졌으면 직접 켜고 adbd 가 뜰 때까지 잠깐 기다린다
        if (WirelessAdb.enableIfAllowed(ctx)) Thread.sleep(minOf(2000L, timeoutMs / 3))
        return connectInternal(ctx, timeoutMs)
    }

    fun connect(timeoutMs: Long = 8000): Boolean = synchronized(lock) {
        val ctx = appCtx ?: return false.also { lastError = "Context 없음" }
        return connectInternal(ctx, timeoutMs)
    }

    fun disconnect() = synchronized(lock) {
        try {
            appCtx?.let { ShipAdbManager.get(it).disconnect() }
        } catch (_: Throwable) {
        }
        connected = false
        via = null
    }

    /**
     * "Wi-Fi 없이도 동작" 준비: USB 디버깅을 켜고 adbd 가 TCP 5555 에서도 대기하게 한다.
     * tcpip:5555 를 보내면 adbd 가 재시작하므로 연결이 끊기고, 127.0.0.1:5555 로 다시 붙는다.
     * @return 로그 한 줄 (할 일이 없었으면 null)
     */
    fun ensureTcpMode(timeoutMs: Long): String? = synchronized(lock) {
        val ctx = appCtx ?: return null
        val p = Prefs(ctx)
        if (!p.adbNoWifi) return cleanupUsbDebug(ctx)
        if (!connected) return null
        val dl = Deadline(timeoutMs)
        val notes = mutableListOf<String>()
        try {
            // 1. USB 디버깅 켜기 — 무선 디버깅이 꺼져도 adbd 가 멈추지 않게
            if (Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) != 1) {
                val r = execUnlocked("settings put global adb_enabled 1", dl.cap(3000))
                if (r.code == 0) Prefs(ctx).usbDebugByApp = true
                notes += "USB 디버깅 켬: ${r.summary()}"
            }
            // 2. 이미 루프백으로 붙어 있거나 5555 로 떠 있으면 끝
            if (via == LOOPBACK_VIA) {
                tcpModeReady = true
                return notes.joinToString(" / ").ifEmpty { null }
            }
            val port = execUnlocked("getprop service.adb.tcp.port", dl.cap(2000)).out.trim()
            if (port == "$TCP_PORT") {
                tcpModeReady = true
                return notes.joinToString(" / ").ifEmpty { null }
            }
            // 3. tcpip:5555 → adbd 재시작 → 루프백으로 다시 연결
            val mgr = ShipAdbManager.get(ctx)
            val reply = try {
                mgr.openStream("tcpip:$TCP_PORT").use { st ->
                    val buf = ByteArray(256)
                    val n = try {
                        st.openInputStream().read(buf)
                    } catch (_: java.io.IOException) {
                        -1
                    }
                    if (n > 0) String(buf, 0, n, StandardCharsets.UTF_8).trim() else ""
                }
            } catch (t: Throwable) {
                "(${t.javaClass.simpleName})"
            }
            notes += "tcpip:$TCP_PORT → ${reply.ifEmpty { "응답 없음" }}"
            connected = false
            via = null
            try {
                mgr.disconnect()
            } catch (_: Throwable) {
            }
            // adbd 가 다시 뜰 때까지 짧게 재시도
            while (!dl.expired) {
                Thread.sleep(minOf(700L, dl.remaining()))
                if (tryLoopback(ctx, mgr, dl)) break
            }
            if (connected) tcpModeReady = true
            notes += if (connected) "✓ $LOOPBACK_VIA 재연결 (이제 Wi-Fi 없이도 동작)"
            else "✗ $LOOPBACK_VIA 재연결 실패 (다음 연결 때 다시 시도)"
        } catch (t: Throwable) {
            notes += "✗ TCP 모드 준비 예외: ${t.javaClass.simpleName}: ${t.message}"
        }
        notes.joinToString(" / ")
    }

    /**
     * "Wi-Fi 없이도 동작" 끄기: adbd 를 TCP 대기 없이 재시작(usb:)하고, 이 앱이 켰던 USB 디버깅을 끈다.
     * 연결돼 있지 않으면 할 수 있는 게 없다 (재부팅하면 어차피 풀림).
     */
    fun disableTcpMode(timeoutMs: Long): String = synchronized(lock) {
        val ctx = appCtx ?: return "Context 없음"
        val p = Prefs(ctx)
        val dl = Deadline(timeoutMs)
        if (!connected && !connectInternal(ctx, dl.cap(5000))) {
            return "ADB 미연결 – 5555 대기는 재부팅하면 풀립니다"
        }
        val notes = mutableListOf<String>()
        // 순서 주의: USB 디버깅을 먼저 끄면 (무선 디버깅도 꺼져 있을 때) adbd 가 멈춰 usb: 를 보낼 수 없다
        try {
            ShipAdbManager.get(ctx).openStream("usb:").use { st ->
                try {
                    st.openInputStream().read(ByteArray(128))
                } catch (_: java.io.IOException) {
                }
            }
            notes += "adbd 5555 대기 해제"
        } catch (t: Throwable) {
            notes += "5555 해제 실패: ${t.javaClass.simpleName}"
        }
        connected = false
        via = null
        tcpModeReady = false
        try {
            ShipAdbManager.get(ctx).disconnect()
        } catch (_: Throwable) {
        }
        cleanupUsbDebug(ctx)?.let { notes += it }
        notes.joinToString(" / ")
    }

    /**
     * "Wi-Fi 없이도 동작"이 꺼져 있는데 이 앱이 켰던 USB 디버깅이 남아 있으면 끈다.
     * WRITE_SECURE_SETTINGS 로 앱이 직접 쓰므로 ADB 연결이 없어도 된다. 실패하면 다음 연결 때 다시 시도.
     */
    private fun cleanupUsbDebug(ctx: Context): String? {
        val p = Prefs(ctx)
        if (p.adbNoWifi || !p.usbDebugByApp) return null
        return try {
            Settings.Global.putInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0)
            p.usbDebugByApp = false
            "앱이 켰던 USB 디버깅 끔"
        } catch (t: Throwable) {
            "USB 디버깅 끄기 실패 (다음에 다시 시도): ${t.javaClass.simpleName}"
        }
    }

    /** 127.0.0.1:5555 접속 시도. 성공하면 connected/via 설정 */
    private fun tryLoopback(ctx: Context, mgr: ShipAdbManager, dl: Deadline): Boolean {
        return try {
            // 기본 타임아웃은 Long.MAX_VALUE 라 (키 확인 창 대기 등) 무한정 막힐 수 있다
            mgr.setTimeout(dl.cap(3000), TimeUnit.MILLISECONDS)
            if (mgr.connect(LOOPBACK, TCP_PORT)) {
                markConnected(ctx, LOOPBACK_VIA)
                true
            } else {
                false
            }
        } catch (_: java.io.IOException) {
            false // 거절 = adbd 가 5555 에서 대기하지 않음
        } catch (e: AdbPairingRequiredException) {
            false
        } finally {
            mgr.setTimeout(IO_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun markConnected(ctx: Context, how: String) {
        ShipAdbManager.get(ctx).setTimeout(IO_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        connected = true
        via = how
        if (how == LOOPBACK_VIA) tcpModeReady = true
        needsPairing = false
        lastError = null
        onConnected(ctx)
    }

    /**
     * 무선 디버깅 "기기 페어링" 화면이 열린 뒤 mDNS 로 페어링 포트를 찾는다.
     * @return 포트 또는 null
     */
    fun discoverPairingPort(timeoutMs: Long = 15_000): Int? {
        val ctx = appCtx ?: return null
        if (!supported()) return null
        val port = AtomicInteger(-1)
        val latch = CountDownLatch(1)
        val mdns = AdbMdns(ctx, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { _, p ->
            if (p > 0) {
                port.set(p)
                latch.countDown()
            }
        }
        mdns.start()
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
        } finally {
            mdns.stop()
        }
        val p = port.get()
        return if (p > 0) p else null
    }

    fun pair(port: Int, pairingCode: String): Boolean = synchronized(lock) {
        val ctx = appCtx ?: return false.also { lastError = "Context 없음" }
        if (!supported()) {
            lastError = "Android 11 이상 필요"
            return false
        }
        return try {
            val mgr = ShipAdbManager.get(ctx)
            // 같은 기기의 페어링 서버이므로 루프백 우선 (getHostIpAddress 는 에뮬레이터에서 호스트 PC 주소)
            val ok = try {
                mgr.pair("127.0.0.1", port, pairingCode.trim())
            } catch (_: java.net.ConnectException) {
                mgr.pair(AndroidUtils.getHostIpAddress(ctx), port, pairingCode.trim())
            }
            if (!ok) {
                lastError = "페어링 실패 (코드/포트 확인)"
                needsPairing = true
                return false
            }
            needsPairing = false
            lastError = null
            connectInternal(ctx, 8000)
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            needsPairing = true
            false
        }
    }

    override fun exec(cmd: String, timeoutMs: Long): ExecResult = synchronized(lock) {
        val ctx = appCtx ?: return ExecResult(null, "Context 없음")
        if (!connected && !connectInternal(ctx, minOf(4000, timeoutMs))) {
            return ExecResult(null, lastError ?: "ADB 미연결")
        }
        return execUnlocked(cmd, timeoutMs)
    }

    private fun connectInternal(ctx: Context, timeoutMs: Long): Boolean {
        return try {
            val mgr = ShipAdbManager.get(ctx)
            val dl = Deadline(timeoutMs.coerceAtLeast(1000L))
            // 끊긴 연결이 살아 있는 것처럼 남아 있으면 connect(host, port) 가 바로 false 를 돌려준다 → 먼저 정리
            try {
                mgr.disconnect()
            } catch (_: Throwable) {
            }
            connected = false
            via = null

            // 1. 루프백 5555: "Wi-Fi 없이도 동작"으로 열어 둔 adbd (또는 에뮬레이터 adb root). Wi-Fi 불필요
            if (tryLoopback(ctx, mgr, dl)) return true

            // 2. 무선 디버깅(mDNS). Wi-Fi 가 없으면 시스템이 무선 디버깅을 꺼 버리므로 기다릴 필요가 없다
            if (!WirelessAdb.wifiConnected(ctx)) {
                lastError = "Wi-Fi 없음 – 무선 디버깅을 쓸 수 없습니다" +
                    if (Prefs(ctx).adbNoWifi) " (Wi-Fi 에서 한 번 연결하면 이후에는 Wi-Fi 없이도 동작)" else ""
                return false
            }
            mgr.setTimeout(dl.remaining().coerceAtLeast(1000L), TimeUnit.MILLISECONDS)
            val ok = try {
                mgr.autoConnect(ctx, dl.remaining().coerceAtLeast(1000L))
            } catch (e: AdbPairingRequiredException) {
                needsPairing = true
                lastError = "페어링 필요"
                connected = false
                return false
            } catch (t: Throwable) {
                try {
                    // autoConnect 는 _adb._tcp(구형 tcpip)와 _adb-tls-connect(무선 디버깅) 중 먼저
                    // 발견된 쪽만 시도한다. 구형 광고가 먼저 잡혀 실패하면 무선 디버깅을 명시적으로 다시 시도.
                    // (InterruptedException = 아무것도 발견 못 함 → TLS 재탐색은 시간 낭비)
                    if (t is InterruptedException || dl.remaining() < 500) throw t
                    mgr.connectTls(ctx, dl.remaining())
                } catch (e: AdbPairingRequiredException) {
                    needsPairing = true
                    lastError = "페어링 필요"
                    connected = false
                    return false
                } catch (t2: Throwable) {
                    // (5555 루프백은 1단계에서 이미 시도했다)
                    android.util.Log.w("ShipTime", "ADB 연결 실패 (무선 디버깅)", t2)
                    lastError = listOfNotNull("auto" to t, ("tls" to t2).takeIf { t2 !== t })
                        .joinToString(" / ") { (k, e) -> "$k: ${e.message ?: e.javaClass.simpleName}" }
                    connected = false
                    return false
                }
            }
            if (ok) {
                markConnected(ctx, "무선 디버깅 ${mgr.hostAddress}")
            } else {
                connected = false
                lastError = "무선 디버깅에 연결할 수 없음 (켜져 있는지 확인)"
            }
            ok
        } catch (t: Throwable) {
            connected = false
            lastError = t.message ?: t.javaClass.simpleName
            if (t is AdbPairingRequiredException || (t.message?.contains("pair", true) == true)) {
                needsPairing = true
            }
            false
        }
    }

    /**
     * 연결 직후 1회: 이 셸 권한으로 앱에 WRITE_SECURE_SETTINGS 를 부여한다.
     * 이후 무선 디버깅 자동 켜기(WirelessAdb)와 자동 시간대 끄기를 앱이 직접 할 수 있다.
     */
    private fun onConnected(ctx: Context) {
        val p = Prefs(ctx)
        if (!p.adbEverConnected) p.adbEverConnected = true
        if (WirelessAdb.hasSecureSettings(ctx)) return
        val r = execUnlocked("pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS", 3000)
        p.appendLog(listOf("WRITE_SECURE_SETTINGS 자체 부여: ${r.summary()}"))
    }

    private fun execUnlocked(cmd: String, timeoutMs: Long): ExecResult {
        val ctx = appCtx ?: return ExecResult(null, "Context 없음")
        return try {
            val mgr = ShipAdbManager.get(ctx)
            // 한 방 실행: shell:cmd … 가 끝나면 스트림이 닫힌다
            val wrapped = "($cmd); echo __SHIP_EXIT__:\$?"
            val stream = mgr.openStream("shell:$wrapped")
            val out = AtomicReference("")
            val err = AtomicReference<Throwable?>(null)
            val latch = CountDownLatch(1)
            thread(isDaemon = true, name = "adb-shell-read") {
                val buf = ByteArrayOutputStream()
                try {
                    stream.openInputStream().use { input ->
                        val b = ByteArray(4096)
                        while (true) {
                            val n = input.read(b)
                            if (n < 0) break
                            buf.write(b, 0, n)
                            // 종료 마커 줄까지 받았으면 스트림 닫힘 신호를 기다리지 않는다
                            if (EXIT_LINE.containsMatchIn(buf.toString(StandardCharsets.UTF_8.name()))) break
                        }
                    }
                } catch (e: java.io.IOException) {
                    // libadb 는 원격이 스트림을 닫는 순간과 read 가 겹치면 -1 대신
                    // IOException("Stream closed.") 를 던진다 → 정상 종료(EOF)로 취급
                    if (!stream.isClosed) err.set(e)
                } catch (t: Throwable) {
                    err.set(t)
                } finally {
                    out.set(buf.toString(StandardCharsets.UTF_8.name()))
                    try {
                        stream.close()
                    } catch (_: Throwable) {
                    }
                    latch.countDown()
                }
            }
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                try {
                    stream.close()
                } catch (_: Throwable) {
                }
                return ExecResult(null, "[timeout ${timeoutMs}ms] ${out.get()}".trim())
            }
            err.get()?.let {
                connected = false
                return ExecResult(null, it.message ?: it.javaClass.simpleName)
            }
            val text = out.get().trim()
            val marker = "__SHIP_EXIT__:"
            val idx = text.lastIndexOf(marker)
            if (idx >= 0) {
                val code = text.substring(idx + marker.length).trim().lines().firstOrNull()?.toIntOrNull()
                val body = text.substring(0, idx).trim()
                return ExecResult(code, body)
            }
            ExecResult(0, text)
        } catch (t: Throwable) {
            connected = false
            lastError = t.message ?: t.javaClass.simpleName
            ExecResult(null, lastError ?: "exec 실패")
        }
    }

    private const val LOOPBACK = "127.0.0.1"
    private const val TCP_PORT = 5555
    private const val LOOPBACK_VIA = "$LOOPBACK:$TCP_PORT"

    /** 연결 후 libadb 기본 타임아웃 (기본값 Long.MAX_VALUE 는 무한 대기가 될 수 있다) */
    private const val IO_TIMEOUT_MS = 30_000L

    /** execUnlocked 가 명령 뒤에 붙이는 종료 코드 줄 (숫자 + 줄바꿈까지 와야 완결) */
    private val EXIT_LINE = Regex("__SHIP_EXIT__:-?\\d+\\s*\\n")
}
