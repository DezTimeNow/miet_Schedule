#!/usr/bin/env bash
# Скриншот приложения на эмуляторе.
#
# SystemUI на этом стенде периодически уходит в ANR (GC по 3 с, зеркало с
# обоями), и его окно с диалогом перекрывает экран. Поэтому:
#   1) сначала поднимаем наш Activity поверх всего,
#   2) снимаем сразу, не дожидаясь ANR-диалога,
#   3) если в кадре ANR — перезапускаем SystemUI и повторяем до 3 раз.
set -uo pipefail

export ANDROID_HOME=/root/android-sdk
export PATH="$ANDROID_HOME/platform-tools:$PATH"
PKG=com.mietschedule.app
OUT="${1:-/tmp/shot.png}"
WAIT="${2:-14}"

launch_and_shoot() {
  adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
  adb shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
  sleep "$WAIT"
  adb exec-out screencap -p > "$OUT" 2>/dev/null
}

bad_frame() {
  tesseract "$OUT" stdout -l eng 2>/dev/null \
    | grep -qiE "isn't responding|Close app|wait"
}

for attempt in 1 2 3; do
  # чистим ANR-диалог, если он висит
  if adb shell dumpsys window 2>/dev/null | grep -q "Application Not Responding"; then
    adb shell am force-stop com.android.systemui >/dev/null 2>&1
    sleep 10
  fi
  launch_and_shoot
  if ! bad_frame; then
    echo "OK: кадр чистый (попытка $attempt)"
    tesseract "$OUT" stdout -l eng 2>/dev/null | head -40
    exit 0
  fi
  echo "попытка $attempt: в кадре ANR-диалог, перезапускаю SystemUI"
done

echo "НЕ УДАЛОСЬ: 3 попытки, в кадре ANR"
exit 1
