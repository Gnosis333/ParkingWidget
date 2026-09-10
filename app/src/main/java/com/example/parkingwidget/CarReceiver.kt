package com.example.parkingwidget

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 차량 오디오(핸즈프리/카오디오) 링크 판별 — 서비스와 매니페스트 리시버 공용.
 *
 * 차량만 골라내는 근거: 본딩된 오디오 기기의 BluetoothClass deviceClass가
 * 차량은 0x0408(HANDSFREE) / 0x0420(CAR_AUDIO)이고, 이어버드는 0x0404
 * (WEARABLE_HEADSET) / 0x0418(HEADPHONES)이라 서로 겹치지 않는다.
 * (실측 2026-09-10: 차량 "K5" 0x240408, "Buds2" 0x240404)
 * → 이어버드를 뺐다 꽂는 건 주차 이벤트로 오인되지 않는다.
 */
object CarAudio {

    @SuppressLint("MissingPermission")   // BLUETOOTH_CONNECT는 MainActivity에서 요청·보유
    fun isCarAudio(device: BluetoothDevice): Boolean = try {
        when (device.bluetoothClass?.deviceClass) {
            BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE,
            BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> true
            else -> false
        }
    } catch (_: SecurityException) { false }

    /** 로그용 표기 (권한이 없어 이름을 못 읽으면 MAC 뒷자리만). */
    @SuppressLint("MissingPermission")
    fun label(device: BluetoothDevice): String = try {
        val name = device.name
        if (name.isNullOrBlank()) device.address.takeLast(8) else "$name ${device.address.takeLast(8)}"
    } catch (_: SecurityException) { device.address.takeLast(8) }

    /** 브로드캐스트에서 기기 꺼내기 (API 33 이전 호환) */
    @Suppress("DEPRECATION")
    fun deviceOf(intent: Intent): BluetoothDevice? =
        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
}

/**
 * 서비스가 죽어 있을 때를 위한 주차 이벤트 백업 경로 (매니페스트 등록).
 *
 * 서비스가 살아 있으면 서비스의 런타임 리시버가 같은 브로드캐스트를 이미 처리하므로
 * 여기선 아무것도 하지 않는다(같은 프로세스라 isRunning이 유효한 판별자).
 * ACL_CONNECTED/DISCONNECTED는 안드로이드의 암시적 브로드캐스트 제한 예외라
 * 매니페스트 등록으로도 배달된다.
 */
class CarReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val device = CarAudio.deviceOf(intent) ?: return
        if (!CarAudio.isCarAudio(device)) return
        if (BleMonitorService.isRunning) return   // 서비스가 처리 중 — 중복 방지

        val label = CarAudio.label(device)
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                MonitorLog.log(context, "주차 감지 [서비스 꺼짐] (차량 BT 해제: $label) → 서비스 기동")
                // 서비스 기동이 거부되더라도 "미확인" 표시는 남도록 상태를 먼저 저장한다.
                ParkingState.markParked(context)
                ParkingWidgetProvider.refreshAll(context)
                try {
                    ParkingWidgetProvider.startMonitorForParking(context, label)
                } catch (e: Exception) {
                    MonitorLog.log(context, "주차 이벤트 서비스 시작 실패: ${e.message}")
                }
            }
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                ParkingState.clearParked(context)
                ParkingWidgetProvider.refreshAll(context)
            }
        }
    }
}
