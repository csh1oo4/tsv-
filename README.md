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
- 한달 위젯은 5주짜리 달이면 여섯 번째 주 줄을 숨겨 그리드를 꽉 채웁니다.
- 모든 카드를 누르면 앱이 열리고, 갱신 주기는 위젯별 30분(`1800000`ms)입니다.

## v2.0 — React 디자인 개편 + 실시간 공유/네이티브 5종 연결

The page is now the React + Tailwind design the user sent, kept as one HTML file:
`assets/index.html` carries React 18.3.1, ReactDOM, the Tailwind Play CDN runtime and
the Firebase 10.14.1 compat SDK **inlined**, and the JSX is **pre-compiled at build
time** (no `text/babel`, no Babel at runtime), so the app still starts with no network.

- **Firestore (팀 코드, 기본 `TSV-AMMK`)** — `workspaces/{teamCode}/`
  - `sharedEvents` — 회사 공통 / 휴가 일정. 개인(🔒) 일정은 `localStorage`에만 남는다.
    v1.6.x 문서(`title`, `scope`)와 v2 문서(`text`, `shareType`)를 모두 읽고 v2 형태로 쓴다.
  - `equipFolders`, `equipPosts` — 설비 폴더와 점검 기록. v1.6.x의 `equipmentPosts`
    (`note`, `imageData`, `folderName`)도 함께 구독해 기존 기록이 그대로 보이고,
    폴더가 없던 기록은 "미분류 설비 기록" 폴더에 모인다.
  - `boardPosts` — 게시판. v1.6.x의 `body`/`imageData`/`dateLabel`/한글 카테고리
    (`맛집추천`, `공지` …)도 v2 카테고리로 매핑해 읽는다.
- **`tsvHandleBack()`** — the page closes the top-most modal/drawer (or leaves the widget
  view) and the hardware back press only exits when nothing is open. `BACK_JS` calls it first.
- **설비 검색** — the 설비 공유 tab keeps its own search field and filters client-side while
  typing (the input node is never rebuilt, so Korean IME composition is safe).
- **`AndroidAlarm`** — the page computes the next 30 days of shift alarms from
  `alarms`/`myGroupId`/`groups`/`pattern`/`anchorDate`/`overrides` and calls
  `scheduleAlarms(json)` with the same occurrence shape as before
  (`{id, triggerAtMs, label, gameEnabled, gameDifficulty, gameCount, sound, vibrate}`).
- **`AndroidWidget`** — `syncWidgetData(json)` gets `{myTeamId, anchorDate, teams[], overrides,
  holidays, events}` translated from the React state: `groups[].offset` → `anchorIndex`,
  `overrides`/`subOverrides` (`{teamId}_{ymd}`) → `{ymd: {teamId: {main, sub}}}`,
  `HOLIDAYS` → `{ymd: name}`, and the next 75 days of memos/shared/device events →
  `{ymd: [{title, color, kind}]}`.
- **`AndroidCalendar`** — permission/`getCalendars`/`getEvents` are read into the drawer
  list and merged into the calendar cells and the day sheet; `window.onAppResume`
  re-reads the device calendar (and re-syncs alarms/widgets) on return.
- **`AndroidUpdater`** — the 설정 tab now opens with a 🔄 앱 업데이트 card: it reads
  `getAppVersion()`, looks for the newest `duty-calendar-v*` GitHub release, shows
  `onUpdateProgress`/`onUpdateError` and calls `downloadAndInstall(url, name)`.
- **`AndroidBridge.shareImageDataUrl`** — 근무표 PNG is now handed to the native share
  sheet (gallery + KakaoTalk) instead of a web download.
- **데이터 이관** — on first launch the page converts v1.6.x `tsv_duty_calendar_v1`
  (`teams[].anchorIndex` → `groups[].offset`, `overrides` → `{teamId}_{ymd}` + sub shifts,
  memo array → per-date map, `alarms[].hour/minute` → `time: "HH:MM"`) exactly once.
- versionCode 12 / versionName 2.1, same fixed signing key.

## v2.1 — 연일 일정 · 조별 근무 색 · 설비 수정/삭제 · 효과음

