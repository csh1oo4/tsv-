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

## Build

The GitHub Actions workflow (`.github/workflows/build-apk.yml`) builds a debug
APK on every push. Locally the same thing is `gradle assembleDebug` with JDK 17
and the Android SDK installed.

- applicationId: `com.tsv.dutycalendar` (installs beside `TSV 근무표`)
- versionCode 1 / versionName 1.0
- Signing: fixed PKCS12 key `app/tsv-duty-fixed.p12` wired into
  `signingConfigs.debug`, so every rebuild installs over the previous one.

## Icons

`tools/generate_icons.py` draws the launcher art from scratch with PIL
(deep blue → indigo background, white calendar card with a blue top bar and
binder rings, bold TSV wordmark) and writes legacy, round, adaptive foreground
and monochrome layers for all five densities, plus `icon-preview.png`.
