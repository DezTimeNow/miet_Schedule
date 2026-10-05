# Сборка и выпуск

Документ для того, кто продолжает поддерживать проект. Обычному
пользователю приложения ничего отсюда не нужно.

## Требования

- JDK 17
- Android SDK: platform 35, build-tools 34.0.0
- `ANDROID_HOME` указывает на каталог SDK

Проверено на Gradle 8.9, AGP 8.7.3, Kotlin 2.0.21.

## Подготовка окружения

```bash
git clone https://github.com/DezTimeNow/miet_Schedule.git
cd miet_Schedule
echo "sdk.dir=$ANDROID_HOME" > local.properties
export PATH=$PATH:$ANDROID_HOME/build-tools/34.0.0
```

`local.properties` в репозиторий не попадает: в нём путь к SDK конкретной
машины.

## Обычная сборка

```bash
./gradlew assembleDebug
```

Артефакт: `app/build/outputs/apk/debug/app-debug.apk`

Тесты:

```bash
./gradlew testDebugUnitTest
```

Отчёт: `app/build/reports/tests/testDebugUnitTest/index.html`

## Подпись — обязательный шаг

В `app/build.gradle.kts` блока `signingConfig` **нет намеренно**. Приложение
подписывается вручную после сборки: это единственный способ удержать подпись
постоянной независимо от настроек Gradle. Без этого шага релизный APK не
установится.

```bash
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release-unsigned.apk

apksigner sign \
  --ks ~/.android/debug.keystore \
  --ks-pass pass:android \
  --ks-key-alias androiddebugkey \
  --key-pass pass:android \
  --out app/build/outputs/apk/release/app-release.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk
```

Проверить, что подпись та же самая, что у прошлых релизов:

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

Ожидаемый отпечаток SHA-256 сертификата:

```
73:F3:79:96:38:C1:72:9A:F3:64:89:C5:27:CD:3A:3B:C9:67:97:77:06:C8:13:6B:DB:A1:82:51:DE:0D:EE:9A
```

Если отпечаток другой — обновление не встанет поверх ранее установленного.
См. следующий раздел.

## Ключ подписи

Ключ лежит **вне репозитория**: `~/.android/debug.keystore`. Это стандартный
отладочный ключ Android: алиас `androiddebugkey`, пароли хранилища и ключа —
`android`.

**Потеря ключа означает невозможность выпустить обновление.** Android
сверяет сертификат при установке, поэтому APK с другим ключом не встанет
поверх уже установленного: система сочтёт его чужим приложением и потребует
сначала удалить старое. Избранное и остальные данные при этом теряются.

Отсюда два правила:

1. **Резервная копия ключа обязана существовать вне сервера.** Хранить
   ключ в GitHub нельзя — репозиторий публичный.
2. Если ключ утерян, выпустить обновление поверх старой версии уже
   невозможно. Остаётся только сменить `applicationId`, и тогда
   приложение станет новым и установится рядом со старым.

## Порядок выпуска

1. Поднять версию в `app/build.gradle.kts`:

   ```kotlin
   versionCode = 57
   versionName = "0.57.0-alpha"
   ```

   `versionCode` растёт на единицу — его сравнивает Android. `versionName`
   несёт суффикс стадии: `-alpha` сейчас, `-beta` в будущем.

2. Прогнать тесты, они обязаны быть зелёными:

   ```bash
   ./gradlew testDebugUnitTest
   ```

3. Собрать и подписать по инструкции выше.

4. Проверить на устройстве или эмуляторе. Обязательная проверка: новая
   версия должна вставать **поверх** ранее установленной, а не требовать
   удаления. Это единственная проверка, которая подтверждает, что подпись
   в порядке:

   ```bash
   adb install -r app/build/outputs/apk/release/app-release.apk
   adb shell dumpsys package com.mietschedule.app | grep versionName
   ```

5. Коммит, тег, публикация:

   ```bash
   git commit -am "0.57: описание изменений"
   git push origin main
   git tag 0.57
   git push origin 0.57
   gh release create 0.57 app/build/outputs/apk/release/app-release.apk \
     --title "0.57.0-alpha" \
     --notes-file /tmp/rel57.md \
     --latest
   ```

