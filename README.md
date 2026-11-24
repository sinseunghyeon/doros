# Ship Time — 선박 시간 위젯

> 기기에 설치되는 앱 이름은 **Helm**입니다. (패키지 `com.edencrew.shiptime`)

홈 화면 위젯의 **[−1h] [−30m] [+30m] [+1h]** 버튼 한 번으로 선박 시간을 조정합니다.
**무선 디버깅(ADB)**이 연결되어 있으면 **기기 시스템 시간대도 같이** 바꿉니다.
루트·Device Owner 없이, 구글 계정이 있는 기기에서도 동작합니다.

- 기기 시계(UTC)는 건드리지 않고 **시간대만** `Etc/GMT±N`으로 바꿉니다. 그래서 UTC 기준이 틀어지지 않습니다.
- 처음 한 번만 **알림에서 6자리 페어링 코드**를 입력하면 됩니다. 이후에는 재부팅해도 앱이 무선 디버깅을 **자동으로 켜고 다시 연결**합니다.
- 권한이 없거나 Wi-Fi가 없어도 위젯은 "표시 전용"으로 동작합니다. 위젯의 TextClock이 선박 시간을 표시합니다.
- **30분 단위**도 조정할 수 있습니다. 다만 시스템 시간대는 tzdata에 있는 ID만 받으므로, DST 없는 실제 시간대가 있는 값만 기기 시간대까지 바꿉니다.

  | 오프셋 | 적용 시간대 |
  |---|---|
  | UTC−9:30 | `Pacific/Marquesas` |
  | UTC+3:30 | `Asia/Tehran` |
  | UTC+4:30 | `Asia/Kabul` |
  | UTC+5:30 | `Asia/Kolkata` |
  | UTC+6:30 | `Asia/Yangon` |
  | UTC+9:30 | `Australia/Darwin` |

  나머지 30분 값(예: UTC+1:30)은 위젯과 앱에만 표시하고 기기 시계는 바꾸지 않습니다 (상태: "표시 전용").
- **디데이**: 시간 탭에서 제목(예: 입항)과 날짜를 하나 등록하면 앱과 위젯 머리글에 `D-12 입항`처럼 표시됩니다. 날짜는 선박 시간 기준으로 세고, 위젯은 선박 시간 자정에 갱신됩니다 (비정확 알람이라 몇 분 늦을 수 있음).
- **위젯 투명도**: 설정 탭의 "위젯 배경 불투명도"(0~100%)로 위젯 배경만 투명하게 합니다. 글자와 버튼은 그대로입니다.

## 구조

```
lib/
  main.dart             진입점 + 크래시 로깅
  app.dart              MaterialApp / 테마
  root_shell.dart       하단 탭 셸 (시간 · 설정 · 기록)
  ship_controller.dart  앱 전역 상태 (폴링, busy, 토스트)
  native.dart           MethodChannel 래퍼 (shiptime/native) + ShipState
  pages/                home_page(시계·±1h/±30m·디데이) / settings_page(ADB 연결·페어링·위젯 투명도) / log_page(진단 로그)
  cat.dart, ui.dart, app_colors.dart   마스코트·포맷 유틸·색상
android/app/src/main/kotlin/com/edencrew/shiptime/
  Core.kt                Zones(분 단위 오프셋↔zoneId), Dday, Prefs, Hidden(숨김 API), SysTz
  ErrorReport.kt         진단 로그·예외를 Sentry로 전송 (DSN 없으면 꺼짐)
  Priv.kt                Priv 인터페이스 / RootPriv (선택 폴백)
  AdbPriv.kt             ★ 무선 디버깅(ADB)으로 셸 명령 실행
  ShipAdbManager.kt      libadb-android 연결·키 관리
  ShipTimeController.kt  ★ 시간대 변경 로직 (전략 순차 시도 + 검증 + 로그)
  ShipTimeWidget.kt      AppWidgetProvider + RemoteViews 갱신(디데이·배경 투명도·자정 갱신 알람) + DeviceAdminReceiver
  PairingService.kt      알림으로 페어링 (포트 mDNS 탐색 + 알림 입력칸으로 코드 수신)
  WirelessAdb.kt         무선 디버깅 자동 켜기 + Wi-Fi 연결 감시(WifiAvailableReceiver) / BootReceiver / AdbKeepAliveJob
  MainActivity.kt        MethodChannel 핸들러
android/app/src/debug/.../DebugCommandReceiver.kt   debug 빌드 전용 테스트 훅 (아래 "에뮬레이터 테스트")
android/app/src/main/res/layout/widget_ship_time(_large).xml   위젯 레이아웃 (기본 / 큰 고양이)
test/widget_test.dart   Dart 단위 테스트 (ShipState 파싱, 포맷)
tool/emulator_test.sh   에뮬레이터 시나리오 테스트
```

