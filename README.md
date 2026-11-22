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

## Build

The GitHub Actions workflow (`.github/workflows/build-apk.yml`) builds a debug
APK on every push. Locally the same thing is `gradle assembleDebug` with JDK 17
and the Android SDK installed.

- applicationId: `com.tsv.dutycalendar` (installs beside `TSV 근무표`)
- versionCode 3 / versionName 1.2
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
start on Android 13+).

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

## Icons

`tools/generate_icons.py` draws the launcher art from scratch with PIL
(deep blue → indigo background, white calendar card with a blue top bar and
binder rings, bold TSV wordmark) and writes legacy, round, adaptive foreground
and monochrome layers for all five densities, plus `icon-preview.png`.