- **연일(다일) 일정** — `AndroidCalendar.getEvents()` now emits one entry per covered
  day (`ymd`) instead of only the start day, with `startYmd`/`endYmd`/`dayIndex`/
  `dayCount`. All-day events subtract 1ms from `END` in UTC before taking the last day,
  so a 22~27일 종일 일정 no longer bleeds into the 28일; timed events walk local dates
  from `begin` to `end - 1ms` (23일 18:00 ~ 25일 10:00 → 23·24·25일). The day sheet
  labels each day (`18:00~계속 (1/3일)`, `계속 (2/3일)`, `~10:00 (3/3일)`) and the
  widget event list gets the event on every day too.
- **다른 조 근무 배지 색** — with A/C조 같이 보기 the badge keeps the real shift color:
  주 = orange (`#f97316` → `#ea580c`), 야 = black (`#1e293b` → `#0f172a`), 휴 = red
  (`#ef4444` → `#dc2626`), white bold text (gradient + solid fallback).
- **설비 기록 수정·삭제** — every 설비 공유 card (folder list and search results) has
  수정 / 삭제. 수정 opens the same sheet in edit mode with 제목·설비 폴더(select)·본문·
  사진(유지/교체/삭제) and writes `equipPosts/{id}` with `{ merge: true }` so
  `createdAt` (list order) stays; the local folder state moves with it. 삭제 asks for
  confirmation first, then removes it from local state and deletes
  `equipPosts/{id}` (and the legacy `equipmentPosts/{id}`).
- **앱 효과음** — Web Audio API only, no audio files: `톡톡` (date cells, tabs, buttons),
  `슉슉` (calendar month change by swipe or `<` `>`), `띠링` (memo·shift·settings·board·
  equip·alarm saved). 설정 탭 has a 🔊 앱 효과음 ON/OFF switch (default ON) stored in
  `localStorage` (`todaywork_v33_sound`) plus 톡톡/슉슉/띠링 preview buttons.
- versionCode 12 / versionName 2.1, same fixed signing key.

## v2.5 — 설비공유 메뉴 원격 공개/숨김 · 급여기준 근태요약 · 알람 시간 키패드 · 기상나팔

`versionCode 20`, `versionName 2.5`.

- **🔧 설비공유 메뉴 관리자 실시간 숨김/오픈** — 설정 탭의 마스터 관리자 영역(코드 `070299`
  인증 후에만 보인다)에 `🔧 설비공유 메뉴 공개 (전체 사용자)` 토글을 넣었다. 기본값은 **공개**.
  상태는 `workspaces/{teamCode}/appConfig/settings` 문서의 `equipmentMenuOpen` 필드에
  `{merge: true}`로 저장하고, 접속한 모든 클라이언트가 `onSnapshot`으로 구독해 즉시 따라간다
  (마지막 값은 `localStorage` `todaywork_v33_equipmentMenuOpen`에도 캐시, 문서가 없으면 공개).
  끄면 하단 메뉴와 드로어에서 `설비 공유` 탭이 사라지고(`visibleNavItems`), 그 화면을 보고 있던
  사용자는 자동으로 달력으로 돌아간다(`useEffect`). 다시 켜면 모든 폰에 즉시 나타난다.
- **📅 근태요약 = 급여 기준(전달 16일 ~ 해당월 15일)** — 보고 있는 달(`viewDate`) 기준으로
  집계 구간을 `전달 16일 ~ 해당월 15일`로 바꿨다(예: 2026년 11월 → `2026-10-16 ~ 2026-11-15`,
  1월 → 전년 `12-16 ~ 01-15`). 계산은 `summaryRangeFor()`·`buildAttendanceSummary()`(하루 단위
  31/30/29일 루프)로 옮겨 주간·야간·휴무·특근(주말/공휴일 출근·지정 특근)·연차·반차·교육을
  모두 같은 구간에서 센다. 요약 상단에 `급여기준 전월16~15일 (10.16~11.15)`을 표시하고,
  특근일 목록도 `10/17 (토요일)`처럼 실제 월/일로 보여 준다. 연차 줄(`🌴 연차 N일`, 반차·교육은
  있을 때만)을 추가했다.
- **⌨️ 알람 시간 숫자 키패드** — 알람 설정의 큰 시·분 표시(또는 `🔢 시간을 눌러 숫자 키패드로
  1분 단위 입력` 버튼)를 누르면 큰 0~9 키패드 모달이 뜬다. 4자리(`0637` → `06:37`), 3자리
  (`637` → `06:37`), 1~2자리(`7` → `07:00`)를 받고 `⌫`·`C`·확인·취소가 동작한다. 검증은
  시 00~23(오전/오후를 고르면 01~12를 12시간제로 풀어 24시간제로 변환)·분 00~59이며,
  잘못된 값은 저장 전에 안내만 띄운다(`timePadRead()`).
