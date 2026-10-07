#!/usr/bin/env bash
# 下のタブを順に押して、各画面の写真を撮る（launch-check.yml から呼ぶ）
set -u
adb exec-out screencap -p > shot-0-start.png
for label in アプリ 言葉 詳細 ホーム; do
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
  adb pull /sdcard/ui.xml ui.xml >/dev/null 2>&1 || true
  b=$(python3 - "$label" <<'PY'
import re, sys
s = open('ui.xml', encoding='utf-8').read()
m = re.search(r'text="' + re.escape(sys.argv[1]) + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s)
print('' if not m else '%d %d' % ((int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2))
PY
)
  echo "tab $label at $b"
  if [ -n "$b" ]; then adb shell input tap $b; sleep 2; adb exec-out screencap -p > "shot-tab-$label.png"; fi
done
