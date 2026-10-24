package com.example.billeteradistribuida

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MovimientoDao {
    @Insert
    suspend fun insertar(movimiento: Movimiento)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertarSinDuplicar(movimiento: Movimiento): Long

    @Query("SELECT * FROM movimientos ORDER BY fechaMillis DESC, id DESC")
    fun observarTodos(): Flow<List<Movimiento>>

    @Query("DELETE FROM movimientos WHERE esDemo = 1")
    suspend fun borrarMovimientosDemo()

    @Query("SELECT EXISTS(SELECT 1 FROM movimientos WHERE serverId = :idServidor)")
    suspend fun yaExisteEnRoom(idServidor: Long): Boolean

    // Vincula a su ID remoto un movimiento anterior a esta etapa sin duplicarlo.
    @Query("""
        UPDATE movimientos SET serverId = :idServidor
        WHERE id = (
            SELECT id FROM movimientos
            WHERE serverId IS NULL AND esDemo = 0
              AND tipo = :tipo AND montoCentavos = :montoCentavos
              AND detalle = :detalle AND fechaMillis = :fechaMillis
            ORDER BY id LIMIT 1
        )
    """)
    suspend fun vincularMovimientoAnterior(
        idServidor: Long,
        tipo: String,
        montoCentavos: Long,
        detalle: String,
        fechaMillis: Long
    ): Int
}
