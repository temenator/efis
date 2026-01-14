package com.example.efis

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import io.dronefleet.mavlink.MavlinkConnection
import io.dronefleet.mavlink.common.Attitude
import io.dronefleet.mavlink.common.CommandAck
import io.dronefleet.mavlink.common.CommandLong
import io.dronefleet.mavlink.common.MavCmd
import io.dronefleet.mavlink.minimal.Heartbeat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.PI
import io.dronefleet.mavlink.common.VfrHud
import io.dronefleet.mavlink.common.GlobalPositionInt
import io.dronefleet.mavlink.common.GpsRawInt
import io.dronefleet.mavlink.common.SysStatus

class BtMavlinkClient(
    private val context: Context,
    private val deviceName: String = "CRIUS_BT"
) {
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    @SuppressLint("MissingPermission")
    fun connect(): MavlinkConnection {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: error("BluetoothAdapter null")

        val dev = adapter.bondedDevices.firstOrNull { it.name == deviceName }
            ?: error("Device $deviceName not paired")

        val sock = dev.createRfcommSocketToServiceRecord(sppUuid)
        adapter.cancelDiscovery()
        sock.connect()

        return MavlinkConnection.create(sock.inputStream, sock.outputStream)
    }

    /**
     * Главный цикл чтения:
     * 1) детект sys/comp
     * 2) выставляем rate
     * 3) читаем пакеты, считаем msg/s и обновляем roll/pitch
     */
    suspend fun run(
        conn: MavlinkConnection,
        onAttDeg: (rollDeg: Float, pitchDeg: Float) -> Unit,
        onMsgsPerSec: (msgsPerSec: Int) -> Unit
    ) {
        val (sysId, compId) = detectTarget(conn)
        android.util.Log.i("EFIS_RATE", "Target detected sys=$sysId comp=$compId")

        // Пытаемся выставить rates (с логом ACK/NoACK)
        sendEfisRates(conn, sysId, compId)

        // Основной read-loop + статистика
        readLoop(conn, onAttDeg, onMsgsPerSec, sysId, compId)
    }

    /**
     * Детектируем sysId/compId из первых входящих сообщений.
     * Если первым пришёл Heartbeat — отлично. Если нет — берём первый пакет.
     */
    private suspend fun detectTarget(conn: MavlinkConnection): Pair<Int, Int> {
        while (true) {
            val msg = withContext(Dispatchers.IO) { conn.next() } ?: error("No MAVLink data")

            val sys = msg.originSystemId
            val comp = msg.originComponentId

            val payload = msg.payload
            if (payload is Heartbeat) {
                android.util.Log.i("EFIS_RATE", "Detected HEARTBEAT from $sys/$comp")
                return sys to comp
            }

            android.util.Log.i("EFIS_RATE", "Detected first MAVLink from $sys/$comp payload=${payload?.javaClass?.simpleName}")
            return sys to comp
        }
    }



    /**
     * Основной цикл чтения.
     * Статистику считаем только по message id, roll/pitch берём из Attitude payload.
     */
    suspend fun readLoop(
        conn: MavlinkConnection,
        onAttDeg: (Float, Float) -> Unit,
        onMsgsPerSec: (Int) -> Unit,
        sysId: Int,
        compId: Int
    ) {
        var all = 0
        var att = 0
        var vfr = 0
        var gpos = 0
        var gps = 0
        var sys = 0

        var last = System.currentTimeMillis()

        while (true) {
            val msg = withContext(Dispatchers.IO) { conn.next() } ?: break
            all++

            // UI данные
            (msg.payload as? Attitude)?.let { p ->
                val rDeg = p.roll() * 180f / PI.toFloat()
                val pDeg = p.pitch() * 180f / PI.toFloat()
                onAttDeg(rDeg, pDeg)
            }

            // Статистика по ID
            when (msg.payload) {
                is Attitude -> att++
                is VfrHud -> vfr++
                is GlobalPositionInt -> gpos++
                is GpsRawInt -> gps++
                is SysStatus -> sys++
            }


            val now = System.currentTimeMillis()
            if (now - last >= 1000) {
                onMsgsPerSec(all)

                android.util.Log.i(
                    "EFIS_RATE",
                    "ALL=$all ATT=$att VFR=$vfr GPOS=$gpos GPS=$gps SYS=$sys (target=$sysId/$compId)"
                )

                all = 0; att = 0; vfr = 0; gpos = 0; gps = 0; sys = 0
                last = now
            }
        }
    }

    /**
     * Выставляем rate для нужных сообщений.
     * Интервал в микросекундах.
     * В ArduPilot обычно возвращается COMMAND_ACK, но не всегда.
     */
    private suspend fun sendEfisRates(conn: MavlinkConnection, targetSys: Int, targetComp: Int) {
        // 20Hz attitude, 10Hz VFR & global pos, 5Hz GPS raw, 1Hz sys status
        setMessageInterval(conn, targetSys, targetComp, MavMsg.ATTITUDE, 50_000)
        setMessageInterval(conn, targetSys, targetComp, MavMsg.VFR_HUD, 100_000)
        setMessageInterval(conn, targetSys, targetComp, MavMsg.GLOBAL_POSITION_INT, 100_000)
        setMessageInterval(conn, targetSys, targetComp, MavMsg.GPS_RAW_INT, 200_000)
        setMessageInterval(conn, targetSys, targetComp, MavMsg.SYS_STATUS, 1_000_000)
    }

    /**
     * MAV_CMD_SET_MESSAGE_INTERVAL:
     * param1 = msgId
     * param2 = intervalUs
     */
    private suspend fun setMessageInterval(
        conn: MavlinkConnection,
        targetSys: Int,
        targetComp: Int,
        msgId: Int,
        intervalUs: Int
    ) {
        val cmd = CommandLong.builder()
            .targetSystem(targetSys)
            .targetComponent(targetComp)
            .command(MavCmd.MAV_CMD_SET_MESSAGE_INTERVAL)
            .param1(msgId.toFloat())
            .param2(intervalUs.toFloat())
            .build()

        // отправка (255/0 — как GCS)
        withContext(Dispatchers.IO) {
            conn.send1(255, 0, cmd)
        }

        // Пытаемся поймать ACK в течение 300мс (чтобы не "съедать" поток долго)
        val deadline = System.currentTimeMillis() + 300
        while (System.currentTimeMillis() < deadline) {
            val m = withContext(Dispatchers.IO) { conn.next() } ?: break
            val p = m.payload
            if (p is CommandAck && p.command() == MavCmd.MAV_CMD_SET_MESSAGE_INTERVAL) {
                android.util.Log.i(
                    "EFIS_RATE",
                    "ACK msgId=$msgId intervalUs=$intervalUs result=${p.result()}"
                )
                return
            }
            // если это не ACK — пропускаем, чтобы не зависнуть
        }

        android.util.Log.w("EFIS_RATE", "No ACK msgId=$msgId intervalUs=$intervalUs (may still work)")
    }

    private object MavMsg {
        const val SYS_STATUS = 1
        const val GPS_RAW_INT = 24
        const val ATTITUDE = 30
        const val GLOBAL_POSITION_INT = 33
        const val VFR_HUD = 74
    }
}
