#!/usr/bin/env bash
# Сборка APK + запуск эмулятора так, чтобы памяти хватало.
#
# На этой машине 8 ГБ RAM и 4 ГБ swap. Эмулятор с -gpu host разъедается
# до 3+ ГБ, а Gradle-демон тянет ещё 0.5-1.5 ГБ. Вместе они добивают swap,
# после чего SystemUI (а потом и наше приложение) уходят в ANR, а
# `screencap` отдаёт чёрный кадр. Поэтому:
#   1) демонов глушим ДО запуска эмулятора,
#   2) эмулятору даём жёсткий лимит памяти и swap настройки,
#   3) анимации выключаем (меньше нагрузки на рендер).
set -euo pipefail

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/root/android-sdk
export ANDROID_SDK_ROOT=/root/android-sdk
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
PROJECT=/root/projects/miet-schedule
AVD=miet_test

free_mb() { free -m | awk '/Mem:/ {print $7}'; }

echo "==> Свободно памяти: $(free_mb) МБ"

# 1. Убиваем Gradle-демоны — они не нужны между сборками
pkill -9 -f GradleDaemon 2>/dev/null || true
pkill -9 -f KotlinCompileDaemon 2>/dev/null || true
sleep 2
echo "==> Gradle-демоны остановлены, свободно: $(free_mb) МБ"

# 2. Сборка (без демона — ./gradlew --no-daemon не держит JVM в памяти)
if [ "${1:-}" != "--no-build" ]; then
  echo "==> Сборка..."
  (cd "$PROJECT" && ./gradlew --no-daemon assembleDebug -q 2>&1 | grep -E "^e:|error:|FAILURE" || true)
  # Демон всё равно может подняться — гасим сразу после сборки
  pkill -9 -f GradleDaemon 2>/dev/null || true
  sleep 2
  echo "==> После сборки свободно: $(free_mb) МБ"
fi

APK="$PROJECT/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { echo "!! APK не найден: $APK"; exit 1; }
ls -lh "$APK"

# 3. Эмулятор
if ! adb devices | grep -q emulator; then
  echo "==> Запуск эмулятора (Xvfb + gpu host)..."
  pkill -9 Xvfb 2>/dev/null || true
  sleep 1
  Xvfb :99 -screen 0 1080x2400x24 -nolisten tcp >/tmp/xvfb.log 2>&1 &
  sleep 3
  export DISPLAY=:99
  # -memory 1536 вместо 2048: qemu всё равно уезжает за лимит, а на system's sake
  # лучше меньший лимит и подсвап, чем OOM всего стенда.
  nohup "$ANDROID_HOME/emulator/emulator" -avd "$AVD" \
      -no-audio -no-boot-anim -gpu host -no-snapshot -no-snapshot-save \
      -memory 1536 -cores 2 \
      >/tmp/emulator.log 2>&1 &
  for i in $(seq 1 40); do
    adb start-server >/dev/null 2>&1 || true
    sleep 8
    [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && { echo ">>> BOOT за ~$((i*8))с"; break; }
  done
  adb shell settings put global window_animation_scale 0   >/dev/null 2>&1 || true
  adb shell settings put global transition_animation_scale 0 >/dev/null 2>&1 || true
  adb shell settings put global animator_duration_scale 0   >/dev/null 2>&1 || true
  # Диалоги ANR рисует system_server, поэтому переживают force-stop SystemUI
  # и намертво перекрывают экран. Эта настройка запрещает их показывать —
  # единственная, которая реально снимает проблему «чёрного кадра».
  adb shell settings put global hide_error_dialogs 1        >/dev/null 2>&1 || true
  adb shell settings put global anr_show_background 0       >/dev/null 2>&1 || true
fi

echo "==> Установка APK..."
adb install -r "$APK" 2>&1 | tail -1
echo "==> Память после установки: $(free_mb) МБ"
echo "==> Готово. Скриншот: adb exec-out screencap -p > /tmp/shot.png"
