# 포털 쪽 전달 자료: 안드로이드 앱 알림 연동 결과 (앱 v1.6.0)

대상: 포털 저장소(`droidbin/gwangju-work-portal`, 기준 커밋 `e865050`) 작업 세션
작성 기준: 앱 저장소 `droidbin/KCAA-WSS-APP` `main` `7456a38`, 릴리스 v1.6.0(versionCode 16)

아래 내용을 포털 작업 세션의 첫 메시지로 붙여넣을 수 있다. 앱 소스는 읽기만 하고, 서버 계약과 다른 점이 있으면 임의로 고치지 말고 질문한다.

---

## 1. 앱이 현재 서버에 하는 호출 (계약 확인용)

| 호출 | 시점 | 내용 |
|---|---|---|
| `POST /api/push/device` | 실행·재개, 토큰 갱신, 종류 스위치 변경 | `deviceId, token, platform:"android", appVersion:"1.6.0", notificationsEnabled, types{…}` — 앱 권한으로 다룰 수 있는 종류만 `types`에 포함 |
| `GET /api/push/preferences` | 같은 내용이 등록되어 있을 때 6시간마다, 관리자 세션이 끝난 기기가 일반직원으로 확인될 때 | `Cookie: kcaa_app_device=<id>`만 첨부. `device:false`면 다시 등록, `admin`은 관리자 역할 유지 여부로만 사용 |
| 쿠키 `kcaa_app_device` | 앱 시작 시 | 앱이 포털·발주·증명서·의무교육·인사 5개 도메인에 설정(`Secure; HttpOnly; SameSite=Lax`, 1년) |

- 응답 처리: `200`+`configured`, `429`(10분간 재시도 안 함, 설정 화면에 안내), `503`(기기 수 한도, 안내). `400`은 로그만 남김.
- 수신: FCM 데이터 메시지의 `type, title, body, url, tag`를 읽음. `url`은 https이고 포털·발주·증명서 3개 도메인일 때만 열고 아니면 포털 `/`로 대체. 모르는 `type`은 공지 채널·공지 수신 설정을 따름.
- 앱 채널: 공지(`notice`) / 발주(`order`) / 업무요청(`support`) / 증명서(`certificate`) / 관리자(`orderAdmin`, `certificateAdmin`) 5개. 서버가 보내는 메시지에 채널 지정이 없어도 앱이 `type`으로 정함.
- 앱은 `POST /api/push/preferences`를 쓰지 않음(Origin 검사로 403).

## 2. 포털에 요청할 사항 (우선순위 순)

### 1. 운영 FCM 설정 확인 — 필수, 가장 먼저
- 운영 Cloudflare Pages에 `FCM_SERVICE_ACCOUNT`(및 Web Push용 VAPID 키)가 설정되어 있는지 확인. 코드만으로는 확인되지 않았고, 앱으로 실제 수신한 기록이 없음.
- 확인 방법: 앱 등록 후 응답 `configured:true` 여부. `false`면 서버 설정 문제이며, 앱 설정 화면에도 같은 안내가 표시됨.
- 앱 실기기 검증(앱 계획 P0)이 이 항목에 달려 있음.

### 2. 앱용 기기 등록 해제 API
- 현재 앱에서 알림을 꺼도 서버의 기기 행이 남아 계속 발송됨. 웹용 `DELETE /api/push/subscription`은 `kind='web'`만 지움(`push-hub.mjs`).
- 요청: `kind='fcm'` 기기를 해제하는 API(예: `DELETE /api/push/device`, 같은 쿠키 기기 기준). 앱은 `Origin` 헤더를 보내지 않으므로 **Origin 검사 없이** 기기 쿠키 또는 본문 `deviceId`로 인증하는 방식이 필요. 응답에서 기기 쿠키를 지우지 말 것(앱이 직접 관리).
- 완료되면 앱에서 알림 끄기·로그아웃 시 호출 연결.

### 3. `notificationsEnabled`와 `appVersion` 처리
- 앱이 `notificationsEnabled`(시스템 알림 허용 여부), `appVersion`을 매번 보내지만 서버는 읽지 않음(`registerDevice`).
- 요청: 두 값을 저장하고, `notificationsEnabled:false`인 기기는 발송 대상에서 제외하거나 현황 집계에서 구분. `appVersion`은 구버전(1.4.0은 관리자 종류를 보내지 않음) 파악용으로 `push_hub_stats` 현황에 분포 표시.

### 4. `GET /api/push/preferences`에 관리자 역할 노출
- 현재 응답은 `admin`(boolean)뿐이라 앱이 통합/열람 관리자를 구분하지 못해, 포털 관리자 세션 API(`/api/admin/session`)를 따로 호출함.
- 요청: 응답에 `role`(`"unified"` | `"reader"` | 없음)과 그 기기가 받을 수 있는 관리자 종류 목록을 포함. 어떤 역할이 `orderAdmin`/`certificateAdmin`을 받는지 서버 규칙(`dispatch`의 `roles`)을 문서화해 주면 앱의 `TYPE_MIN_ROLE`과 맞출 수 있음. 현재 앱은 통합관리자(`unified`)만 `certificateAdmin`을 보여 줌 — 서버 규칙과 다르면 알려 줄 것.

### 5. `assetlinks.json`을 발주·증명서 사이트에도 배포
- 현재 `deploy/pages-portal/.well-known/assetlinks.json`만 있음(패키지 `kr.or.kcaa.gwangju.portal`, 지문 2개).
- 요청: `deploy/pages-orders`, `deploy/pages-certificates`에도 같은 파일 배포. 앱에서 해당 도메인 링크를 앱으로 열려면 필요(앱 계획 P4). 지문 값은 앱 서명 키와 같은 것이어야 함.
- 배포 경로가 바뀐 프로젝트만 빌드되는 규칙(`pages-<이름>.release.json`)에 주의.

## 3. 서버 계약 쪽에서 확인하면 좋은 점 (질문)
- FCM 메시지에 `android.notification`·채널 지정은 없고 `priority: HIGH`, `ttl: 86400s`만 있음(`sendFcm`). 앱은 데이터 전용 메시지를 받아 직접 표시하므로 현재 구조로 문제없음. 이 방식(데이터 전용)을 유지할 것인지 확인.
- `tag` 규칙: 발주 알림의 `tag`가 "발주별"이라고만 적혀 있음. 처리상태가 바뀔 때마다 같은 발주면 같은 `tag`(덮어쓰기)로 보내는지 확인(앱은 같은 `tag`를 같은 알림으로 덮어씀).
- 토큰 만료 시 서버가 기기를 자동 삭제하는 동작은 앱이 `GET /api/push/preferences`의 `device:false`로 감지해 재등록함. 이 응답 형식은 유지해 줄 것.

## 4. 앱에서 알게 된 제약 (참고)
- 앱 WebView는 Web Push를 지원하지 않아 앱 안에서는 네이티브 FCM만 사용함.
- 앱은 기기 ID 원문을 로그·화면에 남기지 않음. 서버도 해시만 저장하는 현재 방식을 유지해 줄 것.
- 이전 앱(v1.5.0 이하)은 관리자 종류를 보내지 않는 경우가 있어, 해당 기기는 기본 종류 스위치를 따르는 현재 서버 동작이 계속 필요함.
- 개인정보처리방침(앱 `PRIVACY_POLICY.md`)에 "기기 식별값은 서버에 해시로만 저장", "발주 알림 기기 연결 시 이메일은 해시와 가린 표시값으로만 저장"을 적었음. 포털 구현이 다르면 알려 줄 것.
