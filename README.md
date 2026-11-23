# 📁 SynoFile Pro (com.choi.filemanager)

시놀로지 NAS(DSM File Station)와 안드로이드 폰의 사진·영상을 한 화면에서 보고 정리하는 앱.
v1.1 / versionCode 2 — v1.0의 네이티브 엔진 위에 새 디자인(다크 듀얼 스토리지 UI)을 얹었다.

## 무엇을 하나요

- **☁️ 시놀로지 NAS** — DSM 계정 로그인(2단계 인증 OTP, 자체 서명 인증서 허용), 공유 폴더 →
  하위 폴더 탐색, 썸네일, 원본 보기, 영상 재생, 업로드/다운로드, NAS 내부 복사·이동·이름
  변경·삭제·새 폴더.
- **📱 내 폰 저장소** — `/storage/emulated/0` 이하 전체 탐색, 카메라·다운로드·사진 등 빠른 이동,
  사진/영상 그리드·리스트, 새 폴더·이름 변경·삭제·복사·이동·공유·외부 앱 열기.
- **🔁 양방향 전송** — 폰 → NAS 업로드, NAS → 폰 다운로드. 전송 작업함에 진행률·완료·실패가
  실시간으로 쌓이고, 상단 배지와 미니 토스트로도 보인다.
- **🖼️ 뷰어** — 전체 화면 사진/영상, 핀치 줌·드래그·더블탭·좌우 넘기기, 저장·이름 변경·삭제.
- **⭐ 즐겨찾기 퀵바**, 검색, 필터, 정렬(날짜·이름·크기), 그리드/리스트 전환.

## 구조

```
app/src/main/assets/index.html   화면 골격 + CSS (외부 CDN 없음)
app/src/main/assets/core.js      순수 로직 (경로, 정렬, 필터, 포맷, 서버 주소, 전송 상태)
app/src/main/assets/icons.js     인라인 SVG 아이콘 세트 (오프라인 안전)
app/src/main/assets/bridge.js    네이티브 브리지를 Promise로 감싸는 계층
app/src/main/assets/app.js       화면 동작 전체

app/src/main/java/com/choi/filemanager/
    MainActivity.java     WebView 셸, 권한, FileProvider, 브리지 등록, 뒤로가기
    NasBridge.java        DSM File Station 클라이언트 (SYNO.API.Auth/List/Thumb/
                          Download/Upload/CreateFolder/Rename/Delete/CopyMove)
    LocalFileBridge.java  폰 저장소 (목록/생성/이름변경/삭제/복사/이동/공유)
    LocalServer.java      루프백 HTTP 서버 (/f /lt /s /nt, Range 지원)
    Core.java             공용 순수 헬퍼 (JVM 단위 테스트 대상)
    CryptoStore.java      자동 로그인 비밀번호를 Android Keystore로 암호화
```

### 왜 루프백 서버인가

`file://` 페이지는 NAS에 직접 요청할 수 없습니다(CORS·자체 서명 인증서). 그래서 모든 DSM
호출은 `NasBridge`가 `HttpURLConnection`으로 대신하고, 화면에 띄우는 사진·영상 바이트만
`127.0.0.1`의 임시 포트(토큰 보호, Range 지원)로 넘겨 `<img>`·`<video>`가 그대로 읽게 합니다.
NAS 영상은 DSM Download API가 Range를 지원하지 않으므로 한 번 캐시에 받아 재생합니다.

### 왜 오프라인 안전한가

디자인 시안은 `cdn.tailwindcss.com`·`unpkg.com/lucide`·Google Fonts에 의존했지만, 이 앱은
집 안 Wi-Fi(인터넷 없음)에서도 켜져야 합니다. 그래서 Tailwind는 같은 색·간격을 옮긴 정적
CSS로, Lucide는 `icons.js`의 인라인 SVG로, 폰트는 시스템 폰트로 바꿨습니다. `index.html`에는
바깥 URL이 하나도 없습니다(테스트가 검사합니다).

## 빌드

GitHub Actions(`Build APK`)가 모든 브랜치 푸시에서 JS 테스트, Java 단위 테스트, APK 빌드,
서명 검증까지 수행합니다. 로컬에는 JDK/Android SDK가 없어도 됩니다.

```
node tools/tests/run.js        # JS 로직 + 구조 + DOM 스모크 테스트 (CI)
python3 tools/tests/run.py     # 같은 테스트를 node 없이 V8(py_mini_racer)로
gradle testDebugUnitTest       # Core 단위 테스트 (CI)
gradle assembleDebug           # APK (CI)
```

## 서명

`app/filemanager-fixed.p12`(PKCS12, alias `filemanager`)를 저장소에 두고 `build.gradle`에
연결했습니다. 빌드가 끝나면 `verifyApkSignature`가 `apksigner --print-certs`로 v1.0과 같은
인증서(SHA-256 `42297011297a…`)인지 확인하고, 다르면 빌드를 실패시킵니다.

## 릴리즈

- 태그: `file-manager-v1.1`
- 에셋: `SynoFile-Pro-v1.1.apk`
