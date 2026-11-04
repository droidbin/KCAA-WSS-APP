# 요리광주 업무지원포털 모바일 앱 (v1.1)

[![Release](https://img.shields.io/badge/Release-v1.1-blue.svg)](https://github.com/droidbin/KCAA-WSS-APP/releases/tag/v1.1)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0+-green.svg)](https://developer.android.com)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-36-orange.svg)](https://developer.android.com)

**요리광주 업무지원포털 모바일 앱**은 코리아요리아트아카데미광주 임직원 및 강사진의 원활한 업무 수행과 행정 편의를 지원하기 위해 제작된 **공식 Android 하이브리드 애플리케이션**입니다.

포털 서비스 URL: `https://gwangju-work-portal.pages.dev/`

---

## 📱 앱 설치 및 다운로드 안내

사내 메신저(카카오톡, 슬랙) 및 사내 인트라넷을 통해 즉시 스마트폰에 설치할 수 있습니다.

| 다운로드 파일 | 파일명 | 설명 |
| :--- | :--- | :--- |
| **정식 릴리스 APK** | **[`kcook_wss_v1.1.apk`](https://github.com/droidbin/KCAA-WSS-APP/releases/download/v1.1/kcook_wss_v1.1.apk)** | 사내 배포용 공식 릴리스 서명 설치 파일 |
| **스토어 배포용 번들** | **[`app-release.aab`](https://github.com/droidbin/KCAA-WSS-APP/releases/download/v1.1/app-release.aab)** | Google Play Console 등록용 App Bundle |

> 📌 **첫 설치 시 안내 (사내 배포 앱)**:
> 1. 링크 클릭 후 다운로드 진행 시 "유해한 파일일 수 있음" 경고 발생 시 **[무시하고 다운로드]** 선택
> 2. 다운로드 완료 후 파일 실행 ➔ **[설치]** 클릭 (필요 시 '이 출처의 앱 허용' 활성화)
> 3. 앱 실행 후 새 공지 알림 수신을 위해 **[알림 허용]** 선택

---

## 🔢 버전 관리(Versioning) 정책

본 프로젝트는 직관적인 `Major.Minor` 2자리 버전 체계를 따릅니다.

| 구분 | 증가 단위 | 예시 | 기준 |
| :--- | :--- | :--- | :--- |
| **초기 정식 릴리스** | `1.0` | `v1.0` | 최초 정식 배포 버전 |
| **마이너 업데이트** | `+0.1` | `v1.1`, `v1.2`, `v1.3` ... | 기능 개선, UI/UX 보완, 버그 수정 및 소규모 기능 추가 |
| **메이저 업데이트** | `+1.0` | `v2.0`, `v3.0` ... | 대규모 아키텍처 개편, 핵심 신규 모듈 도입, 대규모 UI 리뉴얼 |

* Android 내부 빌드 번호(`versionCode`)는 스토어 및 기기 업데이트 호환성을 위해 매 릴리스마다 `+1`씩 단조 증가합니다.

---

## 🚀 주요 핵심 기능 및 아키텍처

### 1. 최적화된 WebView 엔진 (`MainActivity.kt`)
- **보안 및 스토리지**: DOM Storage, 세션 쿠키 영속화(`CookieManager.flush()`), 다중 창(`window.open` / `target="_blank"`) 제어
- **레이아웃 보호**: 사용자 시스템 폰트 크기 변경에 따른 웹 UI 깨짐 방지 (`textZoom = 100`)
- **Android 15 Edge-to-Edge 지원**: 상태바 및 시스템 네비게이션 바와의 겹침 방지 (`ViewCompat.setOnApplyWindowInsetsListener`)
- **User-Agent 식별자**: 안드로이드 전용 앱임을 식별할 수 있는 브라우저 에이전트 주입 (`KCAAWssAndroid/1.1`)

### 2. 브랜드 디자인 및 가독성 최적화
- **상단 헤더 로고 화이트 반전**: 어두운 네이비 배경(`header.top`) 위에서 로고가 선명하게 돋보이도록 순백색 반전 필터 및 드롭 섀도우 적용
- **서브 시스템 로고 호환**: 수강생 증명서 발급 등 흰색 배경 페이지의 헤더에서는 고유 다크 엠블럼을 유지하도록 자동 판별
- **Adaptive Launcher Icon**: 안드로이드 표준 규격의 라운드 및 스퀘어 아이콘 지원

### 3. 사용자 중심의 설정 모달 (`⚙️ 설정`)
- **실시간 공지 알림 센터**: 기기 시스템 알림 허용 여부 상태 뱃지, 원클릭 알림 권한 요청, 테스트 알림 발송, 시스템 설정 바로가기 제공
- **빠른 업무 바로가기**:
  - 🎓 법정의무교육 온라인 수강 바로가기
  - 📋 물품 및 수업자재 발주요청서 작성
  - 💬 카카오톡 1:1 업무지원 상담 채널
  - 📄 수강생 증명서 발급 시스템 다이렉트 연동
- **앱 환경 관리**: 포털 원클릭 새로고침 및 캐시/세션 초기화 기능, 앱 버전(`v1.0`) 자동 연동 표시

### 4. 네이티브 파일 입출력 완벽 연동
- **파일 업로드 (`WebChromeClient.onShowFileChooser`)**: `<input type="file">` 동작 시 **카메라 즉시 촬영(`FileProvider`)**과 **갤러리/파일 탐색기** 동시 지원
- **첨부파일 다운로드 (`PortalDownloadHelper.kt`)**: 세션 인증 쿠키를 안드로이드 시스템 `DownloadManager`에 주입하여 권한이 필요한 첨부파일도 안전하게 다운로드

### 5. Google Play Protect 및 배포 자동화
- **정식 릴리스 서명**: `release-upload-key.jks` (SHA-256) 정식 서명 적용
- **R8 / ProGuard 코드 난독화**: 릴리스 바이너리 경량화 및 앱 무결성 검증
- **패키징 자동화**: 빌드 시 `kcook_wss_v{versionName}.apk` 형식으로 자동 명명

---

## 🛠️ 빌드 및 패키징 방법

### 개발 환경
- **Android Studio**: Ladybug / Jellyfish 이상 권장
- **JDK**: Java 17
- **Gradle**: 8.9 (Kotlin DSL)
- **Compile SDK**: 36 (Android 16)
- **Min SDK**: 26 (Android 8.0 Oreo)

### 빌드 명령어
```bash
# 1. 정식 릴리스 APK 빌드 (kcook_wss_v1.1.apk 생성)
./gradlew assembleRelease

# 2. Google Play Store 배포용 번들 빌드 (app-release.aab 생성)
./gradlew bundleRelease

# 3. 디버그 호환용 APK 빌드 (kcook_wss_v1.1_debug.apk 생성)
./gradlew assembleDebug
```

출력 파일 경로:
- Release APK: `app/build/outputs/apk/release/kcook_wss_v1.1.apk`
- Release AAB: `app/build/outputs/bundle/release/app-release.aab`
- Debug APK: `app/build/outputs/apk/debug/kcook_wss_v1.1_debug.apk`
