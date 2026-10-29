# 저장된 경로 지점의 지도 편집 검증

검증일: 2026-10-29 (Asia/Seoul)

## 동작

경로 목록의 Edit → Route Details에서 각 지점의 편집 버튼을 누르면 해당 위치를 중심으로 지도가 열린다. 지도 탭은 초안 좌표와 경로 미리보기만 바꾸고, Save를 눌러야 저장한다. Cancel과 시스템 뒤로가기는 초안을 버린다. 텔레포트 경로의 대기 시간 편집은 타이머 버튼으로 따로 유지한다.

저장은 지점 ID와 경로 ID가 일치하는 행의 좌표와 경로 수정 시각만 갱신한다. 지점 순서·대기 시간·다른 지점·경로 이름·속도 설정은 유지한다. 저장 실패 시 지도와 초안을 유지하고 오류를 표시한다.

## 실행 환경과 결과

- Android Emulator: `gps_mock_api36`, `sdk_gphone64_arm64`, API 36, 1080×2400.
- 설치 패키지: `com.locationjoystick.app.fork`.
- debug 앱과 AndroidTest APK 빌드, 포맷, lint, 전체 JVM 테스트 통과. JVM 결과는 1,623건, 실패·오류·skip 0건이다.
- 지점 좌표 저장 회귀 테스트 8건 통과. 잘못된 좌표, 다른 경로의 지점, 삭제된 지점과 저장 오류도 검사했다.
- 최종 APK에서 아래 Compose UI 테스트 4건을 실행해 모두 통과했다.

| UI 시나리오 | 결과 |
|---|---|
| 실제 지도 탭 → 저장 전 DB 불변 → 선택 지점만 저장 → 재진입 좌표 일치 | PASS |
| Cancel과 시스템 뒤로가기 → 좌표 불변, 미저장 경로 이름 초안 유지 | PASS |
| 텔레포트 경로 위치 수정 → 대기 시간 유지, 별도 대기 시간 편집 유지 | PASS |
| 편집 중 해당 지점 삭제 → Save 오류 표시, 지도 유지, 삭제 지점 재생성 없음 | PASS |

지도 타일·선택 지점·경로 선을 실제 렌더링해 확인했다. 선택 좌표와 Save/Cancel이 보이며, 지점 번호는 제목에서 잘리지 않는다. 하단 버튼은 시스템 내비게이션 바와 겹치지 않도록 배치했다.

스크린샷 갤러리의 Route Details 단계도 포크 패키지와 에뮬레이터를 지정해 실행했다. 최종 실행에 `Could not find UI element` 경고는 없었다. 필요한 단계만 실행해도 Routes 화면에서 시작하도록 스크립트를 보완했다.

- [지도 편집 화면](route-waypoint-map-editor.png)
- [경로 상세 갤러리 화면](../wiki/screenshots/10_route_detail.png)
- 로컬 검사 로그: `/tmp/locationjoystick-waypoint-edit-checks/final-verification.log`
- 최종 에뮬레이터 UI 로그: `/tmp/locationjoystick-waypoint-edit-checks/emulator-tests-verified.log`
- 갤러리 실행 로그: `/tmp/locationjoystick-waypoint-edit-checks/gallery-final.log`
