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

    /** 참고용: 도어 비콘 iBeacon UUID (층 구분엔 못 쓰지만 "문 근처" 트리거로 유용) */
    const val DOOR_BEACON_UUID = "cf2409fe-81e4-4e00-f8ee-eeff00000000"

    /** MAC → 층(1/2), 앵커 아니면 0 */
    fun anchorFloor(mac: String): Int = when (mac.uppercase()) {
        in F1_ANCHORS -> 1
        in F2_ANCHORS -> 2
        else -> 0
    }
}