## Особенности публикации

**Тег обязан совпадать с номером релиза** — `0.57`, не `v0.57` и не `0.57.0-alpha`.

**Флаг `--latest` обязателен.** `UpdateChecker` читает только
`/releases/latest`, поэтому релиз, помеченный как prerelease, до
пользователей не дойдёт: приложение просто не увидит обновления.

## Что попадает в описание релиза

- буллиты с изменениями, по одной строке на изменение;
- контрольная сумма APK (MD5), чтобы пользователь мог проверить файл;
- **без фамилий и номеров групп** — расписание содержит персональные данные
  студентов и преподавателей.

Пример оформления — в предыдущих релизах этого репозитория.

## Серверная часть

Часть функций полагается на Google Apps Script, исходники которого лежат в
`game_top.gs`: таблица рекордов мини-игры и счётчик установок.

**Развернуть его может только владелец скрипта.** Без доступа к аккаунту,
под которым он создан, скрипт не обновить — и правки в `game_top.gs` останутся
незадействованными. Изменения сначала в репозитории, потом развёртывание.

## Структура проекта

```
app/src/main/java/com/mietschedule/app/
  MainActivity.kt        навигация, состояние экранов, запуск
  ScheduleUi.kt          экран расписания и карточка пары
  ScheduleData.kt        загрузка расписания для каждой роли
  NextLessonCard.kt      карточка ближайшей пары на главном экране
  NextLessonRow.kt       строка ближайшей пары в списке
  GroupPickerUi.kt       выбор группы, избранное, склонение
  PickersScreen.kt       выбор преподавателя и аудитории
  RolePickerScreen.kt    выбор роли
  FavoritesScreen.kt     экран избранного
  AboutScreen.kt         экран «О программе»
  SettingsScreen.kt      настройки напоминаний и оформления
  ReportScreen.kt        форма отчёта об ошибке
  ReportData.kt          сбор состояния приложения для отчёта
  ReportSender.kt        отправка отчёта через Web3Forms
  TapChipScreen.kt       мини-игра «Тапать микросхему»
  ChipTop.kt             игра и таблица рекордов
  InstallCounter.kt      счётчик установок
  MietTopBar.kt          единая шапка
  ScheduleTheme.kt       светлая и тёмная тема
  ScheduleColorScheme.kt палитра
  MietApi.kt             сетевой слой и кэш
  ApiPaths.kt            адреса эндпоинтов miet.ru
  Models.kt              модели ответа сервера, таблица времени
  WeekType.kt            учебные недели, числитель и знаменатель
  Faculties.kt           разбор маркировок групп
  TeacherIndex.kt        сборка расписания преподавателя
  GroupPrefs.kt          выбранная роль и группа, избранное
  CachePolicy.kt         сроки жизни кэша и период фонового обновления
  RefreshWorker.kt       фоновое обновление расписаний
  BootReceiver.kt        восстановление напоминаний после перезагрузки
  ReminderScheduler.kt   постановка уведомлений о начале пары
  UpdateChecker.kt       проверка и загрузка обновлений
  UpdateDialog.kt        диалог обновления
  LastUpdated.kt         время последнего обновления данных
  Analytics.kt           инициализация Appmetrica
game_top.gs              серверная часть Apps Script: рекорды и счётчик
docs/                    скриншоты для README
```

## Кэш и нагрузка на сайт

`CachePolicy` держит два срока, и они обязаны совпадать: `SCHEDULE_TTL_MS` —
сколько кэш считается свежим, `REFRESH_PERIOD_MS` — как часто фоновая задача
проверяет обновления. Сейчас оба равны 6 часам.

Фоновая задача ходит на сайт только за избранными группами и за текущей.
Кнопка «Обновить всё» в шапке перебирает все группы — это примерно 344
запроса за раз, поэтому её стоит вызывать осознанно.

Если число пользователей вырастет, первое, что нужно сделать, — разнести
период фоновой задачи случайной задержкой, иначе приложения, установленные
в один день, будут обновляться синхронно.