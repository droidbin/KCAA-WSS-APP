# KCAA-WSS-APP 프로젝트 규칙 (AGENTS.md)

이 문서는 **요리광주 업무지원포털 모바일 앱(`KCAA-WSS-APP`)** 프로젝트 작업 시 에이전트가 항상 준수해야 하는 필수 규칙을 정의합니다.

## 1. 버전 관리(Versioning) 정책
- **버전 표기 형식**: `Major.Minor` 2자리 체계 (`1.0`, `1.1`, `1.2`, ..., `2.0`)를 사용한다 (`1.0.x` 같은 3자리 패치 번호는 사용하지 않음).
  - **초기 정식 릴리스**: `1.0`
  - **마이너 업데이트**: `+0.1` 단위 증가 (`1.1`, `1.2`, `1.3` ...)
  - **메이저 업데이트**: `+1.0` 단위 증가 (`2.0`, `3.0` ...)
- **버전 코드(`versionCode`)**: Google Play 및 기기 덮어쓰기 설치 호환성을 위해 매 릴리스마다 `+1`씩 단조 증가시킨다.
- **산출물 파일명**:
  - 정식 릴리스 APK: `kcook_wss_v{versionName}.apk` (예: `kcook_wss_v1.0.apk`)
  - 디버그 APK: `kcook_wss_v{versionName}_debug.apk`
  - 스토어 번들: `app-release.aab`

## 2. 릴리스 노트 및 API 통신 시 UTF-8 인코딩 강제 규칙
- Windows PowerShell(5.1) 환경에서 GitHub Release 생성/수정 등 외부 API 호출이나 파일 입출력 시 **한글 및 이모지가 깨지지 않도록 반드시 명시적인 UTF-8 인코딩**을 사용한다.
- `.ps1` 스크립트 내부에 한글 문자열 리터럴을 직접 하드코딩하여 PowerShell 기본 인코딩(CP949/ANSI)으로 파싱되게 하지 않는다.
- 반드시 아래 표준 방식을 사용한다:
  1. `write_file` 도구로 릴리스 제목/본문 텍스트 파일을 생성한다 (기본 UTF-8 저장).
  2. PowerShell에서 `[System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)`로 읽어온다.
  3. JSON 페이로드 전송 시 `[System.Text.Encoding]::UTF8.GetBytes($jsonPayload)` 바이트 배열로 변환하고 `-ContentType "application/json; charset=utf-8"` 헤더를 명시한다.