- **🎺 군대 기상나팔 알람음** — `res/raw/alarm_reveille.wav`(44.1kHz·16bit·모노, 9.0초, 피크
  -1dBFS)를 추가하고 알람음 목록에 `🎺 군대 기상나팔`(id `reveille`)을 넣었다. 음원은
  `tools/generate_alarm_reveille.py`가 뷰글 음역(C4·E4·G4·C5·E5·G5)만으로 기상나팔 선율을
  합성한다(배음 6개·어택 스쿱·긴 음 비브라토·짧은 홀 리버브). `AlarmService.builtinUri()`가
  `reveille`를 `alarm_reveille`로 매핑하므로 실제 알람·미리듣기 모두 같은 경로로 재생된다.
- versionCode 20 / versionName 2.5, 고정 서명 키(`app/tsv-duty-fixed.p12`) 그대로라 덮어쓰기
  설치된다. 웹앱 `APP_VERSION`도 2.5.
- 검증: `tmp/v25/check25.py`가 v2.4 회귀 84종 + v2.5 신규(메뉴 공개/숨김 렌더, 마스터 토글
  노출, 급여기간·집계 단위 테스트 4+11종, 키패드 13종, 기상나팔 WAV·브리지) 88종을 전수 통과.

## v2.4.3 — 앱 아이콘 3종 (순백 · 다크 블랙 · 스카이 블루) 선택

승현 님이 보내신 블랙·스카이블루 아이콘 2종을 더해, 설정 탭에서 **🤍 순백 · 🖤 다크 블랙 ·
💙 스카이 블루** 중 원하는 아이콘으로 바꿀 수 있게 했다(`versionCode 19`, `versionName 2.4.3`).

- **아이콘 리소스** — `tools/generate_icon_variants.py`가 원본 목업 두 장(둥근 사각형 아트 +
  검은/흰 바깥 모서리)에서 3종을 만든다. 다시 그리지 않고 픽셀 처리만 한다:
  `background_field()`가 바탕색(시안/중성 검정)만 골라 정규화 블러로 부드러운 색장을 만들고,
  `full_bleed()`가 목업의 바깥 모서리와 광택 베벨을 그 색장으로 녹여 어느 마스크에서도
  모서리 끊김이 없는 꽉 찬 사각형으로 만든다. 그 위에 아트를 얹고 바깥은 색장을 이어 붙인다.
- **잘림 없음(승현 님 지시)** — 필름 롤·리본·달력·느낌표가 어떤 마스크에서도 1픽셀도 잘리지
  않도록 비율을 정했다. 소스 아트의 물체 외곽을 기준으로 계산해 레거시 스퀘어클 0.95,
  레거시 원형 0.79, 적응형 108dp 아트 0.545(72dp 가시 원 안에 들어가는 한계 0.550)를 쓴다.
  `workspace_tmp/verify_icons.py`로 6가지 마스크(레거시 스퀘어클·원형, 적응형 72dp 원·스퀘어클,
  전체 원·스퀘어클)에 대해 아트 화소 71만~92만 개가 모두 마스크 안(alpha 255)에 있음을 확인했다.
- **`<activity-alias>` 3종** — `MainActivity`에서 `MAIN`/`LAUNCHER`를 떼고 `.LauncherWhite`
  (기본, `@mipmap/ic_launcher`), `.LauncherBlack`(`ic_launcher_black`),
  `.LauncherBlue`(`ic_launcher_blue`)가 런처 진입점을 맡는다. 셋 다 `targetActivity=".MainActivity"`.
- **`window.AndroidIcon` 브리지** — `getCurrentIcon()`은 `PackageManager.getComponentEnabledSetting()`
  으로 지금 켜진 alias를, `setIcon(key)`는 **선택한 alias를 먼저 ENABLED**로 켠 뒤 나머지 둘을
  DISABLED로 내린다(순서를 뒤집으면 런처가 아이콘 없는 순간을 캐시한다). 둘 다 `DONT_KILL_APP`이라
  화면과 페이지 상태가 유지된다.
