const SHEET_ID = '1ZglVZn_H84X8O5HchU3gRS5eSJZhYjBoFt4uS8uOLkc';
const SHEET_NAME = 'Scores';
const HEADER = ['Nick', 'Score', 'Week', 'Install', 'Time'];
// Топ игроков — 50 строк. Было 100: список на телефоне становился длинным
// листом, а играть интересно только тем, кто в верхней части.
const MAX_ROWS = 50;
const MAX_NICK = 30;
const MAX_SCORE = 20000;
const WEEK_RESET_DOW = 1;   // понедельник
const WEEK_RESET_HOUR = 9;  // 09:00 по часовому поясу скрипта
const WEEK_MS = 7 * 86400000;
const EPOCH = Date.UTC(2024, 0, 1);
const BLOCKED = ['admin', 'test', 'spam', 'hack', 'bot', 'premium', 'free',
  'админ', 'тест', 'премия', 'мод', 'читер', 'взлом', 'подмена'];

function tz() {
  return Session.getScriptTimeZone();
}

function json(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}

/**
 * Смещение часового пояса скрипта от UTC в миллисекундах на момент t.
 */
function tzOffsetMs(t) {
  const p = Utilities.formatDate(new Date(t), tz(), 'yyyy-MM-dd-HH-mm-ss').split('-').map(Number);
  const asUtc = Date.UTC(p[0], p[1] - 1, p[2], p[3], p[4], p[5]);
  return Math.round((asUtc - t) / 60000) * 60000;
}

/**
 * Момент времени по стенным часам скрипта → настоящий epoch.
 *
 * Раньше здесь был new Date(y, m, d, 9), то есть часовой пояс среды
 * выполнения. Смена настройки проекта молча сдвинула бы сброс недели на
 * другой час. Теперь преобразование идёт только через timezone скрипта.
 */
function wallClockMs(y, mo, d, h) {
  const guess = Date.UTC(y, mo - 1, d, h, 0, 0, 0);
  return guess - tzOffsetMs(guess);
}

function wallClock(t) {
  return Utilities.formatDate(new Date(t), tz(), 'yyyy-MM-dd-HH-mm-ss').split('-').map(Number);
}

/** Момент последнего сброса (понедельник 09:00) на или до момента t. */
function weekStart(t) {
  const p = wallClock(t);
  const wdow = new Date(Date.UTC(p[0], p[1] - 1, p[2])).getUTCDay();
  const midnight = wallClockMs(p[0], p[1], p[2], 0);
  const mondayMidnight = midnight - ((wdow - WEEK_RESET_DOW + 7) % 7) * 86400000;
  const reset = mondayMidnight + WEEK_RESET_HOUR * 3600000;
  return t < reset ? reset - WEEK_MS : reset;
}

function weekIndex(t) {
  return Math.floor((weekStart(t) - EPOCH) / WEEK_MS);
}

function nextReset() {
  return new Date(weekStart(Date.now()) + WEEK_MS);
}

/**
 * Проверка входных данных. Общая для обоих путей записи: POST и GET.
 *
 * Раньше правила жили только в doPost, а приложение писало через GET, и
 * ник «admin» спокойно попадал в таблицу. Проверка вынесена, чтобы
 * расхождение между путями больше невозможно было пропустить.
 */
function validate(input) {
  const nick = String(input.nick || '').replace(/\s+/g, ' ').trim();
  const score = Math.floor(Number(input.score));
  const install = String(input.install || '').replace(/[^a-zA-Z0-9_-]/g, '').slice(0, 40);

  if (!nick || nick.length > MAX_NICK) return {ok: false, error: 'nick'};
  const low = nick.toLowerCase();
  for (let i = 0; i < BLOCKED.length; i++) {
    if (low.indexOf(BLOCKED[i]) >= 0) return {ok: false, error: 'nick'};
  }
  if (!install) return {ok: false, error: 'install'};
  if (!(score >= 1) || score > MAX_SCORE) return {ok: false, error: 'score'};
  return {ok: true, nick: nick, score: score, install: install};
}

