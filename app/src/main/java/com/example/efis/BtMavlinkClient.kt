package com.example.efis

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import io.dronefleet.mavlink.MavlinkConnection
import io.dronefleet.mavlink.common.Attitude
import java.util.UUID
import kotlin.math.PI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BtMavlinkClient(
    private val context: Context,
    private val deviceName: String = "CRIUS_BT"
) {
    private val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    @SuppressLint("MissingPermission")
    fun connect(): MavlinkConnection {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: error("BluetoothAdapter null")

        val dev = adapter.bondedDevices.firstOrNull { it.name == deviceName }
            ?: error("Device $deviceName not paired")

        val sock = dev.createRfcommSocketToServiceRecord(uuid)
        adapter.cancelDiscovery()
        sock.connect()

        return MavlinkConnection.create(sock.inputStream, sock.outputStream)
    }

    suspend fun readLoop(
        conn: MavlinkConnection,
        onPacket: (rollDeg: Float, pitchDeg: Float) -> Unit,
        onStats: (msgsPerSec: Int) -> Unit
    ) {
        var count = 0
        var last = System.currentTimeMillis()

        while (true) {
            val msg = withContext(Dispatchers.IO) { conn.next() } ?: break
            count++

            val p = msg.payload
            if (p is Attitude) {
                val r = p.roll() * 180f / PI.toFloat()
                val pt = p.pitch() * 180f / PI.toFloat()
                onPacket(r, pt)
            }

            val now = System.currentTimeMillis()
            if (now - last >= 1000) {
                onStats(count)
                count = 0
                last = now
            }
        }
    }
}
