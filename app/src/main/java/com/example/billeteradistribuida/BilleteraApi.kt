package com.example.billeteradistribuida

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

// Solo para el emulador y la prueba local. En Render cambiar a https://.../
const val URL_REST = "https://billeteradistribuida-demo.onrender.com/"
const val URL_SOCKET_BASE = "wss://billeteradistribuida-demo.onrender.com/"

data class CuentaRespuesta(
    val cuenta: String,
    val saldoCentavos: Long
)

data class MontoSolicitud(
    val cuenta: String,
    val montoCentavos: Long
)

data class TransferenciaSolicitud(
    val cuenta: String,
    val montoCentavos: Long,
    val destino: String
)

data class ServicioSolicitud(
    val cuenta: String,
    val montoCentavos: Long,
    val codigoContrato: String
)

data class MovimientoRemoto(
    val id: Long,
    val cuenta: String,
    val tipo: String,
    val montoCentavos: Long,
    val detalle: String,
    val fechaMillis: Long
)

data class OperacionRespuesta(
    val id: Long,
    val cuenta: String,
    val tipo: String,
    val montoCentavos: Long,
    val detalle: String,
    val fechaMillis: Long,
    val saldoCentavos: Long
)

data class Credenciales(val email: String, val password: String)

data class RespuestaAcceso(
    val access_token: String,
    val token_type: String,
    val cuenta: String
)

interface AuthApi {
    @POST("auth/registro")
    suspend fun registrar(@Body datos: Credenciales): RespuestaAcceso

    @POST("auth/login")
    suspend fun login(@Body datos: Credenciales): RespuestaAcceso
}

interface BilleteraApi {
    @GET("cuentas/{cuenta}")
    suspend fun consultarCuenta(@Path("cuenta") cuenta: String): CuentaRespuesta

    @GET("movimientos/{cuenta}")
    suspend fun obtenerMovimientos(@Path("cuenta") cuenta: String): List<MovimientoRemoto>

    @POST("depositos")
    suspend fun depositar(@Header("Idempotency-Key") clave: String, @Body datos: MontoSolicitud): OperacionRespuesta

    @POST("retiros")
    suspend fun retirar(@Header("Idempotency-Key") clave: String, @Body datos: MontoSolicitud): OperacionRespuesta

    @POST("transferencias")
    suspend fun transferir(@Header("Idempotency-Key") clave: String, @Body datos: TransferenciaSolicitud): OperacionRespuesta

    @POST("servicios/pagar")
    suspend fun pagarServicio(@Header("Idempotency-Key") clave: String, @Body datos: ServicioSolicitud): OperacionRespuesta
}

object BilleteraCliente {
    // Cliente sin token: exclusivamente para registro e inicio de sesión.
    val authApi: AuthApi by lazy {
        Retrofit.Builder()
            .baseUrl(URL_REST)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AuthApi::class.java)
    }

    fun crearApi(token: String): BilleteraApi {
        val cliente = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
                chain.proceed(request)
            }
            .build()
        return Retrofit.Builder()
            .baseUrl(URL_REST)
            .client(cliente)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(BilleteraApi::class.java)
    }
}