function table() {
  const ss = SpreadsheetApp.openById(SHEET_ID);
  const sh = ss.getSheetByName(SHEET_NAME) || ss.insertSheet(SHEET_NAME);
  // Заголовок пишется только на пустой таблице. Раньше он дописывался при
  // каждом обращении и сам попадал в данные как игрок.
  if (sh.getLastRow() === 0) {
    sh.appendRow(HEADER);
    sh.setFrozenRows(1);
  }
  return sh;
}

/**
 * Текущий топ недели, уже отсортированный и обрезанный до MAX_ROWS.
 */
function topList(week) {
  const rows = table().getDataRange().getValues();
  const top = [];
  for (let i = 1; i < rows.length; i++) {
    const r = rows[i];
    if (!r || !r[0]) continue;
    if (Number(r[2]) !== week) continue;
    top.push({nick: String(r[0]), score: Number(r[1])});
  }
  top.sort(function (a, b) { return b.score - a.score; });
  return top.slice(0, MAX_ROWS);
}

/** Общая часть ответа: топ плюс срок его действия. */
function envelope(extra) {
  const week = weekIndex(Date.now());
  return Object.assign({
    ok: true,
    tz: tz(),
    week: week,
    resetsAt: nextReset().toISOString(),
    top: topList(week),
  }, extra || {});
}

/**
 * Переписать таблицу, оставив не больше MAX_ROWS строк.
 *
 * Запись всегда идёт целиком: сортировка и обрезка меняют набор строк, а
 * точечное обновление одной ячейки не умеет ни переставить строки, ни
 * убрать вылетевшую неделю. Отдельная функция нужна потому, что запись
 * вызывается из двух веток — обычной и ветки «счёт не улучшился, но
 * ник изменился».
 */
function writeTop(sh, rows) {
  const top = rows.slice().sort(function (a, b) {
    const d = Number(b[1]) - Number(a[1]);
    return d ? d : new Date(a[4]).getTime() - new Date(b[4]).getTime();
  }).slice(0, MAX_ROWS);
  sh.clearContents();
  sh.getRange(1, 1, 1, HEADER.length).setValues([HEADER]);
  if (top.length) {
    sh.getRange(2, 1, top.length, HEADER.length).setValues(top.map(function (r) {
      return [String(r[0]), Number(r[1]), Number(r[2]), String(r[3]), new Date(r[4])];
    }));
  }
  return top;
}

/** Записать результат игрока. */
function submit(input) {
  const v = validate(input);
  if (!v.ok) return v;

  const week = weekIndex(Date.now());

  const sh = table();
  const rows = sh.getDataRange().getValues();
  const kept = [];

  for (let i = 1; i < rows.length; i++) {
    const r = rows[i];
    if (!r || !r[0]) continue;
    if (Number(r[2]) !== week) continue;   // прошлая неделя — вылетает
    // Тот же игрок — по ключу ИЛИ по нику.
    //
    // Ключ выводится из ника, но не всегда совпадает: смена ника меняет
    // ключ, и вернувшись к прежнему нику, игрок получал вторую строку.
    // В топе тогда оказывались два одинаковых ника с разными счётами,
    // и человек видел «у меня 0, а у этого ника 21» про сам себя.
    // Личность игрока — ник, поэтому сверяемся и по нему тоже.
    const sameByKey = r[3] === v.install;
    const sameByNick = String(r[0]).toLowerCase() === v.nick.toLowerCase();
    if (sameByKey || sameByNick) {
      // Строка заменяется целиком. Раньше при улучшении счёта старая строка
      // пропускалась, а новая дописывалась в конец, и в топе оказывалось
      // два одинаковых ника. Теперь остаётся одна строка: лучший счёт
      // и текущий ник.
      const rest = rows.slice(i + 1);   // строки после текущей
      if (Number(r[1]) > v.score) {
        // Счёт не улучшился — держим прежний, но ник обновляем.
        const mine = [v.nick, Number(r[1]), week, v.install, new Date()];
        if (String(r[0]) !== v.nick) {
          kept.push(mine);
          writeTop(sh, kept.concat(rest));
        }
        // Для подсчёта места строка игрока обязана быть в наборе.
        // Раньше здесь возвращали rank без неё, и rankOf отдавал 0:
        // надпись «место N» исчезала именно тогда, когда игрок ничего
        // не улучшил, то есть в самый обычный момент. Нашли тестом.
        // Строка игрока в этом наборе обязана быть: текущая строка из
        // таблицы в kept не попала (цикл её пропустил), а без неё rankOf
        // вернул бы 0 и надпись «место N» исчезла бы. Поэтому добавляем
        // безусловно — и при совпадении по ключу, и по нику.
        const pool = kept.concat([mine], rest);
        return envelope({
          rank: rankOf(pool, v.install, v.nick),
          score: Number(r[1]),
          dup: true,
        });
      }
      kept.push([v.nick, v.score, week, v.install, new Date()]);
      writeTop(sh, kept.concat(rest));
      return envelope({
        rank: rankOf(kept.concat(rest), v.install, v.nick),
        score: v.score,
      });
    }
    kept.push(r);
  }

  kept.push([v.nick, v.score, week, v.install, new Date()]);
  const top = writeTop(sh, kept);
  const rank = rankOf(top, v.install, v.nick);
  // Топ возвращается вместе с записью: приложение не делает второй запрос,
  // а сразу видит, кого обогнал. Раньше после каждой отправки оно
  // отдельно читало топ, то есть платило по 2.5 секунды дважды.
  return envelope({rank: rank, score: v.score, total: top.length});
}

