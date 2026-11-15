#!/usr/bin/env bash
# PostToolUse(Write|Edit): 방금 수정한 파일을 언어별 포맷터로 정리한다.
#   .dart  → dart format
#   .swift → swift-format (Xcode 포함, xcrun 으로 실행)
f=$(jq -r '.tool_response.filePath // .tool_input.file_path // empty')
[ -f "$f" ] || exit 0
case "$f" in
  *.dart) dart format "$f" >/dev/null 2>&1 ;;
  *.swift) xcrun swift-format format --in-place "$f" >/dev/null 2>&1 ;;
esac
exit 0