- **설정 탭 `🎨 앱 아이콘 선택`** — 3열 카드에 실제 96×96 썸네일(웹앱에 data URI로 내장)을 넣고,
  적용 중인 아이콘에 파란 테두리 + `사용 중` 배지를 붙였다. 카드를 누르면 `AndroidIcon.setIcon()`을
  호출하고 "아이콘이 변경되었습니다! 홈 화면 반영까지 1~2초 걸릴 수 있으며 기기에 따라 앱이
  잠깐 닫힐 수 있습니다." 안내를 띄운다. 브리지가 없는 웹 미리보기에서는 안내만 한다.
- versionCode 19 / versionName 2.4.3, 고정 서명 키(`app/tsv-duty-fixed.p12`) 그대로라 덮어쓰기
  설치된다. 웹앱 `APP_VERSION`과 `getAppVersion()` 폴백도 2.4.3. 그 밖의 기능 변경 없음.

## v2.4.2 — 아이콘 바탕을 순백(#FFFFFF)으로

- **하늘색 원형 바탕 제거** — 승현 님이 요청하신 대로 v2.4.1 아이콘 뒤의 연한 하늘색 원판을
  없애고 전체 바탕을 순백 `#FFFFFF`으로 바꿨다. 다시 그리지 않고 픽셀·마스크 처리만 했다:
  파란 기운(`b-g`)으로 원판·그라데이션·그림자를 흰색으로 치환하고, 달력의 회색 날짜 칸·
  은색 링·금색 필름 롤·TSV·QR·느낌표는 보호 마스크로 원본 그대로 남겼다. 필름 롤·달력
  아래에 남아 있던 파란 그림자도 흰색으로 정리했고, 물체 경계에 남는 옅은 청록 헤어라인은
  디스필로 지웠다.
- **적응형 아트 비율 76% → 80%** — 여백이 살짝 넉넉했던 v2.4.1보다 아트를 키워 런처에서 더
  시원하게 채우되, 원형 마스크에 잘리는 화소가 없도록 맞췄다(아트 내부 최대 반경 0.914).
- `tools/generate_icons_from_art.py`가 `clear_blue_background()` → `clean_artwork()` →
  15개 레이어(5밀도 × 스퀘어클·원형·적응형) + `icon-preview.png` 생성까지 담당한다.
- versionCode 18 / versionName 2.4.2, 고정 서명 키 그대로. 웹앱 `APP_VERSION`과
  `getAppVersion()` 폴백도 2.4.2.
- 기능 변경 없음: v2.4.1의 알람·게임·권한·위젯·캘린더·Firebase 공유 그대로.

## v2.4.1 — 새 앱 아이콘 (PI 필름 롤 · 달력 · 느낌표)

- **새 런처 아이콘** — the artwork the user sent on 2026-11-23 (white rounded-square
  backdrop, light-blue disc, gold PI film roll, red calendar, two yellow exclamation
  marks) replaces the v1.3 icon. The black that showed outside the rounded-square
  mask in the source file is flood-filled with white (`#FFFFFF`) — the artwork's own
  backdrop colour — and the anti-aliased edge line is trimmed, so no black corner is
  left in any icon. `tools/generate_icons_from_art.py` does the cleanup, then writes
  the legacy squircle (`ic_launcher`), legacy circle (`ic_launcher_round`) and
  adaptive foreground layers for all five densities plus `icon-preview.png`.
- The adaptive background stays `#FFFFFF`; the foreground artwork sits at 75% of the
  108dp canvas (the visible art spans ~71% of the layer, the film roll / calendar /
  exclamation marks ~68%), so
  neither the circular nor the One UI squircle mask cuts into the artwork.
- versionCode 17 / versionName 2.4.1, same fixed signing key (`app/tsv-duty-fixed.p12`),
  so it installs over v2.4 without uninstalling. Web app: `APP_VERSION` and the
  `AndroidUpdater.getAppVersion()` fallback follow the same number.
- No functional change: everything from v2.4 (alarms, games, master permissions,
  widgets, device calendar, Firebase sharing) is untouched.

## v2.4 — 알람이 실제로 울리게 · 알람 구성 · 사진 확대 · 마스터 권한