/**
 * Место игрока в отсортированном топе.
 *
 * Ищет по ключу И по нику — тем же правилом, что и submit. Иначе игрок,
 * найденный по нику, получал бы 0 и надпись «место» исчезала бы.
 */
function rankOf(rows, install, nick) {
  const sorted = rows.slice().sort(function (a, b) { return Number(b[1]) - Number(a[1]); });
  const low = String(nick || '').toLowerCase();
  for (let i = 0; i < sorted.length; i++) {
    if (sorted[i][3] === install) return i + 1;
    if (low && String(sorted[i][0]).toLowerCase() === low) return i + 1;
  }
  return 0;
}

function doPost(e) {
  try {
    const d = JSON.parse((e && e.postData && e.postData.contents) || '{}');
    if (d.stat !== undefined) return installEntry(d);
    return json(submit(d));
  } catch (err) {
    return json({ok: false, error: String(err)});
  }
}

function doGet(e) {
  try {
    const p = (e && e.parameter) || {};
    // Сводка по установкам: открывается прямо в браузере, без приложения.
    // Проверка идёт до submit, потому что у обоих наборов есть параметр
    // version, а у статистики — нет ни nick, ни score.
    if (p.stat !== undefined) return installEntry(p);
    // Регистрация запуска: приходит с device, но без nick и score, поэтому
    // без этой ветки запрос ушёл бы в envelope и устройство не записалось бы.
    if (p.device !== undefined) return installEntry(p);
    const looksLikeSubmit = p.nick !== undefined || p.install !== undefined || p.score !== undefined;
    // Запись идёт через GET: POST на развёрнутом скрипте после редиректа
    // Google отдаёт 405, и тело ответа теряется.
    if (looksLikeSubmit) return json(submit(p));
    return json(envelope());
  } catch (err) {
    return json({ok: false, error: String(err)});
  }
}

// ----------------------------------------------------------------------
/**
 * Дополнение к topleaderboard.gs: подсчёт установок и возвратов.
 *
 * Зачем это нужно рядом с Appmetrica. Appmetrica приписывает установку к
 * магазину через install referrer: когда APK поставлен руками, скачав файл
 * по ссылке, метки нет, и в отчёте такая установка не появляется вообще.
 * Распространяется приложение ссылкой на GitHub, поэтому реферрера не будет
 * ни у кого — цифра установок всегда была бы нулём.
 *
 * Свой счётчик от референера не зависит: устройство само сообщает серверу
 * при первом запуске. Ответ сервера переносит счётчик: при обновлении через
 * приложение первое обращение — уже не первое, и устройство получает «своё»
 * число. Так считаются и установки (сколько разных устройств), и возвраты
 * (сколько из них запускались повторно).
 *
 * Что считается:
 *   devices  — сколько разных устройств хоть раз запустили приложение;
 *   today    — сколько устройств отметилось сегодня (lastSeen = сегодня);
 *   newToday — сколько устройств впервые появилось сегодня (firstSeen).
 *
 * Списка устройств за сутки сводка НЕ отдаёт: для DAU хватает счётчика, а
 * список тянул бы в ответ всю таблицу.
 *
 * Лист Installs создаётся сам при первом обращении.
 */

