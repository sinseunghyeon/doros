import 'package:flutter_test/flutter_test.dart';

import 'package:ship_time/native.dart';
import 'package:ship_time/ui.dart';

void main() {
  group('ShipState', () {
    test('빈 맵이면 기본값', () {
      final s = ShipState({});
      expect(s.offsetMinutes, 0);
      expect(s.sync, isTrue);
      expect(s.systemSupported, isTrue);
      expect(s.ddayDate, isNull);
      expect(s.ddayLabel, isNull);
      expect(s.widgetOpacity, 90);
      expect(s.autoTimeZone, -1);
      expect(s.statusState, ShipState.stateNone);
      expect(s.adbConnected, isFalse);
      expect(s.lastOk, isNull);
    });

    test('네이티브 값 파싱', () {
      final s = ShipState({
        'offsetMinutes': 540,
        'label': 'UTC+9',
        'zoneId': 'Etc/GMT-9',
        'sync': false,
        'statusState': ShipState.stateOk,
        'systemZone': 'Etc/GMT-9',
        'systemOffsetMinutes': 540,
        'adbConnected': true,
        'lastOk': true,
        'lastMessage': 'UTC+9 적용 (cmd)',
      });
      expect(s.offsetMinutes, 540);
      expect(s.zoneId, 'Etc/GMT-9');
      expect(s.sync, isFalse);
      expect(s.adbConnected, isTrue);
      expect(s.lastMessage, 'UTC+9 적용 (cmd)');
    });

    test('systemMatches 는 분 단위 시스템 오프셋과 비교', () {
      expect(ShipState({'offsetMinutes': 540, 'systemOffsetMinutes': 540}).systemMatches, isTrue);
      expect(ShipState({'offsetMinutes': -180, 'systemOffsetMinutes': -180}).systemMatches, isTrue);
      expect(ShipState({'offsetMinutes': 540, 'systemOffsetMinutes': 570}).systemMatches, isFalse);
      expect(ShipState({'offsetMinutes': 570, 'systemOffsetMinutes': 570}).systemMatches, isTrue);
    });

    test('30분 오프셋 · 디데이 · 위젯 불투명도 파싱', () {
      final s = ShipState({
        'offsetMinutes': 90,
        'label': 'UTC+1:30',
        'zoneId': null,
        'systemSupported': false,
        'ddayTitle': '입항',
        'ddayDate': '2026-12-01',
        'ddayDays': 16,
        'ddayLabel': 'D-16',
        'widgetOpacity': 40,
      });
      expect(s.offsetMinutes, 90);
      expect(s.zoneId, '');
      expect(s.systemSupported, isFalse);
      expect(s.ddayTitle, '입항');
      expect(s.ddayDate, '2026-12-01');
      expect(s.ddayDays, 16);
      expect(s.ddayLabel, 'D-16');
      expect(s.widgetOpacity, 40);
    });
  });

  group('ui 포맷', () {
    test('offLabel', () {
      expect(offLabel(540), 'UTC+9');
      expect(offLabel(0), 'UTC+0');
      expect(offLabel(-720), 'UTC-12');
      expect(offLabel(570), 'UTC+9:30');
      expect(offLabel(-210), 'UTC-3:30');
    });

    test('fmtTime', () {
      final t = DateTime(2026, 1, 2, 3, 4, 5);
      expect(fmtTime(t), '03:04:05');
      expect(fmtTime(t, sec: false), '03:04');
    });
  });
}