- **알람이 안 울리던 문제의 근본 수정**
  - `AlarmManager.setAlarmClock(AlarmClockInfo(at, showPi), pi)`로 예약합니다. 정확한
    알람 권한(`SCHEDULE_EXACT_ALARM`)이 없어도 정시에 울리고, 상태바 알람 아이콘이
    뜨며, Doze·제조사 배터리 관리에서도 `setExactAndAllowWhileIdle`보다 잘 살아남습니다.
    (거부되는 기기만 기존 `setExactAndAllowWhileIdle`/`setAndAllowWhileIdle`로 폴백)
  - **`AlarmService`**(ForegroundService, `foregroundServiceType="mediaPlayback"`)를 새로
    두었습니다. `AlarmReceiver`가 알람 시각에 이 서비스를 즉시 시작하므로, 백그라운드
    액티비티 제한이나 전체 화면 알림 권한 때문에 `AlarmActivity`가 뜨지 못해도 소리와
    진동은 100% 납니다. 서비스는 `AudioAttributes.USAGE_ALARM`으로 재생하고 진동·WakeLock·
    자동 꺼짐·다시 울림을 맡습니다. 알람음은 ① 기기에서 고른 파일 ② 내장 멜로디
    ③ 기기 기본 알람음 순으로, 재생 실패 시 다음 소스로 넘어가 절대 무음이 되지 않습니다.
  - 알람 스트림 음량이 아주 낮으면 울리는 동안 70%로 올리고 끄면 원래 값으로 되돌립니다.
  - `syncNativeAlarms`가 근무 배지와 알람 근무를 맞출 때 `주특→주`, `야특→야`로 보정하고,
    알람에 `휴`가 들어 있으면 휴무일에도 울립니다(휴무 하드코딩 제외 삭제).
  - 알람 탭에 **다음 알람: O/O HH:MM · O근무** 표시와 **5초 뒤 테스트 알람** 버튼 추가.
- **알람 구성 UI** — 12시간제 오전/오후 + 시/분 −/+ 스테퍼와 분 프리셋, 적용 근무
  `주/야/비/휴`, 자동 꺼짐(설정 안 함·1·3·5분), 다시 울림(끄면 5분 뒤 최대 3번), 응원 문구/
  알람 메모, 진동, 알람음 토글, 음량 슬라이더(0~100%, 최소 5% 보장).
- **알람음** — 내장 4종 `res/raw/alarm_arcade.wav`(🕹️ 아케이드), `alarm_trumpet.wav`(🎺 나팔),
  `alarm_cuckoo.wav`(🐤 뻐꾸기), `alarm_aurora.wav`(✨ 오로라). 16-bit mono 22050 Hz, 약 8초,
  오프라인에서도 100% 재생. ▶ 미리듣기/정지(`AndroidAlarm.previewSound`/`stopPreview`).
  **내 기기 음악**(`AndroidAlarm.pickAudioFile`)은 SAF `ACTION_OPEN_DOCUMENT`로 열고
  `takePersistableUriPermission`으로 영구 읽기 권한을 받아 파일명·URI를 저장합니다.
- **알람 해제 게임 5종** — 사칙연산 · 따라쓰기(응원 문구를 그대로 입력) · 숫자 순서 터치
  (1~9/12/16) · 색깔 순서 터치(3/4/5색) · 랜덤. 난이도 쉬움/보통/어려움. 웹 미리보기와
  네이티브 `AlarmActivity`가 같은 규칙을 쓰고, 설정 모달에 **게임 미리 해보기**가 있습니다.
- **설비공유·게시판 사진 확대** — 사진을 누르면 전체화면 라이트박스가 열리고, 사진/버튼으로
  **1x ↔ 2x** 전환(2x에서는 끌어서 이동), 배경 터치·× 버튼·뒤로가기(`tsvHandleBack`)로 닫힙니다.
  계기판의 `0098` 같은 작은 숫자도 확인할 수 있습니다.
- **수정·삭제 권한 분리** — 앱 최초 실행 시 `deviceId`를 만들어 `localStorage`에 보관하고
  게시글·설비 기록에 `authorId`로 저장합니다. 설정 탭 `👑 마스터 관리자 권한`에서 관리자
  코드를 인증하면 `isMaster`가 이 기기에 저장되어 **모든 글**을 수정·삭제할 수 있고, 해제하면
  **본인 글(authorId 일치)**만 가능합니다. 타인 글·`authorId` 없는 예전 글은 버튼이 숨겨지고
  핸들러에서도 "본인이 작성한 글만 수정·삭제할 수 있습니다"로 막습니다. 코드 입력은
  `type="password"`이며 화면에 노출하지 않습니다.
