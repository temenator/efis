package com.example.efis

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class EfisViewModel(context: Context) {

    val state = mutableStateOf(EfisState())
    private val client = BtMavlinkClient(context)

    fun connect(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            try {
                state.value = state.value.copy(status = "Connecting…")
                val conn = client.connect()

                state.value = state.value.copy(status = "Connected")

                client.readLoop(
                    conn,
                    onPacket = { r, p ->
                        state.value = state.value.copy(rollDeg = r, pitchDeg = p)
                    },
                    onStats = { mps ->
                        state.value = state.value.copy(msgsPerSec = mps)
                    }
                )

            } catch (e: Exception) {
                state.value = state.value.copy(status = "Error: ${e.message}")
            }
        }
    }
}
