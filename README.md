# TSV 교대달력 (Android)

`TSV 교대달력` is a thin Android shell around the offline web app in
`app/src/main/assets/index.html`. The page is byte-identical to the web build
(md5 checked at build verification); everything the app knows about the shift
table, memos and settings lives in the page's own `localStorage`.

The one native piece is the device calendar. `MainActivity` registers a
JavaScript bridge as `window.AndroidCalendar` and the page calls it directly:

| method | purpose |
| --- | --- |
| `hasCalendarPermission(): boolean` | READ + WRITE calendar granted |
| `requestCalendarPermission(): void` | runtime permission request; the result comes back as `window.onCalendarPermissionResult(granted)` |
| `getCalendars(): String` | JSON array of `{id, name, accountName, color}` |
| `getEvents(startMs, endMs): String` | JSON array of `{id, calId, calName, title, ymd, allDay, startTime, endTime, color}` from `CalendarContract.Instances` |
| `addEvent(ymd, title, allDay, startTime, endTime): boolean` | inserts into the first writable visible calendar |

All-day rows are formatted in UTC (`yyyy-MM-dd`) so the date does not slide a day
in KST or other positive-offset zones.

A second bridge, `window.AndroidBridge`, plus the script `app/src/main/assets/bridge.js`
(injected after load — `index.html` itself is never edited) keeps the app's own web
features working: the JSON backup export saves to the phone's Downloads folder, the
"텍스트 복사" button uses the native clipboard, the JSON import picker opens, and
`localStorage` is mirrored into SharedPreferences so the shift table survives even if
the WebView storage for the `file://` origin is cleared.

A third bridge, `window.AndroidAlarm`, carries the shift alarms to the platform:

| method | purpose |
| --- | --- |
| `scheduleAlarms(json): void` | replaces the whole native alarm schedule from a JSON array of `{id, triggerAtMs, label, gameEnabled, gameDifficulty, gameCount, sound, vibrate}` |
| `cancelAlarms(): void` | clears the schedule |

The repository stores that list and keeps `AlarmManager` in sync with it
(`setExactAndAllowWhileIdle`, or `setAndAllowWhileIdle` when `SCHEDULE_EXACT_ALARM`
is not granted). `AlarmReceiver` fires at the alarm time, posts a high-importance
alarm notification with a full-screen intent, starts `AlarmActivity` (rings the
default alarm tone, vibrates, shows over the lock screen) and re-arms the future
entries after a reboot or an app update.

## v1.6 — 홈 화면 위젯 5종

다섯 개의 홈 화면 위젯이 추가되었고, 네이티브 쪽에 `window.AndroidWidget`
브리지가 붙었습니다.

| 위젯 | 크기 | 내용 |
| --- | --- | --- |
| `Widget1DayProvider` — 오늘 근무 | 1×1 | 오늘 `M.d` · 요일 · 큰 근무 배지 |
| `Widget2DayProvider` — 오늘·내일 근무 | 2×1 | 오늘/내일 두 칸, 날짜·요일·배지 |
| `Widget4DayProvider` — 4일 근무 | 2×2 | 오늘부터 4일, 2×2 그리드 |
| `WidgetTeam4DayProvider` — 동료 4일 근무 | 2×1 | 4일 날짜 헤더 + 등록된 조마다 4일 배지 |
| `WidgetMonthProvider` — 한달 근무 | 4×4 | `< 2026.11 >` + 요일 줄 + 6주×7일 그리드, 오늘 강조 |

- `window.AndroidWidget.syncWidgetData(json)`은 페이지가 저장할 때마다
  `{myTeamId, anchorDate, teams[], overrides{}, holidays{}}`를 넘겨받아
  `SharedPreferences("tsv_widget_prefs", "widget_json")`에 보관하고, 다섯
  프로바이더에 `ACTION_APPWIDGET_UPDATE`를 보내 즉시 다시 그립니다.
  앱을 한 번도 열지 않았을 때는 `DEFAULT_DATA`와 같은 기본값(A/B/C조, B조,
  기준일 2026-11-22)으로 계산합니다.
- 근무 계산은 웹앱의 `getShift()`와 같습니다 — 패턴 인덱스 + `anchorDate`와의
  일수 차이, `overrides`의 문자열/`{main, sub}` 객체 지원, `연차·반차·교육`은
  서브 근무로 배지에 표시.
- 색: 주/주특 = 앰버 원형 배지(`#F59E0B`), 야/야특 = 다크(`#1E293B`), 교육 =
  파랑(`#0EA5E9`), 연차·반차 = 초록(`#16A34A`), 휴 = 원 없이 연한 주황 글씨.
  날짜는 토요일 파랑 `#2563EB`, 일요일·공휴일 빨강 `#EF4444`.