- **버전** — `APP_VERSION` `2.4`, `versionCode 16`, `versionName "2.4"`, `getAppVersion()` 대체값 `2.4`.
  v2.3.1의 2D 슬라이드 월 전환·최고 버전 업데이터·오늘 박스·메모 시안성·날씨 토글·연일 캘린더·
  위젯 5종·Firebase 공유는 그대로입니다.

## v2.3.1 — 인앱 업데이트 확인 버그 수정

- **증상** — v2.2(과 v2.3) 앱의 설정 탭 `업데이트 확인`이 v2.3이 이미 공개돼 있는데도
  "현재 최신 버전입니다"라고 답했습니다.
- **원인** — GitHub Releases API 목록은 `created_at` 내림차순인데, v2.3 릴리즈의
  `created_at`이 `2026-11-21T21:30:29Z`로 찍혀 목록 **8번째(맨 끝)**로 밀렸고 맨 앞에는
  v2.2가 남아 있었습니다. `checkAppUpdate`가 목록의 **첫 번째 매칭만** 보는
  `.find(...)`였기 때문에 v2.2를 최신으로 판단했습니다.
- **수정** — 순수 함수 `pickLatestDutyRelease(list)`가 조건(draft 아님·prerelease 아님·
  `duty-calendar-v*` 태그·`.apk` 에셋 보유)에 맞는 릴리즈를 **모두 추린 뒤**
  `compareAppVersion`으로 **버전 번호가 가장 높은** 릴리즈를 고릅니다. 목록 순서와
  무관하며, 같은 버전이 둘이면 더 최근에 만들어진(=목록 앞) 쪽을 씁니다.
  `compareAppVersion`은 `2.3.1 > 2.3 > 2.2`처럼 세 자리 비교가 됩니다.
- **버전** — `APP_VERSION` `2.3.1`, `getAppVersion()` 대체값 `2.3.1`.
  v2.3의 2D 가로 슬라이드 월 전환과 v2.2 기능은 그대로입니다.
- versionCode 15 / versionName 2.3.1, same fixed signing key.

## v2.3 — 2D 가로 슬라이드(뷰페이저) 월 전환

- **3D 책 넘기기 제거** — `.book-stage`(`perspective: 1800px`)와 `bookTurnNext` /
  `bookTurnPrev` / `.book-leaf-next` / `.book-leaf-prev`(`rotateY`) 및 `flipAnim` 상태를
  전부 걷어 냈습니다. 이제 원근감·회전 없이 평면 2D로만 넘어갑니다.
- **3패널 수평 트랙** — 달력은 항상 `[이전 달 | 현재 달 | 다음 달]` 세 패널을 한 줄로
  붙인 `.month-track`(폭 100%, 각 패널 `flex: 0 0 100%`)이고, 화면에는 가운데가
  `translate3d(-100%, 0, 0)`으로 놓입니다. 패널마다 요일 헤더(`일 월 화 수 목 금 토`)와
  42칸 그리드를 함께 그려서, 밀면 헤더와 그리드가 통째로 옆에서 따라 들어옵니다.
  패널 경계는 1px 옅은 구분선(`#eef2f7`)과 아주 부드러운 그림자뿐입니다.
- **실시간 터치 드래그** — `touchstart` 뒤 첫 이동에서 수평/수직을 판별해 수평일 때만
  달력 넘김을 가로채고(`touch-action: pan-y`, `overflow: hidden`), 손가락 이동량 `dx`를
  `translate3d(calc(-100% ± dx px))`로 그대로 반영해 인접 달이 실시간으로 밀려 들어옵니다.
  한 패널 폭을 넘겨 끌어도 빈 공간이 보이지 않게 `dx`를 ±폭으로 제한합니다.
- **스냅** — 컨테이너 폭의 22% 이상 끌었거나 26px 이상을 0.45px/ms보다 빠르게 플릭하면
  `transform 280ms cubic-bezier(0.22, 1, 0.36, 1)`로 한 칸 스냅한 뒤 월 상태를 바꿉니다
  (`ReactDOM.flushSync`로 패널 교체와 트랙 리셋을 한 프레임에 처리). 그보다 짧으면
  같은 곡선으로 제자리에 복귀합니다.
