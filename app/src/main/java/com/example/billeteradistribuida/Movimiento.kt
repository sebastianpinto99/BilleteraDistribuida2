package com.example.billeteradistribuida

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "movimientos",
    indices = [Index(value = ["serverId"], unique = true)]
)
data class Movimiento(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val tipo: String,
    val montoCentavos: Long,
    val detalle: String,
    val fechaMillis: Long,
    val esDemo: Boolean = false,
    // Nulo en movimientos antiguos y en los registros de demostración.
    val serverId: Long? = null
)