위젯 버튼은 앱이 꺼져 있어도 동작해야 하므로 실제 로직은 Kotlin(`ShipTimeController`)에 있습니다.
Flutter 앱도 같은 함수를 MethodChannel로 호출합니다.

## 시스템 시간대 변경 전략 (버전 호환)

순서대로 시도하고, 각 단계가 끝날 때마다 `persist.sys.timezone`을 읽어 **실제로 바뀌었는지 검증**합니다.

| # | 방법 | 주체 | 비고 |
|---|------|------|------|
| 0 | `DevicePolicyManager.setTimeZone` | Device Owner | 공식 API (Android 9+), 선택 사항 |
| 1 | `cmd time_zone_detector suggest_manual_time_zone --zone_id` | **ADB** / Root | Android 12+ |
| 2 | `service call alarm <code> s16 <zone>` | **ADB** / Root | 트랜잭션 코드는 런타임 조회 |
| 3 | `setprop persist.sys.timezone` + 브로드캐스트 | Root | 최후 수단 |

적용하기 전에 자동 시간대를 끕니다 (`cmd time_zone_detector set_auto_detection_enabled false`, `settings put global auto_time_zone 0`).
자동 시간대가 켜져 있으면 네트워크/GPS가 다시 덮어쓰기 때문입니다.

어느 단계에서 실패했는지는 앱의 **진단 로그**에 남습니다.
release 빌드에 Sentry DSN이 있으면 그 로그는 개발자에게도 전달됩니다. 아래 "오류 보고"를 보세요.

## 친구 폰 설정 (무선 디버깅)

1. 설정 > 휴대전화 정보 > 소프트웨어 정보 > **빌드번호 7번 탭** → 개발자 옵션 활성화
2. **Wi-Fi에 연결한 상태에서** 개발자 옵션 > **무선 디버깅** 켜기 → **"이 네트워크에서 항상 허용"** 체크
3. Ship Time 앱 > 설정 탭 > **페어링** → 앱이 무선 디버깅 화면을 엽니다
4. **"페어링 코드로 기기 페어링"** 을 누르고, 화면에 뜬 6자리 코드를 **알림을 내려 "코드 입력"** 에 넣습니다
   (설정 화면을 벗어나지 않아도 됩니다. 포트는 앱이 자동으로 찾습니다)
5. 홈 화면 길게 누르기 > 위젯 > Ship Time 추가

알림 권한을 거부했다면 "코드 직접 입력"으로 포트와 코드를 앱에 입력할 수 있습니다.

### 재부팅 후 자동 복구

첫 연결에 성공하면 앱이 그 ADB 권한으로 자기 자신에게 `WRITE_SECURE_SETTINGS`를 부여합니다(PC 불필요).
그 뒤로는 다음과 같이 동작합니다.

- 부팅 후, 그리고 **Wi-Fi가 다시 연결될 때마다** 앱이 무선 디버깅을 직접 켜고 다시 연결합니다 (`WifiAvailableReceiver`). 인터넷이 안 되는 선내 Wi-Fi에서도 동작하고, Wi-Fi가 있는 동안에는 30분마다 연결을 점검합니다.
  (예전에는 JobScheduler의 "인터넷 확인 + 비종량제" 조건을 써서 인터넷이 없는 Wi-Fi에서는 재연결 작업이 실행되지 않았습니다.
  지금은 Wi-Fi 연결 이벤트를 받는 즉시 처리하므로, 앱을 오래 열지 않아 대기 버킷이 낮아져도 밀리지 않습니다.)
