package com.example.billeteradistribuida

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Conexión WebSocket con reconexión automática mientras la pantalla está activa. */
class BilleteraSocket(
    private val scope: CoroutineScope,
    private val cuenta: String,
    private val token: String,
    private val onEstado: (String) -> Unit,
    private val onRecibido: suspend (OperacionRespuesta) -> Unit,
    private val alConectar: suspend () -> Unit
) {
    private val cliente = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var activo = false
    private var job: Job? = null
    private var websocket: WebSocket? = null

    fun iniciar() {
        if (activo) return
        activo = true
        job = scope.launch {
            var demora = 1000L
            while (isActive && activo) {
                onEstado("Conectando...")
                val finalizo = CompletableDeferred<Unit>()
                val peticion = Request.Builder()
                    .url("${URL_SOCKET_BASE}ws/$cuenta")
                    .header("Authorization", "Bearer $token")
                    .build()

                try {
                    websocket = cliente.newWebSocket(peticion, object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            scope.launch {
                                if (activo) {
                                    onEstado("En línea")
                                    // Recupera movimientos que llegaron estando desconectados.
                                    try { alConectar() } catch (_: Exception) { /* se reintentará */ }
                                }
                            }
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val recibo = try { leerTransferencia(text) } catch (_: Exception) { null }
                            if (recibo != null && recibo.cuenta == cuenta) {
                                scope.launch {
                                    try { onRecibido(recibo) }
                                    catch (_: Exception) { /* quedará para sincronizar */ }
                                }
                            }
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            if (code == 1008) {
                                activo = false
                                scope.launch { onEstado("Sesión vencida; vuelve a iniciar sesión") }
                            }
                            webSocket.close(1000, null)
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            finalizo.complete(Unit)
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            if (response?.code == 401 || response?.code == 403) {
                                activo = false
                                scope.launch { onEstado("Sesión vencida; vuelve a iniciar sesión") }
                            }
                            finalizo.complete(Unit)
                        }
                    })
                    finalizo.await()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // La aplicación no se cierra por una caída del servidor.
                } finally {
                    websocket?.cancel()
                    websocket = null
                }

                if (activo && isActive) {
                    onEstado("Desconectado · reintentando")
                    delay(demora)
                    demora = (demora * 2).coerceAtMost(10000L)
                }
            }
        }
    }

    fun detener() {
        activo = false
        websocket?.close(1000, "Pantalla cerrada")
        websocket?.cancel()
        websocket = null
        job?.cancel()
        job = null
        onEstado("Desconectado")
    }

    private fun leerTransferencia(texto: String): OperacionRespuesta? {
        val json = JSONObject(texto)
        if (json.optString("evento") != "TRANSFERENCIA_RECIBIDA") return null
        val m = json.getJSONObject("movimiento")
        return OperacionRespuesta(
            id = m.getLong("id"),
            cuenta = m.getString("cuenta"),
            tipo = m.getString("tipo"),
            montoCentavos = m.getLong("montoCentavos"),
            detalle = m.getString("detalle"),
            fechaMillis = m.getLong("fechaMillis"),
            saldoCentavos = m.getLong("saldoCentavos")
        )
    }
}