/**
 * Лист с аналитикой установок.
 *
 * Отдельный от Scores намеренно: в Scores лежат очки игроков, которые живут
 * одну неделю и обнуляются по понедельникам. Установки не сбрасываются, их
 * история нужна целиком, и срок жизни у строк другой. Смешивать в одном
 * листе означало бы, что правила хранения у данных разные, а таблица одна.
 *
 * Лист создаётся при первом обращении, а не заранее.
 */
var INSTALL_SHEET = 'Installs';
var INSTALL_COLS = ['device', 'firstSeen', 'lastSeen', 'runs', 'version', 'model'];

/** Часовой пояс отчётов. Совпадает с tz() игрового скрипта. */
function installTz() {
  return 'Europe/Moscow';
}

/**
 * Открывает (или создаёт) лист с установками.
 *
 * Создание ленивое: до первого обращения листа нет, и таблица не засоряется
 * пустыми вкладками.
 */
function installSheet() {
  var ss = SpreadsheetApp.openById(SHEET_ID);
  return ss.getSheetByName(INSTALL_SHEET) || ss.insertSheet(INSTALL_SHEET);
}

/**
 * Приводит номер версии к строке.
 *
 * Приходит число вида 46, а не 46.0.0-alpha: сравнивать строки бесполезно.
 * Хранится как есть, сортировка по дате остаётся корректной.
 */
function installVersion(v) {
  var n = parseInt(v, 10);
  return isFinite(n) && n > 0 ? String(n) : '';
}

/** Обрезает строку до длины, при которой лист не разрастается из-за мусора. */
function installText(v, max) {
  var s = String(v === undefined || v === null ? '' : v);
  return s.length > max ? s.slice(0, max) : s;
}

/**
 * Уникальный ключ устройства.
 *
 * Клиент присылает device — UUID, который он сгенерировал при установке.
 * Он же валидируется по длине и набору символов: иначе в таблицу попало бы
 * что угодно, включая пустую строку.
 */
function deviceKey(d) {
  var s = installText(d, 64);
  return /^[A-Za-z0-9_-]{8,64}$/.test(s) ? s : '';
}

/**
 * Записывает один запуск приложения.
 *
 * @param {{device:string, first:boolean, version:(string|number), model:string}} input
 *   device  — идентификатор устройства, сгенерированный приложением;
 *   first   — true, если приложение запущено впервые после установки;
 *   version — versionCode установленной копии;
 *   model   — модель телефона (для разбивки по устройствам).
 */
