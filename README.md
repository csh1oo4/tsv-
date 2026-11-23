# 📁 NAS 파일매니저 (com.choi.filemanager)

시놀로지 NAS(DSM File Station)와 안드로이드 폰의 사진·영상을 한 화면에서 보고 정리하는 앱.
v1.0 / versionCode 1.

## 무엇을 하나요

- **📱 내 폰** — `/storage/emulated/0` 이하 전체 탐색, 카메라·다운로드·사진 등 빠른 이동,
  사진/영상 썸네일 갤러리, 새 폴더·이름 변경·삭제·복사·이동·공유·외부 앱 열기.
- **🖥️ 시놀로지** — DSM 계정 로그인(2단계 인증 OTP 지원, 자체 서명 인증서 허용 옵션),
  공유 폴더 → 하위 폴더 탐색, 썸네일, 원본 보기, 영상 재생, 업로드/다운로드, NAS 내부
  복사·이동·이름 변경·삭제·새 폴더.
- **⭐ 즐겨찾기** — 자주 여는 폴더를 ★로 저장.
- **⚙️ 설정** — 서버 목록, 자동 로그인, 폰 저장소 권한, 보기 방식, 썸네일 캐시.

## 구조

```
app/src/main/assets/index.html   화면 골격 + CSS
app/src/main/assets/core.js      순수 로직 (경로, 정렬, 필터, 포맷) — Node로 테스트
app/src/main/assets/bridge.js    네이티브 브리지를 Promise로 감싸는 계층
app/src/main/assets/app.js       화면 동작 전체

app/src/main/java/com/choi/filemanager/
    MainActivity.java     WebView 셸, 권한, FileProvider, 브리지 등록
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

## 빌드

GitHub Actions(`Build APK`)가 모든 브랜치 푸시에서 JS 테스트, Java 단위 테스트, APK 빌드,
서명 검증까지 수행합니다. 로컬에는 JDK/Android SDK가 없어도 됩니다.

```
node tools/tests/run.js        # JS 로직 + 구조 테스트
gradle testDebugUnitTest       # Core 단위 테스트
gradle assembleDebug           # APK
```

## 서명

`app/filemanager-fixed.p12`(PKCS12, alias `filemanager`)를 저장소에 두고 `build.gradle`에
연결했습니다. 같은 인증서로 계속 빌드되므로 v1.1 이후는 기존 설치 위에 덮어쓰기 업데이트가
됩니다. **이 파일을 지우거나 새로 만들면 업데이트가 깨집니다.**

## 릴리즈

- 태그: `file-manager-v1.0`
- 에셋: `NAS-FileManager-v1.0.apk`
