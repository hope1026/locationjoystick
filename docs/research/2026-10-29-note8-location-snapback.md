# Galaxy Note8 실제 위치 복귀 증상 조사

조사일: 2026-10-29 (Asia/Seoul)

## 작업 범위와 기준

- 기존 `/Users/han-byeol/Work/gps-mock`은 변경하지 않는다. 시작 시 HEAD는 `79ef482e62281726737a74d11dc753d2b844f9c9`, 작업 트리는 깨끗했다.
- 원본 `shortcuts/locationjoystick`을 `hope1026/locationjoystick`으로 포크하고 `/Users/han-byeol/Work/locationjoystick`에 별도로 복제했다.
- `origin`은 사용자 포크, `upstream`은 원본이다. 로컬 조사 브랜치는 `codex/fork-baseline`이다.
- 분석 기준: 원본 `e55a20e9200aa033f2fc1b77484e50745ac4633b` (앱 버전 `0.27.0`). 사용자가 설치한 원본 APK 버전과 같다는 뜻은 아니다.
- 사용자 요청에 따라 GPS 순간 이동 분석을 먼저 진행한다. 가장 가까운 경로 지점부터 시작하는 추가 기능은 보류했다. 앱 동작 코드는 아직 수정하지 않았다.

## 사용자 보고

Galaxy Note8에서 경로를 재생하거나 특정 모의 위치에 고정해 두어도, 가끔 실제 위치로 이동했다가 모의 위치로 돌아온다. 사용자는 다른 앱을 지정하지 않고 원본 앱 지도에서 먼저 확인하자고 요청했다. ADB 연결 후 모델 `SM-N950N`, Android `9` / API `28`, 설치된 원본 `com.locationjoystick.app` 버전 `0.16.0` / versionCode `1600`을 확인했다. 화면 켜짐/꺼짐과 옵션 설정에 따른 재현 조건은 아직 확인하지 않았다.

## 현재 결론

**실기기에서 Fused 공급자가 모의 GPS 좌표 → 네트워크 계열 좌표 → 모의 GPS 좌표로 왕복한 기록을 확보했다.** 15:33:15경 수집 스냅샷에서 Fused만 GPS와 약 1,304m 떨어진 좌표로 바뀌었고, 다음 1초 간격 스냅샷에서 복귀했다. 그동안 GPS mock 갱신과 foreground 서비스는 유지됐다. 이 관찰 구간의 시스템 위치 왕복은 공급자 혼합으로 설명된다. 옛 버전의 주입 공백이 이 구간의 직접 원인이라는 근거는 없다.

**시스템 위치가 바뀌는 현상**과 **앱 자체 지도에서 점이나 카메라가 이동하는 현상**은 계속 구분한다. 설치 버전에 해당하는 지도 코드는 시스템 GPS를 직접 표시하지 않으므로, 이번 시스템 기록만으로 자체 지도 마커의 왕복까지 입증한 것은 아니다.

