package com.example.efis

import android.Manifest
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
import androidx.compose.ui.Alignment
class MainActivity : ComponentActivity() {

    private val reqPerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // permissions Android 12+
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.BLUETOOTH_CONNECT
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.BLUETOOTH_SCAN
        if (need.isNotEmpty()) reqPerms.launch(need.toTypedArray())



                setContent {
                    val scope = rememberCoroutineScope()
                    val vm = remember { EfisViewModel(applicationContext) }
                    val s by vm.state

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        // Верхняя панель
                        Text("Status: ${s.status}")
                        Spacer(Modifier.height(6.dp))
                        Text("RX: ${s.bytesPerSec} B/s | MAV: ${s.msgsPerSec} msg/s | HB: ${s.hbCount}")
                        Spacer(Modifier.height(6.dp))
                        Text("Roll: ${"%.1f".format(s.rollDeg)}°, Pitch: ${"%.1f".format(s.pitchDeg)}°")

                        // Центр: горизонт
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Horizon(
                                rollDeg = s.rollDeg,
                                pitchDeg = s.pitchDeg,
                                modifier = Modifier.size(300.dp)
                            )
                        }

                        // Низ: кнопка
                        Button(
                            onClick = { vm.connect(scope) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Connect CRIUS_BT")
                        }
                    }
                }

    }
}