- **버튼·오늘 이동** — 상단 `<` / `>` 와 `오늘` 버튼도 같은 방향의 2D 슬라이드로 월을
  바꿉니다(여러 달 점프는 한 칸 밀고 도착 월로 교체). 넘김 효과음 `playSfx("swipe")`는
  한 번만 울리고, 스와이프 직후 450ms 안의 날짜 칸 클릭은 무시해 실수로 상세창이
  열리지 않습니다.
- versionCode 14 / versionName 2.3, same fixed signing key.

## v2.2 — 오늘 날짜 박스 · 메모 시안성 · 🌤️ 날씨 보기

- **오늘 칸 표시** — 달력에서 오늘 날짜 칸 전체에 파란 테두리 박스(`ring-2 ring-inset
  ring-blue-600` + `bg-blue-50/50`)를 두르고, 날짜 숫자는 파란 사각 배지
  (`bg-blue-600 text-white`)로 바꿨습니다. 선택한 날짜는 옅은 슬레이트 링
  (`ring-1 ring-inset ring-slate-400 bg-slate-50/60`)이라 오늘 박스가 가장 또렷합니다.
  앱을 열면 항상 이번 달·오늘에서 시작합니다.
- **메모 시안성** — 셀 좌우 패딩을 `px-0.5`로 줄이고, 메모·폰 일정 바를 `text-[10px]
  font-bold tracking-[-0.04em]`로 키워 `GTA6 open` 같은 영문/한글 메모가 7열 화면에서도
  잘리지 않습니다. 길어도 두 줄까지(`-webkit-line-clamp: 2`) 보여 줍니다.
- **🌤️ 날씨 보기** — 상단 `같이 보기` 줄에 `[🌤️ 날씨]` 토글을 넣었습니다. 켜면 각 날짜
  칸의 메모·공유일정·폰 일정 대신 날씨 아이콘(☀️/⛅/☁️/🌧️/❄️/⛈️)과 최저/최고 기온
  (`1°/11°`)이 나옵니다. 값은 `localStorage`(`todaywork_v33_showWeather`)에 저장됩니다.
- **예보 받아오기** — 천안 백석동(36.8297, 127.1255) 기준 오늘부터 10일치를 Open-Meteo
  무료 예보에서 받습니다. `file://` WebView의 CORS 제약을 피하려고 새 네이티브 브리지
  **`window.AndroidWeather.fetch(url)`** 가 직접 받아 `window.__tsvWeatherApply(json)`로
  돌려주고, 브리지가 없거나 실패하면 페이지의 `fetch` → 마지막 캐시(30분) 순으로 버팁니다.
- versionCode 13 / versionName 2.2, same fixed signing key.

The GitHub Actions workflow (`.github/workflows/build-apk.yml`) builds a debug
APK on every push. Locally the same thing is `gradle assembleDebug` with JDK 17
and the Android SDK installed.

- applicationId: `com.tsv.dutycalendar` (installs beside `TSV 근무표`)
- versionCode 15 / versionName 2.3.1
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
artwork sits at 80% of the 108dp canvas (v2.4.2) with every visible mark inside the
circle inscribed in it, so neither a circular (One UI) nor a squircle mask clips it.
- The `<monochrome>` themed-icon layer was dropped: a tint silhouette of this
photographic artwork loses the film roll's highlights, so themed icons simply
fall back to the full-colour icon.
- Web app: `assets/index.html` refreshed byte-for-byte from the web build.

## Icons

`tools/generate_icons_from_art.py SOURCE_IMAGE [out_res_dir] [--preview PATH]`
builds the launcher icons from the supplied artwork and writes legacy, round and
adaptive foreground layers for all five densities, plus a preview sheet. It does
not redraw the artwork: it (1) flood-fills any black backdrop outside the artwork's
rounded square with white and trims the anti-aliased edge line, (2) resizes with
LANCZOS, (3) centres the artwork at 94% (squircle), 88% (circle) or 80% (adaptive,
inside the 72dp safe zone) of the canvas, and (4) applies the squircle/circle mask
for the legacy layers. The adaptive background is `#FFFFFF`
(`values/colors.xml` → `ic_launcher_background`).

The previous from-scratch artwork generator is kept in git history
(`tools/generate_icons.py` at v1.2).