function registerRun(input) {
  var device = deviceKey(input && input.device);
  if (!device) return { ok: false, error: 'device' };

  var sh = installSheet();
  var last = sh.getLastRow();
  var now = new Date();
  var dayLabel = Utilities.formatDate(now, installTz(), 'yyyy-MM-dd');
  var weekLabel = Utilities.formatDate(now, installTz(), 'yyyy') + '-W' + weekOf(now);
  var version = installVersion(input.version);
  var model = installText(input.model, 40);

  if (last < 1) {
    sh.getRange(1, 1, 1, INSTALL_COLS.length).setValues([INSTALL_COLS]);
    sh.setFrozenRows(1);
    // Первая строка данных идёт за заголовком; lastRow сейчас указывает
    // на заголовок, поэтому первое устройство пишется в строку 2.
    last = 1;
  }

  var start = 2;
  var rows = last - 1;
  var found = -1;
  if (rows > 0) {
    var values = sh.getRange(start, 1, rows, 1).getValues();
    for (var i = 0; i < values.length; i++) {
      if (String(values[i][0]).trim() === device) { found = i; break; }
    }
  }

  var row;
  if (found < 0) {
    row = start + rows;
    sh.getRange(row, 1, 1, INSTALL_COLS.length).setValues([[
      device, dayLabel, dayLabel, 1, version, model,
    ]]);
  } else {
    row = start + found;
    sh.getRange(row, 4).setValue(sh.getRange(row, 4).getValue() + 1);
    sh.getRange(row, 3).setValue(dayLabel);
    if (version) sh.getRange(row, 5).setValue(version);
    if (model) sh.getRange(row, 6).setValue(model);
  }

  var total = Math.max(row, 1);
  return {
    ok: true,
    device: device,
    first: found < 0,
    total: total - 1,
    day: dayLabel,
    week: weekLabel,
    runs: sh.getRange(row, 4).getValue(),
  };
}

/** Номер недели по ISO — тот же, что у игрового скрипта. */
function weekOf(d) {
  var t = new Date(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()));
  var dayNum = (t.getUTCDay() + 6) % 7;
  t.setUTCDate(t.getUTCDate() - dayNum + 3);
  var firstThu = new Date(Date.UTC(t.getUTCFullYear(), 0, 4));
  var fDayNum = (firstThu.getUTCDay() + 6) % 7;
  firstThu.setUTCDate(firstThu.getUTCDate() - fDayNum + 3);
  var week = 1 + Math.round((t.getTime() - firstThu.getTime()) / (7 * 86400000));
  return String(week);
}

/**
 * Сводка по установкам.
 *
 * Отдельная функция без записи: её можно открыть прямо в браузере и сразу
 * увидеть цифры, без приложения.
 */
function installStats() {
  var sh = installSheet();
  var last = sh.getLastRow();
  if (last < 2) return { ok: true, devices: 0, returning: 0, runs: 0, today: 0, week: [] };

  // getDisplayValues, а не getValues: лист получает даты через
  // Utilities.formatDate как текст, но выбранный формат ячейки может
  // превратить их в серийный номер. Тогда String(data[i][2]) даёт "45434",
  // сравнение с todayLabel не сходилось, и today/newToday оставались нулём
  // при заведомо сегодняшних данных. На дисплее значение всегда текст.
  var data = sh.getRange(2, 1, last - 1, INSTALL_COLS.length).getDisplayValues();
  var todayLabel = Utilities.formatDate(new Date(), installTz(), 'yyyy-MM-dd');
  var today = 0, returning = 0, runs = 0, newToday = 0;
  var versions = {};

  for (var i = 0; i < data.length; i++) {
    var d = String(data[i][0]).trim();
    if (!d) continue;
    var n = Number(data[i][3]) || 1;
    runs += n;
    if (n > 1) returning++;
    if (String(data[i][2]).trim() === todayLabel) today++;
    if (String(data[i][1]).trim() === todayLabel) newToday++;
    var v = String(data[i][4]).trim();
    if (v) versions[v] = (versions[v] || 0) + 1;
  }

  var week = Object.keys(versions).map(function (k) {
    return { version: k, devices: versions[k] };
  }).sort(function (a, b) { return b.devices - a.devices; });

  return {
    ok: true,
    devices: data.length,
    returning: returning,
    runs: runs,
    today: today,
    newToday: newToday,
    versions: week,
    updated: Utilities.formatDate(new Date(), installTz(), 'yyyy-MM-dd HH:mm:ss'),
  };
}

/**
 * Точка входа для статистики.
 *
 * Вызывается отдельно от игрового: игровой скрипт считает очки, этот —
 * устройства. Смешивать нельзя, иначе один запрос делал бы две работы и
 * ошибка в одной половине уронила бы обе.
 */
function installEntry(p) {
  try {
    var q = p || {};
    if (q.stat === '1' || q.stat === 'true') return json(installStats());
    return json(registerRun(q));
  } catch (err) {
    return json({ ok: false, error: String(err) });
  }
}