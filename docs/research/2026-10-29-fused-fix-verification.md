# Fused 연동 수정과 검증 기록

날짜: 2026-10-29 (Asia/Seoul)

## 변경 내용

`codex/fused-mock-stability`는 원본 v0.27.0 기반 포크에 Google Fused 공식 mock API를 추가한다. [원인 조사](2026-10-29-note8-location-snapback.md)에서 확인한 GPS와 Fused의 좌표 불일치에 대응한다.

- Fused mock mode 활성화가 성공한 뒤 GPS/network 공급자를 준비한다. 같은 fix를 세 경로로 전달한다.
- 세션 토큰과 직렬화로 이전 실행의 대기 쓰기·종료 작업이 새 실행에 영향을 주지 않게 한다. 오래되거나 중복된 fix 시각은 거부한다.
- 준비 중 중지·재시작이 겹치면 현재 활성 상태를 확인해 다시 준비한다. 이전 중지 작업은 새 갱신 작업의 핸들을 지우지 않는다.
- Google Play services가 설치되지 않은 경우 GPS/network만 사용하고 로그에 남긴다. 설치됐지만 준비되지 않았거나 API 호출이 실패하면 세션을 오류로 종료한다.
- 일시정지 중에도 최신 고정 fix를 전달한다. 종료·오류 시 공급자와 Fused mock mode를 정리한다. Android의 mock 표시는 유지한다.

## 보존 및 설치

기존 `/Users/han-byeol/Work/gps-mock`은 수정하지 않았다. 노트8 원본 `com.locationjoystick.app` v0.16.0은 배포용 서명이어서 삭제하거나 덮어쓰지 않았다. APK 사본은 `/tmp/locationjoystick-note8-original-backup-20261029/`에 보존했다.

수정 debug 앱은 `com.locationjoystick.app.fork`, 이름 `locationjoystick Fused`, 버전 `0.27.0-fused-fork`로 별도 설치했다. 테스트 동안 원본 앱은 UI에서 정상 중지하고 mock app-op를 deny로, 포크는 allow로 바꿨다. 위치 모드 `3`, Wi-Fi 검색 `1`, Bluetooth 검색 `1`은 그대로 유지했다. 임시 충전 중 화면 유지 설정은 검증 중단 후 원래 값 `0`으로 복구했다.

## 자동 검사

- Fused 세션 시작·실패·취소·timeout·종료·재시작·좌표 순서를 검사하는 단위 테스트 12건 통과.
- 준비 중 상태 전환·중지·취소를 검사하는 회귀 테스트 6건 통과.
- 전체 JVM 테스트 XML 합계 1,615건, 실패·오류·skip 0건.
- 진단 도구의 Python 테스트 15건 통과. `--package`로 원본과 포크의 서비스 조회를 구분한다.
- debug 앱과 AndroidTest APK 빌드 통과.
- 최초 lint에서 Google API의 권한 오류 처리 2건을 지적받아 `SecurityException`을 명시적으로 기록·전파하도록 수정했다. 최종 포맷·lint·전체 테스트 재확인은 `/tmp/locationjoystick-fused-final-gates/`에 보존한다.

## 실기기 자동 시험의 한계

대상은 Galaxy Note8 `SM-N950N`, Android 9 / API 28이다. 시험은 실제 GPS·network·Google Fused 콜백을 비교하며 고정 90초, 경로 120초, 일시정지 30초, 화면 꺼짐 120초를 계획했다.

첫 실행은 JUnit이 시험 메서드의 반환형을 void로 요구해 초기화에 실패했다. `runBlocking<Unit>`으로 수정하고 APK를 다시 빌드·설치했다.

두 번째 실행은 세 공급자의 mock fix와 시작 좌표를 기다리는 조건을 통과해 고정 위치 단계에 진입했다. 약 16초 후 실제 이동 모드가 `TELEPORT`에서 `ROUTE_REPLAY`로 바뀌어 고정 단계의 모드 검사가 실패했다. 이 기록은 계획한 안정성 시험의 통과로 취급하지 않는다. 종료 정리에서는 Fused의 마지막 캐시가 null 또는 non-mock인 조건을 통과했다.

사용자가 직접 확인하겠다고 요청해 자동 조작을 중단했다. 원시 결과는 로컬 `/tmp/locationjoystick-fused-note8-instrumentation-run2.log`와 `/tmp/locationjoystick-note8-fused-verification-20261029-run2/`에 보존했다. 개인 좌표·기기 원시 로그는 Git에 넣지 않는다.

**고정 위치·경로·일시정지·화면 꺼짐의 전체 관찰은 미완료다. 노트8에서 위치 왕복이 해결됐다고 아직 판정하지 않는다.** 사용자의 수동 결과 또는 조건이 유지되는 자동 시험으로 최종 확인해야 한다.