- 한달 위젯의 화살표는 `WIDGET_MONTH_PREV/MONTH_NEXT` 브로드캐스트로
  `month_offset`을 옮겨 그립니다(0 = 이번 달).
- 모든 카드를 누르면 앱이 열리고, 갱신 주기는 위젯별 30분(`1800000`ms)입니다.

## Build

The GitHub Actions workflow (`.github/workflows/build-apk.yml`) builds a debug
APK on every push. Locally the same thing is `gradle assembleDebug` with JDK 17
and the Android SDK installed.

- applicationId: `com.tsv.dutycalendar` (installs beside `TSV 근무표`)
- versionCode 9 / versionName 1.6
- Signing: fixed PKCS12 key `app/tsv-duty-fixed.p12` wired into
  `signingConfigs.debug`, so every rebuild installs over the previous one.

## v1.2

- **근무별 알람 (네이티브)** — the page's alarm settings now actually ring on the
phone. `AndroidAlarm.scheduleAlarms(json)` cancels the previous `PendingIntent`s,
stores the new list in `SharedPreferences` and schedules each occurrence with
`AlarmManager`; on Android 12+ without the exact-alarm permission it falls back to
`setAndAllowWhileIdle` instead of crashing.
- **AlarmReceiver** — posts a high-priority alarm notification
(`CHANNEL_ID = tsv_duty_alarm`, full-screen intent, `CATEGORY_ALARM`) and starts
`AlarmActivity` with `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP`; it also
re-arms still-future alarms on `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`.
- **AlarmActivity** — lock-screen alarm screen: label + time, the device's default
`TYPE_ALARM` ringtone and a repeating vibration pattern. `gameEnabled` asks
`gameCount` arithmetic questions (`easy` single-digit +, `normal` two-digit ±,
`hard` two-digit × one-digit + two-digit) and only stops when every answer is right;
with the game off a single "알람 끄기" button dismisses it.
- New permissions: `VIBRATE`, `WAKE_LOCK`, `USE_FULL_SCREEN_INTENT`,
`SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`, `POST_NOTIFICATIONS` (requested at
start on Android 13+). The first time an alarm is scheduled, the app offers the
Android 12+ "알람 및 리마인더" and Android 14+ "전체 화면 알림" special-access
switches once, since without them an alarm can ring late or only as a heads-up.

## v1.1

- **자동 재동기화** — `onResume()` fires `window.onAppResume()` in the page, so
after editing an event in the Samsung/Google calendar app the table re-reads the
device calendar as soon as the user comes back (no sync tap needed).
- **이미지 카톡 공유** — `AndroidBridge.shareImageDataUrl(dataUrl, fileName)`
decodes the canvas PNG and writes it to the gallery
(`Pictures/TSV교대달력` via `MediaStore.Images.Media` on Android 10+, a scanned
file under `Pictures/TSV교대달력` on Android 9 and below), then opens the system
share sheet (`ACTION_SEND`, `image/png`, `FLAG_GRANT_READ_URI_PERMISSION`) so one
tap picks KakaoTalk.
- **사진 선택기** — `onShowFileChooser` now gives the intent a concrete type
(`image/*` for `<input type="file" accept="image/*">`), so the 설비 사진 첨부
button opens the gallery/photo picker instead of an "all files" browser;
`bridge.js` un-hides file inputs (including ones built inside a sheet) at click
time so the WebView actually opens the picker.

## v1.3

- **새 아이콘** — the launcher icon is the artwork the user supplied (gold PI film
roll + calendar on a white rounded-square backdrop). `tools/generate_icons_from_art.py`
resizes it with LANCZOS into legacy squircle (`ic_launcher`), circle
(`ic_launcher_round`) and adaptive foreground layers for all five densities and
follows it with `icon-preview.png`. The adaptive background is plain white
(`#FFFFFF`) so the white backdrop of the artwork blends in, and the foreground
artwork sits at 76% of the 108dp canvas, inside the 72dp safe zone, so neither a
circular (One UI) nor a squircle mask clips the film roll or the calendar.
- The `<monochrome>` themed-icon layer was dropped: a tint silhouette of this
photographic artwork loses the film roll's highlights, so themed icons simply
fall back to the full-colour icon.
- Web app: `assets/index.html` refreshed byte-for-byte from the web build.

## Icons

`tools/generate_icons_from_art.py SOURCE_IMAGE` builds the launcher icons from the
supplied artwork (resize, centre, squircle/circle mask, adaptive safe-zone
placement) and writes legacy, round and adaptive foreground layers for all five
densities, plus `icon-preview.png`.

The previous from-scratch artwork generator is kept in git history
(`tools/generate_icons.py` at v1.2).
