#!/usr/bin/env bash
# Stop: 작업을 끝내기 전에 git 기준으로 바뀐 파일을 검사한다.
#   - .dart 가 바뀌었으면 그 파일들만 dart analyze
#   - .kt 가 바뀌었으면 Kotlin 컴파일(compileDebugKotlin, 변경 없을 때 약 13초)
#   - .swift 가 바뀌었으면 문법 검사(swiftc -parse, 즉시) → 통과하면 iOS 빌드로 타입 오류까지 확인
#     (flutter build ios --debug --no-codesign, 수십 초~수 분. ios/ 폴더가 있을 때만)
# 문제가 있으면 끝내지 못하게 막고(decision: block) 결과를 Claude 에게 돌려준다.
input=$(cat)
# 이미 이 훅 때문에 다시 이어진 턴이면 통과 (무한 반복 방지)
[ "$(printf '%s' "$input" | jq -r '.stop_hook_active // false')" = "true" ] && exit 0

cd "${CLAUDE_PROJECT_DIR:-$(pwd)}" || exit 0
changed=$( { git diff --name-only HEAD; git ls-files --others --exclude-standard; } 2>/dev/null | sort -u \
  | while read -r f; do [ -f "$f" ] && printf '%s\n' "$f"; done )
dart_files=$(printf '%s\n' "$changed" | grep -E '\.dart$')
kt_changed=$(printf '%s\n' "$changed" | grep -cE '\.kt$')
swift_files=$(printf '%s\n' "$changed" | grep -E '\.swift$')

report=""
if [ -n "$dart_files" ]; then
  args=()
  while IFS= read -r f; do args+=("$f"); done <<<"$dart_files"
  out=$(dart analyze "${args[@]}" 2>&1) || report+="[dart analyze]
$(printf '%s\n' "$out" | tail -30)
"
fi
if [ "$kt_changed" -gt 0 ]; then
  [ -z "${JAVA_HOME:-}" ] && export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  out=$(cd android && ./gradlew -q :app:compileDebugKotlin 2>&1) || report+="[Kotlin 컴파일]
$(printf '%s\n' "$out" | grep -E '^e: |error' | head -20)
"
fi
if [ -n "$swift_files" ]; then
  parse_err=""
  while IFS= read -r f; do
    out=$(xcrun swiftc -parse "$f" 2>&1) || parse_err+="$out
"
  done <<<"$swift_files"
  if [ -n "$parse_err" ]; then
    report+="[Swift 문법]
$(printf '%s\n' "$parse_err" | grep -E 'error:' | head -20)
"
  elif [ -d ios ]; then
    # 문법이 맞으면 빌드로 타입 오류까지 확인 (Flutter 프레임워크를 import 하므로 단독 타입 검사는 불가)
    # Flutter 는 "Swift Compiler Error (Xcode): 메시지" 다음 줄에 "경로:줄:열" 을 찍는다.
    # 그 형식을 못 찾으면(서명·배포 대상 같은 다른 실패) 로그 끝부분을 그대로 넘긴다.
    out=$(flutter build ios --debug --no-codesign 2>&1) || {
      errs=$(printf '%s\n' "$out" | grep -A1 -E 'Error \(Xcode\):|\.swift:[0-9]+:[0-9]+: error:' | grep -v '^--$' | head -30)
      [ -z "$errs" ] && errs=$(printf '%s\n' "$out" | tail -15)
      report+="[iOS 빌드]
$errs
"
    }
  fi
fi
[ -z "$report" ] && exit 0

jq -n --arg r "바뀐 파일에서 오류/경고가 있습니다. 고친 뒤 끝내세요:
$report" '{decision: "block", reason: $r}'
exit 0
