package com.example.billeteradistribuida

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Guarda respuestas REST, eventos WebSocket y sincronizaciones en forma idempotente. */
class RepositorioMovimientos(private val dao: MovimientoDao) {
    private val mutex = Mutex()

    suspend fun guardar(remoto: MovimientoRemoto): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (dao.yaExisteEnRoom(remoto.id)) return@withLock

            val asociados = dao.vincularMovimientoAnterior(
                idServidor = remoto.id,
                tipo = remoto.tipo,
                montoCentavos = remoto.montoCentavos,
                detalle = remoto.detalle,
                fechaMillis = remoto.fechaMillis
            )
            if (asociados == 0) {
                dao.insertarSinDuplicar(
                    Movimiento(
                        tipo = remoto.tipo,
                        montoCentavos = remoto.montoCentavos,
                        detalle = remoto.detalle,
                        fechaMillis = remoto.fechaMillis,
                        esDemo = false,
                        serverId = remoto.id
                    )
                )
            }
        }
    }

    suspend fun guardar(respuesta: OperacionRespuesta) {
        guardar(
            MovimientoRemoto(
                id = respuesta.id,
                cuenta = respuesta.cuenta,
                tipo = respuesta.tipo,
                montoCentavos = respuesta.montoCentavos,
                detalle = respuesta.detalle,
                fechaMillis = respuesta.fechaMillis
            )
        )
    }
}
