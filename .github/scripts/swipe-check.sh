#!/usr/bin/env bash
set -Eeuo pipefail

pkg=com.shunp.vitmobile
out=swipe-results
mkdir -p "$out"
exec > >(tee "$out/check.log") 2>&1
failed=0
note() { printf '%s\n' "$*" | tee -a "$out/result.txt"; }
fail() { note "FAIL: $*"; failed=1; }
collect() {
  local status=$?
  trap - EXIT
  set +e
  adb logcat -d -v brief > "$out/logcat.txt"
  adb logcat -d -b crash > "$out/crash.txt"
  adb shell dumpsys accessibility > "$out/accessibility.txt"
  adb shell dumpsys window > "$out/window.txt"
  adb exec-out run-as "$pkg" cat files/autosend.log > "$out/autosend.log"
  adb exec-out screencap -p > "$out/final.png"
  if (( status != 0 )); then note "FAIL: script exited $status"; fi
  exit "$status"
}
trap collect EXIT
trap 'note "FAIL: command at line $LINENO"' ERR

wait_log() {
  local pattern=$1
  for ((i=0; i<30; i++)); do
    adb logcat -d -v brief > "$out/latest-logcat.txt"
    if grep -qE "$pattern" "$out/latest-logcat.txt"; then return 0; fi
    sleep 1
  done
  return 1
}
dump_ui() {
  adb shell uiautomator dump /sdcard/vit-swipe-window.xml > /dev/null
  adb pull /sdcard/vit-swipe-window.xml "$out/$1.xml" > /dev/null
}
open_settings() {
  adb shell am force-stop com.android.settings
  adb shell am start -W -a android.settings.SETTINGS > /dev/null
  sleep 2
}

adb wait-for-device
adb install -r app/build/outputs/apk/debug/app-debug.apk > /dev/null
adb shell pm grant "$pkg" android.permission.RECORD_AUDIO
adb shell pm grant "$pkg" android.permission.POST_NOTIFICATIONS
adb shell appops set "$pkg" SYSTEM_ALERT_WINDOW allow
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell wm size 1080x1920
adb shell wm density 420
adb logcat -c
# Unstop the installed package; MainActivity does not start audio without a key.
adb shell am start -W -n "$pkg/.MainActivity" > /dev/null
adb shell settings put secure enabled_accessibility_services "$pkg/$pkg.InputAccessibilityService"
adb shell settings put secure accessibility_enabled 1
if ! wait_log 'VIT_ACC.*service connected'; then
  note 'FAIL: accessibility service did not connect'
  exit 1
fi
# OverlayService is exported=false, so shell cannot directly start it. The debug
# receiver starts it under our UID after the permissions above. No Groq key needed.
adb shell am broadcast -a "$pkg.DEBUG_SIDEBAR_TARGET" \
  -n "$pkg/.DebugSidebarTargetReceiver" --es pkg com.android.settings > /dev/null
if ! wait_log 'VIT_SWIPE.*target=com.android.settings'; then
  note 'FAIL: debug target was not applied'
  exit 1
fi
adb shell dumpsys activity services "$pkg" > "$out/services.txt"
if ! grep -q 'isForeground=true' "$out/services.txt"; then
  note 'FAIL: overlay foreground service did not start'
  exit 1
fi

# Do not relax hidden-API policy or grant platform-only privileges: that would
# mask the actual user-device limitation. Unavailable observation must fail CI.
adb logcat -d -v brief > "$out/observation-logcat.txt"
if grep -q 'VIT_SWIPE.*sidebar observation enabled' "$out/observation-logcat.txt"; then
  note 'PASS: observation enabled'
else
  fail 'observation unavailable (see autosend.log; hidden API/signature permission)'
fi
open_settings
dump_ui initial
adb exec-out screencap -p > "$out/before-swipe.png"
adb logcat -c
adb shell input swipe 390 960 890 960 300
sleep 1
adb logcat -d -v brief > "$out/horizontal-logcat.txt"
if grep -q 'VIT_SWIPE.*detected' "$out/horizontal-logcat.txt"; then
  note 'PASS: center right swipe detected'
else
  fail 'center right swipe not detected'
fi
if grep -qE 'VIT_SWIPE.*sidebar (ACTION_CLICK .*accepted=true|fallback tap dispatched=true)' "$out/horizontal-logcat.txt"; then
  note 'PASS: sidebar click/tap accepted (Settings has no Claude drawer)'
else
  fail 'no accepted sidebar click/tap'
fi
adb exec-out screencap -p > "$out/after-swipe.png"

# Reopen the same top-level list, since the sidebar fallback may have opened a
# Settings search/control. Compare Settings text inside scrollable containers;
# status-bar clocks, focus flags and XML timestamps cannot create a false pass.
open_settings
dump_ui before-scroll
adb logcat -c
adb shell input swipe 540 1580 540 500 400
sleep 1
dump_ui after-scroll
adb exec-out screencap -p > "$out/after-scroll.png"
if python3 - "$out" <<'PY'
import pathlib
import sys
import xml.etree.ElementTree as ET

out = pathlib.Path(sys.argv[1])
def texts(name):
    root = ET.parse(out / (name + '.xml')).getroot()
    result = set()
    for container in root.iter('node'):
        if container.get('scrollable') != 'true':
            continue
        for node in container.iter('node'):
            if node.get('package') == 'com.android.settings' and node.get('text', '').strip():
                result.add(node.get('text').strip())
    (out / (name + '-texts.txt')).write_text('\n'.join(sorted(result)), encoding='utf-8')
    return result

before, after = texts('before-scroll'), texts('after-scroll')
if not before or not after or before == after:
    sys.exit('Settings scroll text did not change or scrollable list was unavailable')
print(f'Settings list changed: {len(before)} -> {len(after)} labels')
PY
then
  note 'PASS: vertical scroll reached Settings'
else
  fail 'vertical scroll did not change Settings list text'
fi
adb logcat -d -v brief > "$out/vertical-logcat.txt"
if grep -q 'VIT_SWIPE.*detected' "$out/vertical-logcat.txt"; then
  fail 'vertical gesture incorrectly detected as right swipe'
else
  note 'PASS: vertical gesture did not trigger sidebar'
fi
adb logcat -d -b crash > "$out/crash.txt"
if grep -q 'FATAL EXCEPTION' "$out/crash.txt"; then fail 'Android process crashed'; fi
if (( failed )); then note 'SWIPE CHECK FAILED'; exit 1; fi
note 'SWIPE CHECK PASSED'
