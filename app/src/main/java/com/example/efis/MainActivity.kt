package com.example.efis

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.dronefleet.mavlink.MavlinkConnection
import io.dronefleet.mavlink.common.Attitude
//import io.dronefleet.mavlink.common.Heartbeat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.UUID
import kotlin.math.PI
// ui
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

//ui end
private const val CRIUS_NAME = "CRIUS_BT"
private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

class MainActivity : ComponentActivity() {

    private val reqPerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* ignore */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Запрос прав Android 12+
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            need += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            need += Manifest.permission.BLUETOOTH_SCAN
        }
        if (need.isNotEmpty()) reqPerms.launch(need.toTypedArray())

        setContent {
            EfisScreen()
        }
    }

    @Composable
    private fun EfisScreen() {
        val scope = rememberCoroutineScope()

        var status by remember { mutableStateOf("Idle") }
        var bytesPerSec by remember { mutableStateOf(0) }

        var msgsPerSec by remember { mutableStateOf(0) }
        var hbCount by remember { mutableStateOf(0) }
        var rollDeg by remember { mutableStateOf(0f) }
        var pitchDeg by remember { mutableStateOf(0f) }

        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Status: $status")
            Spacer(Modifier.height(8.dp))
            Text("RX: $bytesPerSec B/s")
            Spacer(Modifier.height(8.dp))
            Text("MAV: $msgsPerSec msg/s, HB: $hbCount")
            Spacer(Modifier.height(8.dp))
            Text("Roll: ${"%.1f".format(rollDeg)}°, Pitch: ${"%.1f".format(pitchDeg)}°")
            Spacer(Modifier.height(16.dp))
//ui
            Spacer(Modifier.height(16.dp))

            AttitudeIndicator(
                rollDeg = rollDeg,
                pitchDeg = pitchDeg,
                size = 260.dp
            )

            Spacer(Modifier.height(8.dp))
            Text("Roll ${"%.1f".format(rollDeg)}°, Pitch ${"%.1f".format(pitchDeg)}°", fontSize = 14.sp)

            //endui

            Button(onClick = {
                scope.launch {
                    try {
                        status = "Connecting..."
                        val sock = connectCrius()
                        val cin = CountingInputStream(sock.inputStream)

                        mavlinkLoop(
                            input = cin,
                            output = sock.outputStream,
                            onStats = { mps, hbTotal, bps ->
                                msgsPerSec = mps
                                hbCount = hbTotal
                                bytesPerSec = bps
                            },
                            onAtt = { rollRad, pitchRad ->
                                rollDeg = rollRad * 180f / PI.toFloat()
                                pitchDeg = pitchRad * 180f / PI.toFloat()
                            }
                        )


                    } catch (e: Exception) {
                        status = "Error: ${e.javaClass.simpleName}: ${e.message}"
                    }
                }
            }) {
                Text("Connect CRIUS_BT")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectCrius(): android.bluetooth.BluetoothSocket {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: throw IllegalStateException("BluetoothAdapter == null")

        val dev = adapter.bondedDevices.firstOrNull { it.name == CRIUS_NAME }
            ?: throw IllegalStateException("$CRIUS_NAME not bonded (pair it in Android Bluetooth settings)")

        val sock = dev.createRfcommSocketToServiceRecord(SPP_UUID)
        adapter.cancelDiscovery()
        sock.connect()
        return sock
    }



    private  suspend fun mavlinkLoop(
        input: InputStream,
        output: java.io.OutputStream,
        onStats: (msgsPerSec: Int, heartbeatCount: Int, bytesPerSec: Int) -> Unit,
        onAtt: (rollRad: Float, pitchRad: Float) -> Unit
    ) {
        val conn = MavlinkConnection.create(input, output)

        var accMsgs = 0
        var hb = 0
        var last = System.currentTimeMillis()

        var lastBytesTotal = (input as? CountingInputStream)?.total ?: 0L

        while (true) {
            val msg = withContext(Dispatchers.IO) { conn.next() } ?: break
            accMsgs++

            when (val p = msg.payload) {
                is Attitude -> onAtt(p.roll(), p.pitch())
            }

            val now = System.currentTimeMillis()
            if (now - last >= 1000) {
                val curTotal = (input as? CountingInputStream)?.total ?: lastBytesTotal
                val bps = (curTotal - lastBytesTotal).toInt().coerceAtLeast(0)
                lastBytesTotal = curTotal

                onStats(accMsgs, hb, bps)

                accMsgs = 0
                last = now
            }
        }
    }
    @Composable
    fun AttitudeIndicator(
        rollDeg: Float,
        pitchDeg: Float,
        size: Dp
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(Color(0xFF101010)),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {

                val cx = this.size.width / 2f
                val cy = this.size.height / 2f
                val radius = (this.size.minDimension / 2f) * 0.95f

                // pitch: смещение горизонта (грубо: 1° = 3px при 260dp; потом можно откалибровать)
                val pxPerDeg = this.size.minDimension / 90f
                val pitchOffset = -pitchDeg * pxPerDeg

                // рисуем "небо/землю" в повернутой системе (roll)
                rotate(degrees = -rollDeg, pivot = Offset(cx, cy)) {
                    // клип в круг
                    clipRect(left = cx - radius, top = cy - radius, right = cx + radius, bottom = cy + radius) {

                        // Небо
                        drawRect(
                            color = Color(0xFF2B6CB0),
                            topLeft = Offset(cx - radius, cy - radius + pitchOffset),
                            size = Size(radius * 2, radius)
                        )
                        // Земля
                        drawRect(
                            color = Color(0xFF8B5A2B),
                            topLeft = Offset(cx - radius, cy + pitchOffset),
                            size = Size(radius * 2, radius)
                        )

                        // Линия горизонта
                        drawLine(
                            color = Color.White,
                            start = Offset(cx - radius, cy + pitchOffset),
                            end = Offset(cx + radius, cy + pitchOffset),
                            strokeWidth = 4f
                        )

                        // Pitch-метки (каждые 10°)
                        for (p in -30..30 step 10) {
                            val y = cy + pitchOffset + (-p * pxPerDeg)
                            val len = if (p == 0) radius * 0.9f else radius * 0.45f
                            drawLine(
                                color = Color.White,
                                start = Offset(cx - len / 2f, y),
                                end = Offset(cx + len / 2f, y),
                                strokeWidth = 2f
                            )
                        }
                    }
                }

                // Фиксированный самолетик (символ)
                drawLine(Color.Yellow, Offset(cx - radius * 0.35f, cy), Offset(cx - radius * 0.10f, cy), 5f)
                drawLine(Color.Yellow, Offset(cx + radius * 0.10f, cy), Offset(cx + radius * 0.35f, cy), 5f)
                drawLine(Color.Yellow, Offset(cx, cy - radius * 0.05f), Offset(cx, cy + radius * 0.12f), 5f)

                // Шкала крена (внешняя дуга + риски)
                val arcRect = Rect(cx - radius, cy - radius, cx + radius, cy + radius)
                // тонкое кольцо
                drawArc(
                    color = Color(0xFFDDDDDD),
                    startAngle = 210f,
                    sweepAngle = 120f,
                    useCenter = false,
                    topLeft = Offset(arcRect.left, arcRect.top),
                    size = Size(arcRect.width, arcRect.height),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f)
                )

                // риски каждые 30°
                val ticks = listOf(-60f, -30f, 0f, 30f, 60f)
                for (t in ticks) {
                    val ang = Math.toRadians((270f + t).toDouble())
                    val r1 = radius * 0.92f
                    val r2 = radius * 0.80f
                    val x1 = cx + (r1 * cos(ang)).toFloat()
                    val y1 = cy + (r1 * sin(ang)).toFloat()
                    val x2 = cx + (r2 * cos(ang)).toFloat()
                    val y2 = cy + (r2 * sin(ang)).toFloat()
                    drawLine(Color.White, Offset(x1, y1), Offset(x2, y2), strokeWidth = 3f)
                }

                // Индекс крена (треугольник сверху, показывает текущий roll)
                rotate(degrees = -rollDeg, pivot = Offset(cx, cy)) {
                    val top = Offset(cx, cy - radius * 0.98f)
                    val left = Offset(cx - 14f, cy - radius * 0.90f)
                    val right = Offset(cx + 14f, cy - radius * 0.90f)
                    val path = Path().apply {
                        moveTo(top.x, top.y)
                        lineTo(left.x, left.y)
                        lineTo(right.x, right.y)
                        close()
                    }
                    drawPath(path, Color.Yellow)
                }
            }
        }
    }

}

