package com.example.parkingwidget

/**
 * 층 판정 기준 — 단일 출처 (MainActivity 디버거 + BleMonitorService 공유).
 *
 * 배경: 스마트 IoT 원패스 도어 비콘(iBeacon UUID cf2409fe…f8ee-eeff)은
 *  - 1층/2층이 동일 UUID/major/minor 이고
 *  - MAC을 ~30~40분마다 회전시킴
 * → UUID로도 MAC으로도 층 구분 불가.
 *
 * 대신 층마다 고유하게 존재하는 "고정(Public) MAC" 기기를 앵커로 사용해 층을 판정한다.
 * 근거: 여러 트립 로그에서 아래 기기들이 한 번도 층을 넘나들지 않았고 MAC도 안 바뀜.
 */
object ParkingAnchors {

    // 1층에만 잡히는 고정-MAC 기기
    val F1_ANCHORS = setOf(
        "7C:72:E7:9F:FE:F8",  // 삼성 기기 "S717…" — 주민 차량 탑재 추정 (부재 시 감지 공백)
        "DD:57:E3:E3:1B:64"   // keybox_xxxx (iBeacon UUID 92428ea0…) — 2026-07-17 문 앞에서도 미관측, 소멸 의심
        // "04:EE:03:91:6D:2A" (SYEz) 제거 (2026-07-20): 고정 앵커가 아니라 이동하는 애플 Find My
        //   기기(mfg 0x4c:0218…)였음. 07-20 B2 도착 시 이 MAC이 사용자 옆(-40~-64)에서 잡혀
        //   B1 오판정 유발. 진짜 B2 앵커(LC241)는 주차자리에서 -91~-94뿐이라 문턱에 걸려 B2 확정 실패.
        //   RSSI 문턱으로는 "B1이라 강함" vs "이동기기가 옆이라 강함"을 구분 불가 → 앵커 자격 박탈.
        //   ("SYEz"는 이름도 고유하지 않음: 07-20 18:49 다른 MAC(18:04:ED…)도 같은 이름으로 관측)
    )

    /**
     * 층 누설 방어용 per-anchor 최소 수신 강도.
     * 현재 등록 앵커 없음 (SYEz 제거 후 문턱 불필요 — LC241/삼성/keybox는 문턱 없이 인정).
     * 반대층 누설이 재발하면 여기에 (MAC → 최소 RSSI)를 다시 추가.
     */
    private val MIN_RSSI = emptyMap<String, Int>()

    /** 층 판정에 이 수신 강도를 인정할지 (문턱 미등록 앵커는 무조건 인정) */
    fun passes(mac: String, rssi: Int): Boolean =
        rssi >= (MIN_RSSI[mac.uppercase()] ?: Int.MIN_VALUE)

    // 2층에만 잡히는 고정-MAC 기기
    val F2_ANCHORS = setOf(
        "74:46:B3:D4:3E:B5",  // "LC241/2/3/CT/SI/PD" (조명 컨트롤러 추정)
        "74:46:B3:D4:87:BC",
        "74:46:B3:D4:85:62"
    )

    val ALL_ANCHORS: Set<String> = F1_ANCHORS + F2_ANCHORS

    /**
     * 앵커 광고를 주소 타입과 무관하게 통과시키기 위한 이름 필터.
     *
     * 배경(2026-07-10 진단): ScanFilter.setDeviceAddress(mac) 1-인자 버전은 주소 타입을
     * PUBLIC으로 가정한다. 앵커가 랜덤 주소 타입(TxAdd=random)으로 광고하면 필터가
     * 영원히 안 걸려서, 백그라운드 필터 스캔은 "정상 시작" 후 결과 0건이 된다
     * (keybox DD:…는 정적 랜덤 대역, LC241 74:…도 랜덤 대역 첫 바이트).
     * 이름 필터로 광고를 통과시키고, 층 판정은 콜백에서 MAC으로 재검증한다
     * (같은 이름의 다른 LC241 유닛은 MAC이 앵커 목록에 없으므로 무시됨).
     */
    val ANCHOR_NAMES = setOf(
        "LC241/2/3/CT/SI/PD",    // 2층 조명 컨트롤러 3대 공통 이름
        "keybox_xxxx",           // 1층 keybox
        "S71767d2deea6940dC"     // 1층 삼성 기기 7C:72:E7 (3주 로그에서 이름 불변 확인)
        // "SYEz" 제거 (2026-07-20): 이동 애플 기기라 앵커 박탈, 게다가 이름이 고유하지 않음
    )

    /** 참고용: 도어 비콘 iBeacon UUID (층 구분엔 못 쓰지만 "문 근처" 트리거로 유용) */
    const val DOOR_BEACON_UUID = "cf2409fe-81e4-4e00-f8ee-eeff00000000"

    /** MAC → 층(1/2), 앵커 아니면 0 */
    fun anchorFloor(mac: String): Int = when (mac.uppercase()) {
        in F1_ANCHORS -> 1
        in F2_ANCHORS -> 2
        else -> 0
    }
}
