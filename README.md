# 광주 업무지원포털 Android 애플리케이션 (v1.0.0)

`https://gwangju-work-portal.droidbin.chatgpt.site/` 업무지원포털을 위한 **Custom Android WebView 기반 하이브리드 애플리케이션**입니다.

## 주요 아키텍처 및 적용 기능

1. **최적화된 Custom WebView 엔진 (`MainActivity.kt`)**
   - DOM Storage, 쿠키 영속화(`CookieManager.flush()`), 다중 창(`window.open` / `target="_blank"`) 제어
   - 시스템 폰트 크기 변경 시 웹 레이아웃 무너짐 방지(`textZoom = 100`)
   - User-Agent 커스텀 식별자(`KCAAWssAndroid/1.0.0`) 추가

2. **네이티브 UX & 신규 브랜드 아이덴티티 통합**
   - **신규 로고 및 아이콘 적용**: 대한민국 검찰청 스타일의 고해상도 심볼 로고를 바탕으로 Adaptive Icon(`ic_launcher`, `ic_launcher_round`), Android 12+ SplashScreen(`ic_splash_logo`), 웹 상단 브랜드 로고에 일관 적용
   - **Splash Screen API**: Android 12+ 표준 스플래시 화면 적용 및 초기 웹페이지 로딩 완료 시점 연동
   - **Swipe-to-Refresh**: 최상단 스크롤(`scrollY == 0`) 상태에서만 당겨서 새로고침이 동작하여 웹 내부 스크롤과 충돌 방지
   - **네이티브 오프라인/에러 화면**: 네트워크 단절 또는 메인 프레임 로드 실패 시 기본 브라우저 에러 페이지 대신 네이티브 재시도/홈 이동 UI 제공
   - **스마트 뒤로가기 제어 (`OnBackPressedDispatcher`)**: WebView 히스토리 탐색 후 더 이상 뒤로 갈 페이지가 없을 때 2초 내 더블 탭 종료 안내

3. **앱 설치 UX 및 사용자 편의 중심 설정 메뉴 개편**
   - **앱 설치하기 버튼 자동 은닉**: 네이티브 앱 및 단독 실행(Standalone) 환경에서 불필요한 '앱 설치하기' 버튼 및 홈 화면 추가 안내 문구를 자동 감지하여 완전 숨김 처리
   - **사용자 편의 최우선 설정 메뉴 (`⚙️ 설정`)**:
     - 기존 혼잡했던 '앱 설치 · 알림 설정' 및 '관리자 메뉴'를 직관적인 모달 레이아웃으로 통합 개편
     - **공지 푸시 알림 제어**: 실시간 알림 수신 상태 뱃지, 원클릭 켜기/끄기, Android 시스템 기기 알림 설정 바로가기 제공
     - **앱 환경 관리**: 화면 새로고침, 원클릭 캐시 및 세션 정리, 앱 버전 정보 확인
     - **업무 바로가기**: 의무교육 수강, 발주요청서 작성, 학과별 대시보드, 카카오톡 1:1 상담
     - **정돈된 관리자 메뉴**: 관리자 로그인 상태에 따라 URL 관리, Slack 알림, 공지 발송 관리, 휴지통, 인사관리, 로그아웃을 깔끔한 카드 리스트로 제공

4. **파일 업로드 & 다운로드 완벽 지원**
   - **파일 업로드 (`onShowFileChooser`)**: `<input type="file">` 호출 시 **즉시 카메라 촬영(`FileProvider`)** 또는 **내장 파일/갤러리 선택**을 동시에 제공하는 Chooser 연동
   - **파일 다운로드 (`PortalDownloadHelper.kt`)**: WebView의 로그인 세션 쿠키(`CookieManager`)와 `User-Agent`를 Android 시스템 `DownloadManager`에 주입하여 인증이 필요한 포털 첨부파일도 끊김 없이 다운로드

5. **웹 ↔ 네이티브 양방향 브릿지 (`PortalJsBridge.kt`)**
   - 웹 포털의 JavaScript에서 `window.AndroidPortalBridge` 객체를 통해 네이티브 기능 호출 가능:
     - `window.AndroidPortalBridge.getAppInfo()` : 앱 버전 및 기기 정보(JSON) 반환
     - `window.AndroidPortalBridge.isNativeApp()` : 네이티브 앱 환경 식별(true)
     - `window.AndroidPortalBridge.getLogoBase64()` : 신규 로고 base64 데이터 반환
     - `window.AndroidPortalBridge.openNotificationSettings()` : 기기 시스템 알림 설정 화면 바로가기
     - `window.AndroidPortalBridge.showToast("메시지")` : 네이티브 토스트 출력
     - `window.AndroidPortalBridge.reloadPortal()` : 포털 새로고침
     - `window.AndroidPortalBridge.clearSessionAndCache()` : 로그아웃 시 웹뷰 쿠키/캐시 완전 초기화

## 빌드 및 실행 방법

1. **Android Studio**에서 프로젝트 루트 폴더(`KCAA-WSS-APP`)를 엽니다.
2. Gradle Sync 완료 후 에뮬레이터 또는 실제 기기를 연결하고 **Run (`Shift + F10`)**을 실행합니다.
3. APK 직접 생성 시:
   - **Build > Build Bundle(s) / APK(s) > Build APK(s)** 메뉴를 통해 배포용/테스트용 APK를 즉시 추출할 수 있습니다.
