#!/usr/bin/env bash
# 仮想スマホで、設定画面の辞書の「＋ 追加」を押した時にキーボードが出るかを確かめる（keyboard-check.yml から呼ぶ）
set -u
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.shunp.vitmobile android.permission.RECORD_AUDIO || true
adb shell pm grant com.shunp.vitmobile android.permission.POST_NOTIFICATIONS || true
adb shell am start -W -n com.shunp.vitmobile/.MainActivity
sleep 6
# 辞書は下の「言葉」タブにある
adb shell uiautomator dump /sdcard/ui.xml > /dev/null
adb pull /sdcard/ui.xml ui0.xml > /dev/null
python3 tools/tap_text.py ui0.xml "言葉" 0 > tab.txt || true
echo "tab at: $(cat tab.txt)"
[ -s tab.txt ] && adb shell input tap $(cat tab.txt)
sleep 2
: > tap.txt
for i in 1 2 3 4 5 6 7 8 9 10; do
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  adb pull /sdcard/ui.xml ui0.xml > /dev/null
  python3 tools/tap_text.py ui0.xml "＋ 追加" 0 > tap.txt || true
  [ -s tap.txt ] && break
  adb shell input swipe 540 1700 540 700 300
  sleep 1
done
grep -o 'text="[^"]*"' ui0.xml | head -60
echo "tap at: $(cat tap.txt)"
[ -s tap.txt ] || { echo "ADD BUTTON NOT FOUND"; exit 1; }
adb shell input tap $(cat tap.txt)
sleep 3
adb exec-out screencap -p > shot-after-add.png
adb shell dumpsys input_method | grep -E "mInputShown|mServedView|mCurFocusedWindowName|isInputViewShown" | tee ime.txt
if grep -q "mInputShown=true" ime.txt; then echo "KEYBOARD SHOWN"; else echo "KEYBOARD NOT SHOWN"; exit 1; fi