- 위젯을 눌렀을 때 무선 디버깅이 꺼져 있으면 앱이 켜고 바로 적용합니다.
- 연결이 없을 때 바꾼 시간은 **"적용 대기"** 로 저장했다가, 다시 연결되면 자동으로 적용합니다.
- 12시간마다 한 번 접속해 둡니다 (`AdbKeepAliveJob`). 시스템은 7일 동안 접속이 없는 키를 지우기 때문입니다 (`adb_allowed_connection_time` 기본값).
- 설정 탭의 **"무선 디버깅 자동 켜기"** 로 끌 수 있습니다.

조건 (AOSP `AdbDebuggingManager` 동작):
- 무선 디버깅은 Wi-Fi에서만 켜집니다. **Wi-Fi가 꺼지거나 끊기거나 다른 AP로 옮기면 시스템이 강제로 끕니다.**
- "항상 허용"은 **AP(BSSID)마다** 저장됩니다. 선내처럼 AP가 여러 대면 처음 붙는 AP에서 뜨는 확인 창에서 "항상 허용"을 한 번씩 눌러야 합니다 (페어링은 다시 할 필요 없음).

> 자동 켜기를 쓰면 신뢰한 Wi-Fi에서 무선 디버깅이 켜진 상태로 유지됩니다. 페어링된 키가 있어야 접속할 수 있지만, 원하지 않으면 스위치로 끄세요.

### Wi-Fi 없이도 동작 (실험)

선내 Wi-Fi가 끊기거나 데이터 로밍·eSIM으로 바뀌면 무선 디버깅이 꺼져서 시간대를 바꿀 수 없었습니다. 이 옵션(기본 켜짐)은 다음과 같이 동작합니다.

1. ADB에 연결되면 **USB 디버깅을 켜고**(`adb_enabled=1`) adbd를 **TCP 5555**로도 띄웁니다 (`tcpip:5555`).
   시스템(`AdbService`)은 USB·무선 디버깅이 **둘 다** 꺼질 때만 adbd를 멈추므로, Wi-Fi가 끊겨도 adbd는 살아 있습니다.
2. 이후에는 `127.0.0.1:5555`(루프백)로 먼저 접속합니다. Wi-Fi가 없어도 됩니다. 인증은 페어링한 키를 그대로 씁니다.

제약과 주의:
- **5555 대기는 재부팅하면 풀립니다.** 재부팅 후에는 Wi-Fi에서 한 번 다시 연결돼야 합니다 (자동). USB 디버깅은 스위치를 끌 때까지 켜져 있습니다.
- **5555 포트는 모든 네트워크에 열립니다** (`service.adb.listen_addrs`는 shell이 설정할 수 없어 루프백 전용으로 만들 수 없음).
  처음 보는 기기가 접속하면 허용 창이 뜨므로 모르는 요청은 거부하세요. **이미 "항상 허용"한 PC는 창 없이 접속할 수 있습니다.**
- Wi-Fi 감시는 일회성 콜백이라 처리 후 30분 뒤에 다시 등록합니다. 그 사이의 AP 이동·재접속은 다음 점검이나 위젯 버튼에서 처리됩니다 (절전 중에는 더 늦어질 수 있음).
- USB 디버깅이 켜져 있으면 일부 금융 앱이 실행을 거부할 수 있습니다. 스위치를 끄면 5555 대기를 해제하고, 앱이 켰던 USB 디버깅도 끕니다.

### (선택) Device Owner — 공장초기화된 기기 / 계정이 없는 기기 전용

```bash
adb shell dpm set-device-owner com.edencrew.shiptime/.ShipAdminReceiver
```

구글 계정이 있으면 등록할 수 없습니다. 이 앱의 기본 경로는 **무선 디버깅**입니다.

## 빌드

```bash
cd ship_time
flutter create --platforms=android --org com.edencrew .   # 빠진 파일만 생성
flutter pub get
flutter build apk --release
# → build/app/outputs/flutter-apk/app-release.apk
```

- release 빌드는 debug 키로 서명돼 있어서 바로 설치할 수 있습니다.

### iOS (개발 시작 단계)

```bash
flutter build ios --debug --no-codesign      # 기기용 빌드 확인
flutter run -d "iPhone 15 Pro"               # 시뮬레이터 실행
```

- 번들 ID `com.edencrew.shiptime`, 앱 이름 Helm, 최소 iOS 15.0.
- `flutter build ios --simulator`는 Flutter 3.38 + Xcode 27 조합에서 실패합니다. 시뮬레이터는 `flutter run`을 쓰세요.
- iOS 네이티브 구현이 아직 없어 앱이 로딩 화면에서 멈춥니다.
- `libadb-android`는 JitPack에서 받습니다 (`android/build.gradle.kts`에 jitpack 저장소).

