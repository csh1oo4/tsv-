# TSV secret 근무표 — Android APK

「TSV secret 근무표」 웹 앱(`index.html`)을 그대로 담은 안드로이드 WebView 앱입니다.
앱은 인터넷 없이 동작하며, 근무표 데이터는 기기 안(`localStorage` + 네이티브 백업)에만 저장됩니다.

## 구성

| 항목 | 값 |
| --- | --- |
| 앱 이름 | TSV secret 근무표 |
| 패키지 | `com.tsvsecret.schedule` |
| compileSdk / targetSdk | 34 / 34 |
| minSdk | 24 (Android 7.0) |
| AGP / Gradle / Java | 8.3.2 / 8.7 / 17 |
| 버전 | versionCode 2 / versionName 1.1 |

## 웹 앱 기능 연결 상태

- `assets/index.html` — 웹 앱 원본 그대로 (수정 금지, `/workspace/tsv-secret-schedule/index.html` 사본)
- `assets/bridge.js` — 네이티브 보조 스크립트 (페이지 로드 후 주입)
  - `data:` / `blob:` 다운로드 → 기기 `다운로드` 폴더 저장 (JSON 내보내기, 이미지 저장)
  - 공유 코드 복사 → 네이티브 클립보드
  - `localStorage` → SharedPreferences 백업 (WebView 저장소가 비워졌을 때 자동 복원)
  - 테마(주간/야간)에 맞춰 상태바·내비게이션바 색상 동기화
    — 상태바는 헤더색(주간 `#1d4ed8`, 야간 `#6d28d9`)에 흰 아이콘,
    내비게이션바는 배경색(주간 `#f1f5fb`, 야간 `#f3f0ff`)에 어두운 아이콘. 아이콘 밝기는 막대 색의 휘도로 자동 판단
  - `display:none` 파일 입력을 WebView가 열 수 있도록 보정 (JSON 가져오기)
- 뒤로 가기: 열린 모달을 먼저 닫고, 스크롤을 맨 위로 올린 뒤에만 앱 종료

## 빌드

GitHub Actions(`.github/workflows/build-apk.yml`)가 `push` / 수동 실행 시마다
`gradle assembleDebug`로 APK를 만들고 아티팩트로 올립니다.

```bash
gradle assembleDebug   # 로컬에 Android SDK + JDK 17이 있을 때
```

APK 경로: `app/build/outputs/apk/debug/app-debug.apk`

## 아이콘

`tools/generate_icons.py`로 레거시 런처 PNG(mdpi~xxxhdpi)를 생성합니다.
API 26+는 `mipmap-anydpi-v26/ic_launcher.xml`(적응형 아이콘, 모노크롬 포함)을 사용합니다.
