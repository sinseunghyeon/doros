import 'dart:async';

import 'package:flutter/material.dart';

import 'app.dart';
import 'native.dart';

void main() {
  // 크래시 원인을 정확히 잡기 위한 상세 로깅.
  // 기기에서: adb logcat -s flutter 로 "SHIPTIME-ERR" 를 찾으세요.
  // release + Sentry DSN 이 있으면 같은 내용을 네이티브가 Sentry 로 보낸다.
  FlutterError.onError = (FlutterErrorDetails details) {
    FlutterError.presentError(details);
    debugPrint('════════ SHIPTIME-ERR ════════');
    debugPrint('예외: ${details.exceptionAsString()}');
    if (details.library != null) debugPrint('라이브러리: ${details.library}');
    if (details.context != null) debugPrint('상황: ${details.context}');
    final info = details.informationCollector?.call();
    if (info != null) {
      for (final node in info) {
        debugPrint('  · $node');
      }
    }
    debugPrint('── 스택 ──');
    debugPrintStack(stackTrace: details.stack, maxFrames: 40);
    debugPrint('══════════════════════════════');
    _report(details.exceptionAsString(), details.stack);
  };

  runZonedGuarded(() => runApp(const ShipTimeApp()), (e, st) {
    debugPrint('════════ SHIPTIME-ZONE-ERR ════════');
    debugPrint('$e');
    debugPrintStack(stackTrace: st, maxFrames: 40);
    _report('$e', st);
  });
}

void _report(String message, StackTrace? stack) {
  unawaited(Native.reportError(message, stack?.toString()).then<void>(
    (_) {},
    onError: (Object error, StackTrace trace) {},
  ));
}