원본도 [문제 해결 문서](../wiki/troubleshooting.html#spoofed-location-resets)에 같은 증상을 기록하고 있다. 이 문서는 Google 위치 서비스가 실제 위치를 갱신할 가능성을 설명한다. 문서의 설명은 이 기기에서 관찰한 결과를 대신하지 않는다. 원본 앱 지도에서 보이는 점이 내부 모의 좌표인지 시스템 위치 표시인지도 구분해야 한다.

첫 기기 조회에서는 원본 앱과 기존 GPS Mock 모두 서비스가 없었는데 GPS와 network mock 공급자가 남아 있었고, 두 공급자에는 같은 오래된 fix가 있었다. 현재 상태에서는 network가 실제 좌표를 보내고 있다고 주장할 수 없다. 이 잔여 공급자의 생성 주체도 확인하지 않았으므로 특정 앱의 결함으로 단정하지 않는다.

## 코드로 확인한 사실

### GPS 공급자만 교체한다

[`MockLocationService.kt`](../../core/location/src/main/kotlin/com/locationjoystick/core/location/MockLocationService.kt)의 `setupTestProvider()`, `addLegacyTestProvider()`, `applyToProvider()`는 모두 `LocationManager.GPS_PROVIDER`만 사용한다. API 31 이상과 이전 버전 모두 같다. 네트워크 공급자를 교체하거나 Google Fused에 `setMockMode` / `setMockLocation`을 호출하는 경로는 없다.

이는 [기술 제약](../technical-constraints.md)의 Play Services 미사용 방침과 일치한다. GPS mock을 넣었다는 사실만으로 네트워크 공급자나 모든 위치 소비자의 결과까지 같다고 단정할 수 없다. Android의 `addTestProvider`는 지정한 이름의 공급자를 교체하는 API이고, Google Fused의 mock mode는 별도 API다.

- [Android LocationManager 공식 문서](https://developer.android.com/reference/android/location/LocationManager)
- [Google FusedLocationProviderClient 공식 문서](https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient)

### 위치 갱신 중단·서비스 재시작도 후보다

- 고정 위치는 `startUpdateLoop()`에서 1초마다 갱신한다. 경로 재생은 자체 콜백으로 갱신하고 일반 루프를 중지한다.
- `pushLocationUpdate()`의 주입 실패는 로그에 기록하지만 공급자 재등록이나 오류 상태 전환은 하지 않는다. 내부 `providerAdded` 값만으로 실제 공급자의 건강 상태를 보장하지 못한다.
- 디버그 통계는 `applyToProvider()`보다 먼저 게시한다. 정상 tick 표시만으로 공급자가 위치를 실제로 수락했다고 판단할 수 없다.
- 서비스 종료 시 공급자를 제거한다. OS 재시작 후 기억된 위치를 비동기로 복구하는 구간이 있어 서비스 재시작과 증상이 겹치는지도 확인해야 한다.

### 최신 원본에 이미 반영된 수정

설치된 APK는 `v0.16.0` 태그(`1dd7ac38`, 2026-08-17)에 해당하는 버전명이다. APK binary와 태그 소스의 일치 여부까지 검증한 것은 아니지만, 해당 태그 소스에서는 `buildLocation()`이 suspended 구간에 `null`을 반환하고, 일반 사용자의 경로 일시정지는 갱신 루프를 유지하지 않는 코드를 확인했다. 아래 두 수정은 이 태그 이후에 반영됐다.

| 변경 | 근거 | 현재 분석 기준 소스 |
|---|---|---|
| 화면 잠금 후 루프 지연을 줄이는 partial wake lock | `a71b8fce`, 2026-07-22 | 포함. 획득 실패 시 로그를 남김 |
| 경로 일시정지 때도 고정 좌표를 계속 주입 | `2a96c28a`, 2026-08-19 | 포함 |
| suspended mocking 휴지 구간에서도 좌표를 계속 주입 | `f4a93f49`, 2026-08-19 | 포함 |

현재 `suspendedMockingEnabled`는 기본값이 `false`다. 켜더라도 최신 `buildLocation()`은 fix를 생략하지 않으며, 경로 재생·walk-to에서는 휴지 구간 자체를 적용하지 않는다. 따라서 이 옵션만으로 현재 소스의 두 모드 모두에서 생기는 증상을 설명할 수 없다.

설치 버전에 해당하는 옛 소스의 갱신 공백은 실제 후보지만, 휴지 옵션이 꺼져 있거나 경로가 계속 실행 중이라면 해당 두 버그만으로 증상을 설명할 수 없다. 최신 APK로 바꾸기 전에 옛 버전의 설정과 갱신 기록을 먼저 수집한다.

`MapController.restoreLastLocationIfNeeded()`의 실제 위치 초기화도 현재 위치가 비어 있을 때만 실행되고, 늦게 도착한 실제 fix는 사용자가 위치를 바꾸지 않았을 때만 반영한다. 반복적인 실행 중 복귀의 첫 번째 후보로 보지 않는다.

### 설치 버전에 해당하는 자체 지도는 내부 좌표를 표시한다

`v0.16.0` 태그에서 표시 경로는 `LocationRepository.currentPosition` → `MapController.sharedState` → `MapViewModel.uiState` → `MapScreen`의 파란 점이다. 메인 지도는 MapLibre 시스템 location component를 켜지 않는다. 카메라도 같은 내부 좌표를 따라간다.

- [v0.16.0 MapScreen](https://github.com/shortcuts/locationjoystick/blob/v0.16.0/feature/map/impl/src/main/kotlin/com/locationjoystick/feature/map/impl/MapScreen.kt): 위치 점은 `uiState.currentPosition`, follow 카메라는 그 위치로 500ms 애니메이션을 사용한다.
- [v0.16.0 MapController](https://github.com/shortcuts/locationjoystick/blob/v0.16.0/core/location/src/main/kotlin/com/locationjoystick/core/location/MapController.kt): 초기화 때 저장 위치를 복구한다. 최신 소스와 달리 실제 GPS 초기 조회는 없다.

내부 좌표의 생산자는 시작·텔레포트 명령, 경로·로밍·걷기·조이스틱·그룹 follower, 최초 저장 위치 복구다. 실제 GPS 콜백으로 내부 좌표를 덮어쓰는 경로는 찾지 못했다. 따라서 파란 점까지 이동하는지, 카메라만 움직이는지, 시스템 공급자 좌표만 바뀌는지를 재현에서 따로 관찰해야 한다.

좌표가 그대로여도 지도 재구성 때 follow 카메라 애니메이션이 다시 호출될 수 있다. 이는 화면 흔들림 후보이며, 실제 위치로 왕복한다는 증거는 아니다. 최초 저장 위치 복구에는 비동기 읽기 뒤 쓰기 전 재확인이 없는 경합 가능성도 있지만, 주기적인 고정 위치 왕복을 설명하는 근거는 아직 없다.

## 기기에서 구분할 증거

같은 시각에 다음 항목을 수집한다. 사용자 설정은 먼저 바꾸지 않는다.

1. 모델·Android API·APK 버전, 선택된 모의 위치 앱과 권한.
2. GPS 및 network 공급자의 마지막 좌표, fix 시간과 mock 표시.
3. `MockLocationService`의 실행 상태와 재시작, 공급자 주입 오류.
4. 사용 모드, 화면 상태, 대상 앱에서 위치가 튄 시각.

| 증상 순간의 관찰 | 해석 |
|---|---|
| GPS mock fix는 계속 갱신되는데 network는 실제 위치이고 서비스는 유지 | 공급자 혼합 가설을 지지. 대상 앱의 위치 입력까지 확인해야 확정 가능 |
| GPS fix 시간이 멈추거나 공급자가 사라짐 | 루프 지연·공급자 소실·주입 실패를 우선 조사 |
| 서비스 종료/재시작 로그와 실제 위치 복귀 시각이 일치 | 서비스 생명주기·배터리 제한을 우선 조사 |
| 시스템 공급자 기록은 안정적인데 특정 앱만 튐 | 그 앱의 캐시·Fused·다른 위치 입력 가능성을 구분 |

초기 관찰 후에만 GPS-only / Wi-Fi·Bluetooth 검색 끄기를 한 번에 하나씩 비교할 수 있다. 계정 설정 변경, Play Services 권한 제거, 기기 찾기 비활성화는 이 조사에서 수행하지 않는다.

## 다음 단계

- 아래 실기기 기록으로 공급자 혼합을 확인했다. 동일 조건에서 공급자별 수신 좌표가 계속 일치하는 것을 수정의 검증 기준으로 삼는다.
- GPS+network 동시 주입을 검토하되, 기기에 남은 network mock 공급자가 있는데도 네트워크 계열 fix가 전달됐으므로 단순 등록만으로 해결됐다고 보지 않는다. Google Fused 직접 연동은 원본의 Play Services 미사용 방침을 바꾸므로 별도 설계 결정으로 다룬다.
- 갱신 공백이 확인되면 해당 루프·pause·재시작 경로를 재현하고 회귀 테스트를 만든다.
- 원인 확인 전에는 수정 완료나 증상 해소로 보고하지 않는다.

## 15:32–15:34 실기기 발생 직후 수집

사용자가 위치가 튀었다고 알린 직후 앱 화면·설정·이동 모드를 건드리지 않고 logcat, `dumpsys location`, 서비스, 화면 전원, 권한과 기기 시각을 저장했다. 두 번째 발생 보고도 별도 저장했다. 개인 좌표와 전체 시스템 로그는 저장소 밖 로컬 폴더에만 보존한다.

- 첫 발생 직후: `/tmp/locationjoystick-note8-snapback-20261029T063226Z`
- 첫 수집 후 45초 관찰: `/tmp/locationjoystick-note8-after-snapback-20261029T063226Z`
- 두 번째 보고 직후: `/tmp/locationjoystick-note8-snapback2-20261029T063344Z`
- 공급자 비교 결과: 위 45초 폴더의 `provider-comparison-summary.json`. 좌표 원문 대신 거리·fix 시간·mock 표시와 원시 파일 이름을 기록했다.

표의 시각은 수집 호스트의 UTC 기록을 Asia/Seoul로 변환한 값이다. 기기 `date`와 호스트 시각에는 약 2초 차이가 있어, 앱 화면에서 인지한 순간과 초 단위로 동일하다고 단정하지 않는다.

| 수집 시각 (KST) | GPS | Fused | 원시 스냅샷 |
|---|---|---|---|
| 15:33:14경 | 모의 좌표, mock=true | GPS 모의 좌표와 일치, mock=true | `0112-location.stdout.txt` |
| 15:33:15경 | 같은 모의 좌표를 계속 갱신, mock=true | GPS에서 1,303.97m 떨어짐, network 좌표와 0.8m 차이, mock=false | `0115-location.stdout.txt` |
| 15:33:16경 | 모의 좌표 갱신 유지 | GPS 모의 좌표로 복귀, mock=true | `0118-location.stdout.txt` |

중간 스냅샷의 Fused와 network fix는 동일한 elapsed realtime 값을 갖는다. GPS fix는 그보다 282ms 이전 값이지만 모의 위치 주입은 계속되고 있었다. 따라서 서로 다른 공급자의 최신 fix가 바뀔 때 합성된 위치가 왕복하는 현상을 직접 관찰한 것이다. 이 값들이 대상 앱에 실제로 전달된 콜백까지 수집한 것은 아니다.

45개 위치 스냅샷에서는 GPS fix의 변경 간격이 약 1,001–1,026ms였고, network는 약 6.6초마다 GPS와 약 1.3km 다른 좌표로 갱신됐다. 서비스 스냅샷 45개 모두 같은 PID `24908`의 foreground 서비스가 유지됐다. 해당 구간의 화면 전원은 켜져 있었으며, 캡처한 앱 로그에 주입 실패나 프로세스 재시작은 없었다. logcat에는 삼성 위치 처리기의 `isFromMockProvider, return` 메시지도 반복되지만, 이 문구 하나만으로 Fused 왕복의 원인을 설명하지 않는다.

15:32:06.964에는 `RouteReplayEngine: Replay stopped`, 직후 `ReplayOrchestrator: Replay stopped; service remains active in TELEPORT mode`가 기록됐다. 즉 이 관찰 시점은 경로 정지 후 좌표를 유지하는 상태였다. 사용자가 직접 정지했는지 등 명령의 주체는 이 로그만으로 판정하지 않는다. 계속 실행 중인 경로에서도 똑같이 발생한다는 보고는 별도 조건으로 남긴다.

network의 외부 `Location`에는 mock 표시가 남아 있지만 내부 `noGPSLocation`에는 다른 좌표가 들어 있고, 순간적으로 Fused는 mock 표시 없이 그 네트워크 계열 위치를 노출했다. 원본 코드가 network를 직접 갱신하지 않는 점과 부합한다. 다만 Samsung 위치 서비스·잔여 mock 공급자·Google 위치 서비스 각각의 기여도는 아직 분리하지 않았으며, 해결책의 효과도 검증하지 않았다.

## 재사용할 수집 도구

[`scripts/collect-location-diagnostics.py`](../../scripts/collect-location-diagnostics.py)는 사용자 설정과 앱 실행 상태를 바꾸지 않고 ADB 조회만 수행한다. 권한이 허용된 기기가 한 대일 때 다음 명령으로 실행한다. 여러 기기가 연결돼 있으면 `--serial SERIAL`을 추가한다.

```bash
python3 scripts/collect-location-diagnostics.py --duration 120 --interval 2
```

모델·OS·앱 정보·권한·위치 검색 설정, 필터링한 실시간 logcat, 위치·서비스·화면 전원 상태 스냅샷을 수집한다. 기본 출력은 저장소 밖 임시 폴더이며, `manifest.json`에 각 조회의 시각·성공 여부·오류·파일 경로를 남긴다. 실제 좌표를 포함한 원시 파일은 로컬에 보존한다. 수집 중 대상 앱에서 증상이 발생한 시각을 메모하면 스냅샷과 대조할 수 있다. 2초 간격의 스냅샷 사이에서 끝나는 짧은 이벤트는 놓칠 수 있으므로, 로그가 없다는 이유만으로 증상이 없었다고 판단하지 않는다.

연결 거부·복수 기기 선택·명령 timeout·수집 중단·logcat 정리를 검사하는 테스트는 다음과 같다.

```bash
python3 scripts/test_collect_location_diagnostics.py
```

## 검증 진행 기록

- GitHub 포크의 `isFork=true`, parent=`shortcuts/locationjoystick` 확인.
- 원본 debug APK `:app:assembleDebug` 성공 (5분 25초). APK metadata의 package=`com.locationjoystick.app`, versionName=`0.27.0`, minSdk=`28`을 확인했다. `apksigner verify --print-certs`도 성공했다. 이는 설치·실기기 동작이나 증상 해소를 입증하지 않는다.
- 수집 도구의 Python 테스트 14건 성공.
- 저장소 필수 검사 `make format`, `make lint` 성공. 소스 포맷 변경은 없었다.
- `make test` 첫 실행의 XML 합계는 1,597건 중 1건 실패였다. `RouteReplayEnginePauseResumeTest`의 일시정지 전후 콜백 수 검사가 기대 2 / 실제 3으로 실패했다. 이 테스트는 실제 시간의 `Thread.sleep()`과 비동기 콜백을 사용하고, pause 직전 저장한 개수와 나중의 개수를 비교한다. 소스 변경 없이 해당 클래스 재실행이 성공했고, 이어서 `make test` 전체 대상도 성공했다(성공한 다른 모듈 결과는 Gradle이 재사용). 최초 실패는 간헐적인 테스트 결과로 남기며 실기기 증상의 원인이나 수정 완료로 단정하지 않는다.
- USB 연결 확인 완료. 위 모델·OS·설치 버전과 `android:mock_location=allow`, 위치 모드 `3`을 조회했다. 사용자 설정은 변경하지 않았다.
- 원본을 `am start`로 열었지만 보안 잠금(`showing=true`, `secure=true`)과 화면 꺼짐 상태여서 앱 UI 재현은 아직 진행하지 못했다. 사용자에게 잠금 해제를 요청했다.
- 최초 수집 폴더: `/tmp/locationjoystick-note8-20261029-baseline`. 120초, 위치 스냅샷 120개, 서비스 스냅샷 120개, 조회 오류 0건이다. 공급자별 마지막 fix 시간은 이 구간에 바뀌지 않았고, 원본 위치 서비스는 120개 모두 실행되지 않았다. 따라서 증상 재현 기록으로 취급하지 않는다.