## 오류 보고 (Sentry)

진단 로그에 줄이 쌓일 때마다 **release** 빌드가 그 묶음을 Sentry로 보냅니다. 위젯만 눌러 앱 화면이 꺼져 있어도 Kotlin에서 바로 보냅니다. `✗` 로 시작하는 실패, 성공(`✓`) 없이 남은 예외·시간 초과는 error이고, 성공·적용 대기·연결 기록은 info입니다. Wi-Fi 감시·재연결·페어링에서 로그로 남지 않는 예외와, 앱이 열려 있을 때의 Flutter 예외도 error로 보냅니다. debug 빌드와 에뮬레이터 테스트는 보내지 않습니다.

보내는 내용: 진단 로그 묶음, 예외, 기기 모델, Android 버전. `코드` 뒤의 숫자(페어링 코드)는 지우고, 화면 캡처는 보내지 않습니다. DSN이 켜져 있으면 기록 탭과 설정 문구에 "실패 기록은 개발자에게 전달됩니다"가 나옵니다. Sentry 알림을 error만 켜면 성공 기록은 쌓이되 알림은 실패일 때만 옵니다.

켜려면 [Sentry](https://sentry.io)에서 Android 프로젝트를 만들고, DSN을 `android/local.properties`에 넣은 뒤 **release**로 다시 빌드합니다. 이 파일은 git에 올라가지 않습니다.

```
sentry.dsn=https://<key>@<org>.ingest.sentry.io/<project>
```

DSN이 없으면 앱 동작은 그대로이고 전송만 하지 않습니다.

## 에뮬레이터 테스트

```bash
tool/emulator_test.sh              # 기본: API 33, 35
KEEP_EMULATOR=1 tool/emulator_test.sh 35
```

`google_apis` 이미지 에뮬레이터에서는 스크립트가 `adb root`를 실행합니다. 그러면 adbd가 `:5555`에서 인증 없이(`ro.adb.secure=0`) 대기하므로,
앱의 ADB 경로가 페어링 없이 자동으로 연결되어 **실제 ADB 경로**로 검증합니다.
Play Store 이미지나 실기기에서는 앱에서 직접 ADB를 연결한 뒤 Enter를 누르세요 (`SKIP_CONNECT=1`이면 이 단계를 건너뜁니다).
API 30 에뮬레이터는 adbd가 TCP 포트를 열지 않아 자동 연결이 되지 않습니다. 그래서 기본 대상에서 뺐습니다 (Android 11은 실기기에서 확인하세요).

스크립트는 시간대 변경 시나리오만 검증합니다. 알림 페어링과 자동 복구는 아래 debug 명령으로 수동 확인합니다
(API 35 에뮬레이터에서 확인 완료. 실제 재부팅·제조사별 알림 UI는 실기기에서 확인 필요).

```bash
# debug 훅: 위젯 버튼과 같은 경로로 실행하고 결과를 logcat 한 줄(RESULT ...)로 남김
CMD() { adb shell am broadcast -f 268435488 -n com.edencrew.shiptime/.DebugCommandReceiver \
          -a com.edencrew.shiptime.debug.CMD --es id 1 "$@"; }
CMD --es cmd plus                         # +1h  (minus, plus30, minus30, reapply, state, sync_on, sync_off)
CMD --es cmd set --ei offset 9            # UTC+9 지정
CMD --es cmd set --ei offset_min 330      # UTC+5:30 지정 (분 단위)
CMD --es cmd pair_start                   # 알림 페어링 시작 (앱이 화면에 떠 있을 때)
CMD --es cmd pair_code --es code 123456   # 알림 입력칸과 같은 경로로 코드 제출
CMD --es cmd boot                         # 부팅 시와 같이 Wi-Fi 감시 등록 (Wi-Fi 가 있으면 바로 재연결)
CMD --es cmd reconnect                    # Wi-Fi 연결 시와 같은 재연결 + 적용 대기 처리를 바로 실행
CMD --es cmd tcp_on                       # Wi-Fi 없이도 동작 켜기 (USB 디버깅 + tcpip:5555)  (tcp_off: 끄기)
adb logcat -s ShipTime
```
