# 길 따라 걷기 확인

검증일: 2026-10-29 (Asia/Seoul)

## 연결 및 서버 확인

지도 동작의 Walk via roads는 `MapController.walkViaRoads()`에 연결돼 있다. 현재 모의 위치에서 목적지까지 OSRM 도로 경로를 받아 `WalkCoordinator.startWalkAlongRoute()`로 이동하고, 위치 tick을 모의 위치 서비스에 전달한다.

서버·프로필 재시도 후에도 경로 요청이 실패하면 이유를 알리고 직선 걷기로 대체한다. 따라서 도로 경로 응답이 성공한 경우와 직선 대체 동작을 구분해야 한다.

공개 서울 테스트 좌표로 현재 서버 응답을 확인했다.

| 서버 | 응답 | 경로 좌표 수 | 경로 거리 |
|---|---|---|---|
| FOSSGIS 보행 경로 | Ok | 39 | 1,009.8m |
| OSRM 대체 서버 | Ok | 37 | 1,091.8m |

## 앱 로직 통합 확인

별도 API 36 에뮬레이터 `locationjoystick_sort_api36`에서 실제 네트워크와 모의 위치 API를 사용하는 `RoadWalkSmokeTest`를 실행했다. UI 버튼 자체를 누르는 시험은 아니며, 그 버튼에 연결된 실제 controller를 호출했다. 보간 경로에는 초기 위치에서 도로 시작점까지의 진입 구간도 포함한다.

- 실제 수신한 도로 polyline: 39개 좌표. 두 점만 있는 직선 대체 경로가 아님을 검사했다.
- 도로 걷기 모드 활성화 확인.
- 관찰 25초, 시작·끝 위치 사이 거리 약 38.18m.
- 25개 내부 위치 샘플의 polyline 대비 최대 거리 약 0.47m.
- 마지막 GPS와 Google Fused fix 모두 mock 표시 유지, 좌표 차이 0m.
- 시험 종료 시 걷기와 모의 위치 서비스를 정상 중지했다.
- 최종 결과: `OK (1 test)`, 약 28초.

이 결과는 해당 구간에서 도로 경로 조회·이동·위치 주입이 연결돼 동작함을 확인한다. 목적지까지 약 1km 전체 이동이나 모든 지역·서버 장애 조건의 실기기 검증을 뜻하지 않는다. 노트8의 화면·설정·이동 상태는 이 시험에서 조작하지 않았다.

로컬 결과는 `/tmp/locationjoystick-road-walk-emulator.log`, `/tmp/locationjoystick-road-walk-evidence.json`, `/tmp/locationjoystick-road-walk-curl-check.json`에 보존했다.
