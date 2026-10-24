package com.example.billeteradistribuida

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Movimiento::class], version = 2, exportSchema = false)
abstract class BilleteraDatabase : RoomDatabase() {
    abstract fun movimientoDao(): MovimientoDao

    companion object {
        // Separar físicamente los historiales locales de cada usuario.
        private val instancias = mutableMapOf<String, BilleteraDatabase>()

        // No borra el historial de la Etapa 4.
        private val MIGRACION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE movimientos ADD COLUMN serverId INTEGER")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                            "index_movimientos_serverId ON movimientos(serverId)"
                )
            }
        }

        fun obtener(context: Context, cuenta: String): BilleteraDatabase {
            require(Regex("C[A-F0-9]{20}").matches(cuenta)) {
                "Formato de cuenta inválido"
            }
            return synchronized(this) {
                instancias.getOrPut(cuenta) {
                    Room.databaseBuilder(
                        context.applicationContext,
                        BilleteraDatabase::class.java,
                        "billetera_${cuenta}.db"
                    )
                        .addMigrations(MIGRACION_1_2)
                        .build()
                }
            }
        }
    }
}
