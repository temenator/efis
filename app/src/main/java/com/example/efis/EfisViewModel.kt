package com.example.efis

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EfisViewModel(context: Context) {

    val state = mutableStateOf(EfisState())
    private val client = BtMavlinkClient(context)

    @Volatile private var connecting = false

    fun connect(scope: CoroutineScope) {
        if (connecting) return
        connecting = true

        scope.launch(Dispatchers.IO) {
            try {
                // 1) статус -> Main
                withContext(Dispatchers.Main) {
                    state.value = state.value.copy(status = "Connecting…")
                }

                val conn = client.connect()

                // 2) статус -> Main
                withContext(Dispatchers.Main) {
                    state.value = state.value.copy(status = "Connected")
                }

                // 3) читать MAVLink можно на IO, но обновления state -> Main
                client.readLoop(
                    conn,
                    onPacket = { r, p ->
                        scope.launch(Dispatchers.Main) {
                            state.value = state.value.copy(rollDeg = r, pitchDeg = p)
                        }
                    },
                    onStats = { mps ->
                        scope.launch(Dispatchers.Main) {
                            state.value = state.value.copy(msgsPerSec = mps)
                        }
                    }
                )

                // Если readLoop завершился (разрыв) — покажем это
                withContext(Dispatchers.Main) {
                    state.value = state.value.copy(status = "Disconnected")
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    state.value = state.value.copy(status = "Error: ${e.message}")
                }
            } finally {
                connecting = false
            }
        }
    }
}
