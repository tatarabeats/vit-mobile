#!/usr/bin/env bash
# スマホに VIT を入れ直す。録音中・書き起こし中は待つ（入れ直すと VIT が落ち、録音が途中で切れるため。2026-11-05）
# 使い方: bash tools/safe_install.sh <apk のパス>
set -u
APK="$1"
A=/c/Users/shunp/Tools/platform-tools/adb.exe
# スマホの住所はテザリングのたびに変わる（2026-11-11 は .179）。つながっている端末から選ぶ
DEV=$("$A" devices | awk '$2=="device"{print $1; exit}')
if [ -z "$DEV" ]; then echo "スマホが adb につながっていない"; exit 1; fi
S="-s $DEV"
quiet=0
for i in $(seq 1 360); do
  # pending_rec.json は録音中〜書き起こし中だけある
  if "$A" $S shell run-as com.shunp.vitmobile ls files/pending_rec.json > /dev/null 2>&1; then
    quiet=0
  else
    quiet=$((quiet + 1))
    # 録音の合間（話し終えて次に話し始めるまで）を避けるため、20秒続けて録音が無い時に入れる
    if [ $quiet -ge 4 ]; then
      "$A" $S install -r "$APK"
      "$A" $S shell dumpsys package com.shunp.vitmobile | grep -m1 versionName
      exit 0
    fi
  fi
  sleep 5
done
echo "30分待っても録音が終わらないので入れていない"
exit 1
