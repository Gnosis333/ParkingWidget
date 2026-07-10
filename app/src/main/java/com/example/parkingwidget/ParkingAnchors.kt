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
        "7C:72:E7:9F:FE:F8",  // 삼성 기기 "S717…"
        "DD:57:E3:E3:1B:64"   // keybox_xxxx (iBeacon UUID 92428ea0…)
    )

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
