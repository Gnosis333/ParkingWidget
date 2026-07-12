# Parking Memory Widget

지하 주차장에서 **어느 층(B1/B2)에 주차했는지 자동으로 기억**하는 안드로이드 홈 화면 위젯.

주머니 속에서 폰을 꺼내지 않아도, 주차 후 걸어 나오는 동안 BLE 스캔으로 층을 감지해
위젯에 표시한다. 수동 탭으로 층을 바꾸는 것도 가능하다.

## 동작 원리

아파트 도어 비콘(스마트 IoT 원패스)은 1층/2층이 동일 UUID이고 MAC을 30~40분마다
회전시켜 층 구분에 쓸 수 없다. 대신 **층마다 고유하게 존재하는 고정-MAC BLE 기기를
"앵커"로 사용**한다 (`ParkingAnchors.kt` — 층 판정 단일 출처):

- B1 앵커: 삼성 기기, keybox
- B2 앵커: LC241 조명 컨트롤러 3대

`BleMonitorService`(포그라운드 서비스)가 앵커 광고를 상시 스캔하고, 최근 30초 내
가장 강한 앵커의 층으로 판정해 위젯을 갱신한다.

## 구성

| 파일 | 역할 |
|------|------|
| `ParkingWidgetProvider.kt` | 홈 위젯 렌더링·탭 처리, 서비스 시작/중지 |
| `BleMonitorService.kt` | 상시 BLE 스캔, 층 판정, 자가치유·워치독 |
| `ParkingAnchors.kt` | 앵커 MAC/이름 목록 + 층 매핑 (단일 출처) |
| `WatchdogReceiver.kt` | AlarmManager 워치독 수신 (Doze 대응 부활) |
| `MonitorLog.kt` | 서비스 진단 로그 (`monitor_log.txt`, 256KB 캡) |
| `BootReceiver.kt` | 부팅 후 서비스 재시작 |
| `MainActivity.kt` | BLE 디버그 콘솔 (수동 스캔·로그 저장·권한/상태 안내) |

## 안정성 설계 (4중)

백그라운드 BLE 스캔은 조용히 죽는 경로가 많다. 이 앱은 4일간의 실전 디버깅으로
확인된 함정들을 모두 방어한다:

1. **위치 "항상 허용" 필수** — BLE 스캔 결과는 위치 정보로 취급되어, 위치 권한이
   "앱 사용 중에만"이면 백그라운드에서 결과가 에러 없이 전량 차단된다.
2. **주소 타입 함정** — `ScanFilter.setDeviceAddress(mac)`는 PUBLIC 주소를 가정해
   랜덤 주소로 광고하는 앵커에 안 걸린다 → 기기 **이름 필터를 병행**하고 콜백에서
   MAC으로 재검증.
3. **Doze 워치독** — Handler 타이머는 깊은 잠에서 멈추므로, AlarmManager
   `setAndAllowWhileIdle` 15분 체인으로 죽은 서비스도 부활시킨다.
4. **스캔 자가치유** — `onScanFailed` 백오프 재시작, 20분 주기 stop→start,
   화면 켜짐 시 무필터 버스트 스캔(25초).

## 설치 후 필수 설정

1. 앱 실행 → **"백그라운드 자동감지 켜기"** 버튼.
2. 위치 권한 **"항상 허용"** 선택 (안 하면 백그라운드 감지 불가 — 상태창에 표시됨).
3. 배터리 최적화 예외 허용.

## 빌드

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew installDebug
```

## 진단

문제 발생 시 서비스 로그 회수:

```
adb pull /sdcard/Android/data/com.example.parkingwidget/files/monitor_log.txt
```

서비스 생명주기·스캔 시작/실패·워치독 발화·앵커 수신(경로 태그)·층 판정이 모두
기록된다. 앵커 기기가 교체되면 "앵커 MAC 변경 의심" 로그로 새 MAC을 알 수 있다.
수동 스캔 로그(전파 전수 조사)는 앱의 "1층/2층 스캔" 버튼 → `scan_log.txt`.
