# 앱 쪽 회신: 포털 알림 연동 요청 처리 결과

대상: 안드로이드 앱 저장소(`droidbin/KCAA-WSS-APP`) 작업 세션
근거: `docs/portal-handoff-android-push.md`(앱 v1.6.0), 포털 저장소 작업 결과
상태: **운영 배포 완료**(2026-11-12, 포털 저장소 커밋 `51bc44c`, 포털·발주·증명서 세 Pages 프로젝트 production `deploy:success` 확인).
운영에서 확인한 것: 세 사이트의 `/.well-known/assetlinks.json` 200·`application/json`·내용 동일, 쿠키 없는 `GET /api/push/preferences`는 `{"device":false}`, 식별 정보 없는 `DELETE /api/push/device`는 400.
아직 확인하지 못한 것: 앱 실기기 수신, 운영 D1의 새 컬럼 생성(앱이 처음 등록할 때 자동 생성됨. 등록 후 관리자 전송 현황의 앱 버전 표시로 확인).

## 요청별 결과

| # | 요청 | 결과 |
|---|---|---|
| 1 | 운영 FCM 설정 확인 | Cloudflare API로 포털 프로젝트의 비밀값 **이름**을 확인했습니다. `FCM_SERVICE_ACCOUNT`, `VAPID_PRIVATE_JWK`, `VAPID_PUBLIC_KEY`, `VAPID_SUBJECT`가 있습니다. 값이 올바른지는 실기기에서만 확인됩니다. |
| 2 | 앱용 해제 API | `DELETE /api/push/device` 추가 |
| 3 | `notificationsEnabled`·`appVersion` | 저장하고, `notificationsEnabled:false` 기기는 발송에서 제외 |
| 4 | preferences에 역할 노출 | `role`, `adminKinds` 추가 |
| 5 | assetlinks 배포 | 발주·증명서 사이트에도 배포 |

## 2. 기기 해제: `DELETE /api/push/device`

- 식별: `Cookie: kcaa_app_device=<id>` **또는** 본문 `{"deviceId":"<64자리 16진수>"}`(쿠키가 있으면 쿠키 우선). `Origin` 헤더는 필요 없습니다.
- 응답: `200 {"ok":true,"removed":true|false}`. 이미 없어도 200입니다(여러 번 호출해도 안전). 기기 ID 형식이 없거나 틀리면 `400`, IP당 10분 30회 초과는 `429`.
- 서버는 `kind='fcm'` 기기 행과 그 기기의 대기 이벤트만 지웁니다. **응답에서 쿠키를 지우지 않습니다.** 브라우저·PWA 기기는 지우지 않습니다.
- 발주 이메일 연결은 푸시 등록과 별개라 **지우지 않습니다.** 해제 후 같은 기기 ID로 다시 등록하면 이메일 연결 기준 알림도 다시 받습니다. 이메일 연결을 끊으려면 `/orders/notify`의 연결 해제를 써야 합니다.
- 알림 끄기·로그아웃 때 호출하면 됩니다. 이후 `GET /api/push/preferences`는 `{"device":false}`를 줍니다.

## 3. 등록 요청의 두 값

`POST /api/push/device` 본문에 보내는 값을 서버가 저장합니다.

- `appVersion`: `^[0-9A-Za-z._+-]{1,20}$`만 저장합니다. 그 밖의 값은 버전 미상(빈 값)으로 저장됩니다.
- `notificationsEnabled`: **`false`일 때만** 끔으로 저장합니다. 값이 없거나 불리언이 아니면 켬으로 간주합니다.
- **`false`인 기기는 모든 발송에서 제외**됩니다. 서버는 실패로 세지도 않습니다. 시스템 알림을 다시 켜면 앱이 실행·재개 때 다시 등록하면서 `true`를 보내야 발송이 재개됩니다. 앱이 재개 때마다 등록한다는 현재 동작이면 충분합니다.
- 관리자 전송 현황에 앱 버전별 기기 수와 꺼진 기기 수가 표시됩니다.

## 4. `GET /api/push/preferences` 확장

`Cookie: kcaa_app_device=<id>`만으로 호출합니다(기존과 같음).

```json
{
  "device": true,
  "kind": "fcm",
  "admin": true,
  "role": "unified",
  "adminKinds": ["orderAdmin", "certificateAdmin"],
  "types": { "order": true, "support": true, "certificate": true, "orderAdmin": true, "certificateAdmin": true }
}
```

- `role`은 `"unified"` 또는 `"reader"`이고 **관리자가 아니면 키 자체가 없습니다**(null이 아님). 앱에서 `optString`으로 읽으면 안전합니다.
- `adminKinds`는 항상 배열입니다(관리자가 아니면 빈 배열). `types`에는 기본 3종과 `adminKinds`에 있는 종류만 들어옵니다.
- 기기가 없으면 `{"device":false}`(형식 유지).

### 서버 규칙(앱 `TYPE_MIN_ROLE`과 맞추세요)

| 종류 | 받는 역할 |
|---|---|
| `orderAdmin` (새 발주요청 접수) | 통합 관리자(`unified`), 열람 관리자(`reader`) |
| `certificateAdmin` (새 증명서 수정요청 접수) | **통합 관리자(`unified`)만** |

현재 앱의 "통합관리자만 `certificateAdmin` 표시"는 서버 규칙과 같습니다. 서버는 다른 역할 기기에는 `certificateAdmin`을 보내지 않으므로 UI에 보이지 않게 하면 됩니다. 앱이 `adminKinds`를 그대로 따르면 규칙이 바뀌어도 앱 수정이 필요 없습니다.

## 5. assetlinks.json

`https://gwangju-orders-migration.pages.dev/.well-known/assetlinks.json`, `https://gwangju-student-certificates.pages.dev/.well-known/assetlinks.json`을 포털과 같은 내용(패키지 `kr.or.kcaa.gwangju.portal`, 지문 2개)으로 제공합니다. 배포 후 `application/json`, 200으로 응답합니다. 의무교육·인사 사이트는 이번 범위에 없습니다. 앱이 그 도메인도 앱 링크로 열려면 별도 요청해 주세요.

## 6. 질문에 대한 답

- **데이터 전용 메시지**: 유지합니다. 서버는 `android.notification`·채널 지정을 보내지 않고 `priority: HIGH`, `ttl: 86400s`만 보냅니다.
- **`tag`**: 같은 발주의 처리상태 알림은 항상 `order-status-<발주ID>`라서 덮어쓰기가 됩니다. 새 발주는 `order-new-<ID>`, 업무요청 답변은 `support-<ID>`, 증명서는 `certificate-<ID>`, 공지는 `notice-<ID>`입니다.
- **`device:false`**: 응답 형식 유지합니다.
- **개인정보처리방침**: 서버는 기기 식별값을 해시로, 이메일을 HMAC과 가린 표시값으로만 저장하며, 이번에 앱 버전과 앱 알림 허용 여부 저장을 포털 방침에 반영했습니다. 앱 `PRIVACY_POLICY.md`에도 이 두 항목을 넣어 주세요.

## 7. 앱에서 확인할 것

1. 배포 후 `POST /api/push/device` 응답의 `configured:true` 확인.
2. 알림 끄기·로그아웃에 `DELETE /api/push/device` 연결.
3. 시스템 알림을 껐다 켠 뒤 실행·재개 때 `notificationsEnabled:true`로 다시 등록되는지 확인.
4. 실기기 수신 확인(운영 설정이 올바른지는 이것으로만 검증됩니다).
