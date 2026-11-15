# ship_time (앱 이름: Helm)
선박 시간 위젯 Android 앱. 위젯 ±1h로 시스템 시간대를 `Etc/GMT±N`으로 바꾼다 (무선 디버깅 ADB 경유).
구조·동작 설명은 README.md가 기준이다.

## 구조 원칙
- 핵심 로직은 Kotlin(`android/app/src/main/kotlin/com/edencrew/shiptime/`)에 있다. 위젯은 앱이 꺼져 있어도 동작해야 하기 때문.
- Flutter(`lib/`)는 UI만 담당하고 MethodChannel `shiptime/native`로 Kotlin을 호출한다. 로직을 Dart로 옮기지 않는다.

## 검증 명령
- Dart: `flutter analyze`, `flutter test`
- Kotlin 포함 빌드: `flutter build apk --debug` (release는 R8 축소가 있으니 `--release`도 확인)
- 시간대 변경 시나리오: `SKIP_CONNECT=1 tool/emulator_test.sh` (google_apis 에뮬레이터, API 33·35)
- iOS(Swift): `flutter build ios --debug --no-codesign`, 포맷은 `xcrun swift-format` (Xcode 포함, swiftlint 없음)
- `android/gradlew`를 직접 쓸 때는 `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`

## 알려진 함정
- libadb `getHostIpAddress()`는 에뮬레이터에서 호스트 PC(10.0.2.2)를 준다 → 같은 기기 adbd는 `127.0.0.1` 우선.
- libadb 스트림은 정상 종료 때도 `IOException("Stream closed.")`를 던질 수 있다 → EOF로 취급.
- `logcat -s TAG:I TAG:E`처럼 같은 태그를 두 번 쓰면 마지막 레벨만 적용된다.
- Android 14+는 캐시된 앱으로 가는 브로드캐스트를 지연시킨다 → debug 훅은 `-f 268435488`(FOREGROUND) 사용.
- API 30 에뮬레이터는 adbd가 TCP를 열지 않아 앱 ADB 연결을 자동화할 수 없다.
- iOS를 추가할 때(`flutter create --platforms=ios .`) Flutter 템플릿의 최소 iOS 13.0은 Xcode 27에서 빌드되지 않는다
  → `project.pbxproj`의 `IPHONEOS_DEPLOYMENT_TARGET`과 `ios/Flutter/AppFrameworkInfo.plist`를 15.0 이상으로 올린다.

## 규칙
- 커밋: 한국어 제목 한 줄 + 빈 줄 + 변경 요약 bullet. 브랜치는 `main`.
- push: 원격은 SSH 별칭 `github-sinseunghyeon`(sinseunghyeon 계정). 이 Mac의 기본 SSH 키는 다른 계정이다.
- 사용자에게 보이는 문구를 바꾸면 README.md와 앱 안내(`lib/pages/settings_page.dart`)를 함께 맞춘다.
