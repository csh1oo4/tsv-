# 📁 SynoFile Pro (com.choi.filemanager)

시놀로지 NAS와 안드로이드 폰의 사진·영상을 한 화면에서 보고 정리하는 앱.
v1.2 / versionCode 3 — v1.1의 화면에 **WebDAV 접속**(HTTP 5005 / HTTPS 5006)을 더했다.
DSM 로그인이 막힌 NAS도 WebDAV 계정으로 붙을 수 있다.

## 무엇을 하나요

- **☁️ 시놀로지 NAS** — 두 가지 접속 방식.
  - **DSM (File Station)** — DSM 계정 로그인(2단계 인증 OTP, 자체 서명 인증서 허용).
  - **WebDAV** — 주소에 `:5005`(http)를 적으면 자동 선택, `:5006`이면 https. Basic 인증으로
    PROPFIND/MKCOL/MOVE/COPY/PUT/DELETE를 직접 말한다. 포트를 비우면 `http://…:5005`.
  공유 폴더 → 하위 폴더 탐색, 썸네일, 원본 보기, 영상 재생(Range 스트리밍), 업로드/다운로드,
  NAS 내부 복사·이동·이름 변경·삭제·새 폴더.
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
    NasBridge.java        브리지 본체: DSM File Station 클라이언트 + WebDAV 분기
                          (SYNO.API.Auth/List/Thumb/Download/Upload/CreateFolder/
                          Rename/Delete/CopyMove, 프로토콜별 저장·자동 로그인)
    DavClient.java        WebDAV 동작 (PROPFIND 목록·MKCOL·MOVE/COPY·PUT·GET·DELETE)
    DavXml.java           PROPFIND 본문과 207 Multi-Status 파서 (순수 Java, JVM 테스트)
    HttpWire.java         소켓 HTTP/1.1 클라이언트 — PROPFIND 등을 위해 직접 구현
    LocalFileBridge.java  폰 저장소 (목록/생성/이름변경/삭제/복사/이동/공유)
    LocalServer.java      루프백 HTTP 서버 (/f /lt /s /nt, Range 지원)
    Core.java             공용 순수 헬퍼 (JVM 단위 테스트 대상)
    CryptoStore.java      자동 로그인 비밀번호를 Android Keystore로 암호화
```

### 왜 루프백 서버인가

`file://` 페이지는 NAS에 직접 요청할 수 없습니다(CORS·자체 서명 인증서). 그래서 모든 NAS
호출은 `NasBridge`가 대신하고, 화면에 띄우는 사진·영상 바이트만 `127.0.0.1`의 임시 포트
(토큰 보호, Range 지원)로 넘겨 `<img>`·`<video>`가 그대로 읽게 합니다.

DSM 모드는 `HttpURLConnection`을 쓰고, WebDAV 모드는 `HttpWire`(직접 만든 소켓 HTTP/1.1
클라이언트)를 씁니다 — 안드로이드의 `HttpURLConnection`은 표준 6개 메서드 외에는
`ProtocolException`을 던져 PROPFIND/MKCOL/MOVE/COPY를 보낼 수 없기 때문입니다.
DSM Download API는 Range를 지원하지 않아 NAS 영상을 한 번 캐시에 받아 재생하지만,
WebDAV는 Range를 그대로 넘겨 바로 스트리밍합니다. WebDAV에는 썸네일 API가 없어 이미지를
한 번 받아(24MB 이하) 축소·캐시합니다.

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

- 태그: `file-manager-v1.2`
- 에셋: `SynoFile-Pro-v1.2.apk`
