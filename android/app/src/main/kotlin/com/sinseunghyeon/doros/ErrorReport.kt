package com.sinseunghyeon.doros

import io.sentry.Sentry
import io.sentry.SentryLevel

/**
 * 진단 로그와, 로그에 안 남는 예외를 Sentry로 보낸다.
 * DSN이 없거나 debug 빌드면 [enabled]가 false라 전송하지 않는다.
 * 위젯·브로드캐스트만으로 프로세스가 떠도 Manifest auto-init으로 SDK가 준비된다.
 */
object ErrorReport {
    private val codeRe = Regex("""(코드\s*)\d{4,8}""")

    /** [Prefs.appendLog]에 쌓인 한 묶음. 실패는 error, 그 외(성공·적용 대기)는 info. */
    fun diagnostic(lines: List<String>) {
        if (!enabled() || lines.isEmpty()) return
        val body = redact(lines.joinToString("\n")).take(MAX)
        val error = isError(lines)
        val title = if (error) {
            lines.firstOrNull { it.trim().startsWith("✗") }
                ?: lines.firstOrNull { it.contains("예외") || it.trim().startsWith("시간 초과") }
                ?: lines.first()
        } else {
            lines.first()
        }
        val headline = redact(title.trim()).take(200)
        Sentry.captureMessage(headline, if (error) SentryLevel.ERROR else SentryLevel.INFO) { scope ->
            scope.setExtra("log", body)
            scope.fingerprint = listOf(if (error) "diagnostic-error" else "diagnostic-info", headline.take(120))
        }
    }

    /** 진단 로그로 남지 않는 예외, 또는 Flutter 화면 예외. */
    fun failure(where: String, error: Throwable? = null) {
        if (!enabled()) return
        val place = redact(where).take(200)
        if (error != null) {
            Sentry.captureException(error) { scope ->
                scope.setTag("where", place)
                scope.fingerprint = listOf("exception", error.javaClass.name, place.take(80))
            }
        } else {
            val msg = redact(where).take(MAX)
            Sentry.captureMessage(msg.lineSequence().first().take(200), SentryLevel.ERROR) { scope ->
                scope.setExtra("detail", msg)
                scope.fingerprint = listOf("message", msg.lineSequence().first().take(120))
            }
        }
    }

    private fun enabled(): Boolean = !BuildConfig.DEBUG && BuildConfig.SENTRY_DSN.isNotEmpty() && Sentry.isEnabled()

    /** ✗ 이 있거나, 성공(✓) 없이 예외·시간 초과만 있으면 실패. */
    private fun isError(lines: List<String>): Boolean {
        val trimmed = lines.map { it.trim() }
        if (trimmed.any { it.startsWith("✗") }) return true
        if (trimmed.any { it.startsWith("✓") }) return false
        return trimmed.any { it.contains("예외") || it.startsWith("시간 초과") }
    }

    private fun redact(s: String) = codeRe.replace(s) { "${it.groupValues[1]}******" }

    private const val MAX = 4000
}
