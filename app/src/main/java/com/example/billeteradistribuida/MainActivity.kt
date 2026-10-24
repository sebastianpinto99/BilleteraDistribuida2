package com.example.billeteradistribuida

import android.os.Bundle
import android.util.Patterns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import retrofit2.HttpException
import java.io.IOException
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

// Colores de marca. No hacen falta librerías ni recursos adicionales.
private val Fondo = Color(0xFFF5F7FC)
private val AzulNoche = Color(0xFF101E39)
private val AzulMedio = Color(0xFF24477F)
private val VerdeMenta = Color(0xFF15CCAC)
private val VerdeTexto = Color(0xFF087E68)
private val RojoTexto = Color(0xFFDF5268)
private val Morado = Color(0xFF7669D7)
private val GrisTexto = Color(0xFF728098)
private val Borde = Color(0xFFE5EAF2)
private val Blanco = Color.White
private val EcLocale = Locale("es", "EC")

private val EsquemaBilletera = lightColorScheme(
    primary = AzulMedio,
    onPrimary = Blanco,
    background = Fondo,
    onBackground = AzulNoche,
    surface = Blanco,
    onSurface = AzulNoche,
    secondary = VerdeTexto,
    error = RojoTexto
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = EsquemaBilletera) {
                var sesion by remember { mutableStateOf<RespuestaAcceso?>(null) }
                if (sesion == null) {
                    PantallaAcceso(alAcceder = { sesion = it })
                } else {
                    val actual = sesion!!
                    key(actual.cuenta) {
                        PantallaBilletera(
                            cuentaActual = actual.cuenta,
                            token = actual.access_token,
                            alSalir = { sesion = null }
                        )
                    }
                }
            }
        }
    }
}

private fun precio(centavos: Long): String =
    BigDecimal.valueOf(centavos, 2).toPlainString()

private fun mensajeDeError(e: Exception): String = when (e) {
    is HttpException -> {
        val detalle = try {
            JSONObject(e.response()?.errorBody()?.string().orEmpty()).optString("detail")
        } catch (_: Exception) { "" }
        detalle.ifBlank { "Error del servidor (${e.code()})" }
    }
    is IOException -> "No se pudo contactar al servidor. Revisa tu conexión."
    else -> e.localizedMessage ?: "Ocurrió un error inesperado"
}

private fun esIngreso(tipo: String): Boolean =
    tipo == "DEPOSITO" || tipo == "TRANSFERENCIA_RECIBIDA"

private fun nombreMovimiento(tipo: String): String = when (tipo) {
    "DEPOSITO" -> "Depósito"
    "RETIRO" -> "Retiro"
    "TRANSFERENCIA_RECIBIDA" -> "Transferencia recibida"
    "TRANSFERENCIA_ENVIADA" -> "Transferencia enviada"
    "SERVICIO_BASICO" -> "Pago de servicios"
    else -> tipo.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
}

private fun simboloMovimiento(tipo: String): String = when (tipo) {
    "DEPOSITO", "TRANSFERENCIA_RECIBIDA" -> "↙"
    "RETIRO", "TRANSFERENCIA_ENVIADA" -> "↗"
    "SERVICIO_BASICO" -> "ϟ"
    else -> "•"
}

private fun fechaMovimiento(millis: Long): String =
    SimpleDateFormat("dd MMM · HH:mm", EcLocale).format(Date(millis))

private fun saludoDelDia(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Buenos días"
    in 12..18 -> "Buenas tardes"
    else -> "Buenas noches"
}

private data class ActividadDia(val etiqueta: String, val ingresos: Long, val salidas: Long)

// Estadística local. Nunca se usa para calcular o modificar el saldo del servidor.
private fun actividadSemanal(movimientos: List<Movimiento>): List<ActividadDia> {
    val calendario = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, -6)
    }
    val clave = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val agrupados = movimientos.filterNot { it.esDemo }.groupBy { clave.format(Date(it.fechaMillis)) }
    val nombreDia = SimpleDateFormat("EEE", EcLocale)
    return List(7) {
        val movimientosDia = agrupados[clave.format(calendario.time)].orEmpty()
        val actual = ActividadDia(
            nombreDia.format(calendario.time).replaceFirstChar { letra -> letra.uppercase() },
            movimientosDia.filter { esIngreso(it.tipo) }.sumOf { it.montoCentavos },
            movimientosDia.filterNot { esIngreso(it.tipo) }.sumOf { it.montoCentavos }
        )
        calendario.add(Calendar.DAY_OF_YEAR, 1)
        actual
    }
}

@Composable
private fun ActividadSemanal(movimientos: List<Movimiento>) {
    val datos = remember(movimientos) { actividadSemanal(movimientos) }
    val maximo = maxOf(1L, datos.maxOf { maxOf(it.ingresos, it.salidas) })
    Column(
        Modifier.fillMaxWidth()
            .background(Blanco, RoundedCornerShape(24.dp))
            .border(1.dp, Borde, RoundedCornerShape(24.dp))
            .padding(19.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Pulso de tu semana", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = AzulNoche)
                Text("Últimos 7 días · historial local confirmado", fontSize = 11.sp, color = GrisTexto)
            }
            Box(Modifier.background(Color(0xFFEAF6F4), CircleShape)
                .padding(horizontal = 11.dp, vertical = 7.dp)) {
                Text("7 días", color = VerdeTexto, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }
        if (datos.all { it.ingresos == 0L && it.salidas == 0L }) {
            Column(Modifier.fillMaxWidth().height(115.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("✧", fontSize = 30.sp, color = Morado)
                Text("Tu actividad aparecerá aquí", fontSize = 12.sp, color = GrisTexto)
            }
        } else {
            Row(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                datos.forEach { dia ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth().height(94.dp),
                            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
                            Box(
                                Modifier.width(11.dp)
                                    .fillMaxHeight(if (dia.ingresos == 0L) .035f else
                                        (dia.ingresos.toFloat() / maximo).coerceIn(.09f, 1f))
                                    .background(if (dia.ingresos == 0L) Color(0xFFE8EFED) else VerdeMenta,
                                        RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            )
                            Spacer(Modifier.width(4.dp))
                            Box(
                                Modifier.width(11.dp)
                                    .fillMaxHeight(if (dia.salidas == 0L) .035f else
                                        (dia.salidas.toFloat() / maximo).coerceIn(.09f, 1f))
                                    .background(if (dia.salidas == 0L) Color(0xFFF2E5E8) else RojoTexto,
                                        RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            )
                        }
                        Text(dia.etiqueta, fontSize = 10.sp, color = GrisTexto, maxLines = 1)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(9.dp).background(VerdeMenta, CircleShape))
                Text("Ingresos", color = GrisTexto, fontSize = 11.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(9.dp).background(RojoTexto, CircleShape))
                Text("Salidas", color = GrisTexto, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun DetalleMovimiento(movimiento: Movimiento, alCerrar: () -> Unit) {
    val ingreso = esIngreso(movimiento.tipo)
    AlertDialog(
        onDismissRequest = alCerrar,
        shape = RoundedCornerShape(26.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(47.dp).background(
                    if (ingreso) Color(0xFFE2F8F0) else Color(0xFFFFEDF0),
                    RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                    Text(simboloMovimiento(movimiento.tipo), fontSize = 26.sp,
                        fontWeight = FontWeight.Bold, color = if (ingreso) VerdeTexto else RojoTexto)
                }
                Text("Detalle de movimiento", fontSize = 17.sp, color = AzulNoche, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(nombreMovimiento(movimiento.tipo), fontSize = 13.sp, color = GrisTexto)
                Text("${if (ingreso) "+" else "−"} $ ${precio(movimiento.montoCentavos)}",
                    fontSize = 28.sp, fontWeight = FontWeight.ExtraBold,
                    color = if (ingreso) VerdeTexto else RojoTexto)
                HorizontalDivider(color = Borde)
                Text("Descripción", color = GrisTexto, fontSize = 11.sp)
                Text(movimiento.detalle.ifBlank { "Movimiento de cuenta" }, color = AzulNoche)
                Text("Fecha y hora", color = GrisTexto, fontSize = 11.sp)
                Text(SimpleDateFormat("dd/MM/yyyy · HH:mm:ss", EcLocale).format(Date(movimiento.fechaMillis)),
                    color = AzulNoche)
                Text("Origen del registro", color = GrisTexto, fontSize = 11.sp)
                Text(if (movimiento.esDemo) "DEMO · solo guardado en Room"
                else "Operación confirmada por el servidor", fontSize = 12.sp, color = AzulNoche)
                movimiento.serverId?.let { id ->
                    Text("Comprobante del servidor: #$id", color = GrisTexto, fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = alCerrar) { Text("Cerrar", fontWeight = FontWeight.Bold) }
        }
    )
}

@Composable
private fun Marca(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(44.dp)
            .background(Brush.linearGradient(listOf(VerdeMenta, Color(0xFF5BDED1))), RoundedCornerShape(15.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text("◈", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = AzulNoche)
    }
}

@Composable
private fun EncabezadoSeccion(titulo: String, subtitulo: String? = null, accion: String? = null, alPulsar: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(titulo, fontWeight = FontWeight.Bold, fontSize = 19.sp, color = AzulNoche)
            if (subtitulo != null) Text(subtitulo, color = GrisTexto, fontSize = 12.sp)
        }
        if (accion != null) TextButton(onClick = alPulsar) {
            Text(accion, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
    }
}

@Composable
private fun EtiquetaEstado(texto: String, activo: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(if (activo) Color(0xFFE5F9F2) else Color(0xFFFFF0F0), CircleShape)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(7.dp).background(if (activo) VerdeTexto else RojoTexto, CircleShape))
        Text(texto, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            color = if (activo) VerdeTexto else RojoTexto, maxLines = 1)
    }
}

@Composable
private fun TarjetaSaldo(
    cuenta: String,
    saldo: Long,
    saldoVisible: Boolean,
    apiConectada: Boolean,
    estadoWs: String,
    sincronizando: Boolean,
    alCambiarVisibilidad: () -> Unit,
    alSincronizar: () -> Unit,
    alCopiar: () -> Unit
) {
    val forma = RoundedCornerShape(28.dp)
    Box(
        Modifier.fillMaxWidth().clip(forma)
            .background(Brush.linearGradient(listOf(AzulNoche, Color(0xFF254A83), Color(0xFF246B8B))))
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(Color.White.copy(alpha = .05f), radius = size.width * .52f,
                center = Offset(size.width * 1.04f, -size.height * .12f))
            drawCircle(VerdeMenta.copy(alpha = .1f), radius = size.width * .34f,
                center = Offset(size.width * .96f, size.height * .93f))
        }
        Column(Modifier.fillMaxWidth().padding(23.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SALDO DISPONIBLE", color = Color(0xFFD2DFEF), letterSpacing = 1.3.sp,
                    fontWeight = FontWeight.SemiBold, fontSize = 11.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = alCambiarVisibilidad,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text(if (saldoVisible) "◉  Ocultar" else "◎  Mostrar", fontSize = 12.sp, color = Blanco)
                }
            }
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.animateContentSize()) {
                Crossfade(
                    targetState = if (saldoVisible) "$ ${precio(saldo)}" else "••••••",
                    modifier = Modifier.weight(1f), label = "saldo"
                ) { textoSaldo ->
                    Text(textoSaldo, color = Blanco, fontWeight = FontWeight.Bold,
                        fontSize = 37.sp, maxLines = 1, letterSpacing = (-1).sp)
                }
                Text("USD", color = Color(0xFFB0D0EA), fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 7.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("MI CUENTA", color = Color(0xFFAEC4DA), fontSize = 10.sp, letterSpacing = 1.sp)
                    Text(cuenta, color = Blanco, fontWeight = FontWeight.Medium, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = alCopiar) {
                    Text("▢  Copiar", color = VerdeMenta, fontSize = 12.sp)
                }
            }
            HorizontalDivider(color = Blanco.copy(alpha = .16f))
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val wsOnline = estadoWs == "En línea"
                Row(
                    Modifier.background(Blanco.copy(alpha = .12f), CircleShape)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(Modifier.size(7.dp).background(if (apiConectada) VerdeMenta else Color(0xFFFFA6A6), CircleShape))
                    Text("API", fontSize = 11.sp, color = Blanco)
                }
                Row(
                    Modifier.weight(1f).background(Blanco.copy(alpha = .12f), CircleShape)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(Modifier.size(7.dp).background(if (wsOnline) VerdeMenta else Color(0xFFFFA6A6), CircleShape))
                    Text(if (wsOnline) "Tiempo real activo" else estadoWs, fontSize = 11.sp,
                        color = Blanco, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(
                    Modifier.size(35.dp).background(Blanco.copy(alpha = .14f), CircleShape)
                        .clickable(enabled = !sincronizando, onClick = alSincronizar),
                    contentAlignment = Alignment.Center
                ) {
                    if (sincronizando) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = Blanco)
                    else Text("⟳", fontSize = 23.sp, color = Blanco)
                }
            }
        }
    }
}

private data class OperacionVisual(val nombre: String, val descripcion: String, val signo: String, val tono: Color, val fondo: Color)
private val OperacionesVisuales = listOf(
    OperacionVisual("Depositar", "Recargar saldo", "↙", VerdeTexto, Color(0xFFE2F8F0)),
    OperacionVisual("Retirar", "Sacar fondos", "↗", RojoTexto, Color(0xFFFFEBEF)),
    OperacionVisual("Transferir", "Enviar dinero", "⇄", Morado, Color(0xFFEFEAFF)),
    OperacionVisual("Pagar servicios", "Luz, agua y más", "ϟ", Color(0xFFBE851F), Color(0xFFFFF4DD))
)

@Composable
private fun TarjetaOperacion(operacion: OperacionVisual, habilitada: Boolean, alPulsar: () -> Unit) {
    val forma = RoundedCornerShape(22.dp)
    Column(
        Modifier.fillMaxWidth().border(1.dp, Borde, forma)
            .background(Blanco, forma).clickable(enabled = habilitada, onClick = alPulsar)
            .padding(15.dp), verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        Box(Modifier.size(47.dp).background(operacion.fondo, RoundedCornerShape(15.dp)),
            contentAlignment = Alignment.Center) {
            Text(operacion.signo, fontSize = 28.sp, color = operacion.tono, fontWeight = FontWeight.Bold)
        }
        Column {
            Text(if (operacion.nombre == "Pagar servicios") "Servicios" else operacion.nombre,
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AzulNoche)
            Text(operacion.descripcion, fontSize = 11.sp, color = GrisTexto,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TarjetaResumen(titulo: String, cantidad: Long, positivo: Boolean, modificador: Modifier = Modifier) {
    Column(
        modificador.border(1.dp, Borde, RoundedCornerShape(19.dp))
            .background(Blanco, RoundedCornerShape(19.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(if (positivo) "↙" else "↗", color = if (positivo) VerdeTexto else RojoTexto,
            fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text(titulo, fontSize = 12.sp, color = GrisTexto)
        Text("$ ${precio(cantidad)}", fontSize = 19.sp, fontWeight = FontWeight.Bold,
            color = AzulNoche, maxLines = 1)
    }
}

@Composable
private fun FilaMovimientoPremium(movimiento: Movimiento, alPulsar: () -> Unit) {
    val ingreso = esIngreso(movimiento.tipo)
    val iconBg = if (ingreso) Color(0xFFE2F8F0) else if (movimiento.tipo == "SERVICIO_BASICO") Color(0xFFFFF3DD) else Color(0xFFFFEDF0)
    val tinta = if (ingreso) VerdeTexto else if (movimiento.tipo == "SERVICIO_BASICO") Color(0xFFB88420) else RojoTexto
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(onClick = alPulsar).padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(47.dp).background(iconBg, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center) {
            Text(simboloMovimiento(movimiento.tipo), fontSize = 26.sp, color = tinta,
                fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(nombreMovimiento(movimiento.tipo), color = AzulNoche,
                    fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (movimiento.esDemo) {
                    Text("DEMO", fontWeight = FontWeight.Bold, fontSize = 9.sp, color = Morado,
                        modifier = Modifier.background(Color(0xFFEDEAFE), RoundedCornerShape(5.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp))
                }
            }
            Text(movimiento.detalle.ifBlank { "Movimiento de cuenta" },
                color = GrisTexto, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(fechaMovimiento(movimiento.fechaMillis), color = Color(0xFF9AA5B5), fontSize = 10.sp)
        }
        Text("${if (ingreso) "+" else "−"} $ ${precio(movimiento.montoCentavos)}",
            color = if (ingreso) VerdeTexto else RojoTexto,
            fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun TarjetaVacia(titulo: String, detalle: String) {
    Column(
        Modifier.fillMaxWidth().background(Blanco, RoundedCornerShape(22.dp))
            .border(1.dp, Borde, RoundedCornerShape(22.dp)).padding(30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text("▤", fontSize = 38.sp, color = Morado)
        Text(titulo, color = AzulNoche, fontWeight = FontWeight.Bold)
        Text(detalle, fontSize = 12.sp, textAlign = TextAlign.Center, color = GrisTexto)
    }
}

@Composable
private fun BarraInferior(pagina: Int, alSeleccionar: (Int) -> Unit) {
    Surface(color = Blanco, shadowElevation = 12.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 9.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceAround) {
            listOf(Triple("⌂", "Inicio", 0), Triple("▤", "Movimientos", 1), Triple("◯", "Mi cuenta", 2)).forEach { (icono, titulo, indice) ->
                val resaltado by animateColorAsState(
                    if (pagina == indice) Color(0xFFEAF1FF) else Color.Transparent,
                    label = "pestana"
                )
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(15.dp))
                        .background(resaltado, RoundedCornerShape(15.dp))
                        .clickable { alSeleccionar(indice) }.padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(icono, fontSize = 22.sp, color = if (pagina == indice) AzulMedio else GrisTexto)
                    Text(titulo, fontSize = 10.sp, color = if (pagina == indice) AzulMedio else GrisTexto,
                        fontWeight = if (pagina == indice) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
fun PantallaBilletera(cuentaActual: String, token: String, alSalir: () -> Unit) {
    val contexto = LocalContext.current.applicationContext
    val clipboard = LocalClipboardManager.current
    val dao = remember(contexto, cuentaActual) {
        BilleteraDatabase.obtener(contexto, cuentaActual).movimientoDao()
    }
    val flujo = remember(dao) { dao.observarTodos().flowOn(Dispatchers.IO) }
    val movimientos by flujo.collectAsState(initial = emptyList())
    val api = remember(token) { BilleteraCliente.crearApi(token) }
    val repositorio = remember(dao) { RepositorioMovimientos(dao) }
    val avisos = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var operacion by remember { mutableStateOf<String?>(null) }
    var saldoCentavos by remember { mutableLongStateOf(0L) }
    var conectado by remember { mutableStateOf(false) }
    var estadoWebSocket by remember { mutableStateOf("Desconectado") }
    var procesando by remember { mutableStateOf(false) }
    var sincronizando by remember { mutableStateOf(false) }
    var pagina by remember { mutableIntStateOf(0) }
    var saldoVisible by remember { mutableStateOf(true) }
    var mostrarSalir by remember { mutableStateOf(false) }
    var demoExpandido by remember { mutableStateOf(false) }
    var busqueda by remember { mutableStateOf("") }
    var movimientoSeleccionado by remember { mutableStateOf<Movimiento?>(null) }
    var filtro by remember { mutableStateOf("Todos") }

    // Métricas del historial confirmado, nunca utilizadas para calcular el saldo bancario.
    val reales = remember(movimientos) { movimientos.filterNot { it.esDemo } }
    val entradas = remember(reales) { reales.filter { esIngreso(it.tipo) }.sumOf { it.montoCentavos } }
    val salidas = remember(reales) { reales.filterNot { esIngreso(it.tipo) }.sumOf { it.montoCentavos } }
    val ultimos = remember(movimientos) { movimientos.take(4) }
    val filtrados = remember(movimientos, busqueda, filtro) {
        movimientos.filter { movimiento ->
            val coincideFiltro = when (filtro) {
                "Ingresos" -> esIngreso(movimiento.tipo)
                "Salidas" -> !esIngreso(movimiento.tipo)
                "Servicios" -> movimiento.tipo == "SERVICIO_BASICO"
                "DEMO" -> movimiento.esDemo
                else -> true
            }
            val texto = "${nombreMovimiento(movimiento.tipo)} ${movimiento.detalle}"
            coincideFiltro && texto.contains(busqueda.trim(), ignoreCase = true)
        }
    }

    val porFecha = remember(filtrados) {
        val formatoDia = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        filtrados.groupBy { formatoDia.format(Date(it.fechaMillis)) }
    }

    suspend fun actualizarSaldo() {
        try {
            val respuesta = withContext(Dispatchers.IO) { api.consultarCuenta(cuentaActual) }
            saldoCentavos = respuesta.saldoCentavos
            conectado = true
        } catch (e: Exception) {
            conectado = false
            avisos.showSnackbar(mensajeDeError(e))
        }
    }

    suspend fun sincronizarHistorial() {
        if (sincronizando) return
        sincronizando = true
        try {
            val remotos = withContext(Dispatchers.IO) { api.obtenerMovimientos(cuentaActual) }
            remotos.sortedBy { it.id }.forEach { repositorio.guardar(it) }
            actualizarSaldo()
        } catch (e: Exception) {
            if (e is IOException) conectado = false
            avisos.showSnackbar("No se pudo sincronizar: ${mensajeDeError(e)}")
        } finally {
            sincronizando = false
        }
    }

    LaunchedEffect(cuentaActual) { sincronizarHistorial() }

    DisposableEffect(token, cuentaActual) {
        val socket = BilleteraSocket(
            scope = scope,
            cuenta = cuentaActual,
            token = token,
            onEstado = { estadoWebSocket = it },
            onRecibido = { respuesta ->
                repositorio.guardar(respuesta)
                saldoCentavos = respuesta.saldoCentavos
                try {
                    val actual = withContext(Dispatchers.IO) { api.consultarCuenta(cuentaActual) }
                    saldoCentavos = actual.saldoCentavos
                    conectado = true
                } catch (_: Exception) { /* Se conserva el saldo del evento */ }
                avisos.showSnackbar("Transferencia recibida: +$ ${precio(respuesta.montoCentavos)}")
            },
            alConectar = { sincronizarHistorial() }
        )
        socket.iniciar()
        onDispose { socket.detener() }
    }

    fun notificar(mensaje: String) { scope.launch { avisos.showSnackbar(mensaje) } }

    fun copiarCuenta() {
        clipboard.setText(AnnotatedString(cuentaActual))
        notificar("Código de cuenta copiado")
    }

    fun realizarOperacion(tipo: String, montoTexto: String, destino: String) {
        val centavos = try {
            BigDecimal(montoTexto).movePointRight(2).longValueExact()
        } catch (_: Exception) {
            notificar("Monto demasiado grande o inválido")
            return
        }
        if (centavos <= 0) {
            notificar("El monto debe ser mayor que cero")
            return
        }
        val claveOperacion = UUID.randomUUID().toString()
        scope.launch {
            procesando = true
            try {
                val respuesta = withContext(Dispatchers.IO) {
                    when (tipo) {
                        "Depositar" -> api.depositar(claveOperacion, MontoSolicitud(cuentaActual, centavos))
                        "Retirar" -> api.retirar(claveOperacion, MontoSolicitud(cuentaActual, centavos))
                        "Transferir" -> api.transferir(claveOperacion, TransferenciaSolicitud(cuentaActual, centavos, destino))
                        "Pagar servicios" -> api.pagarServicio(claveOperacion, ServicioSolicitud(cuentaActual, centavos, destino))
                        else -> error("Operación desconocida")
                    }
                }
                saldoCentavos = respuesta.saldoCentavos
                conectado = true
                operacion = null
                val guardadoLocal = try { repositorio.guardar(respuesta); true }
                catch (_: Exception) { false }
                if (guardadoLocal) {
                    notificar("$tipo confirmado y guardado en Room")
                } else {
                    notificar("El servidor confirmó la operación, pero Room falló. No repitas el pago.")
                }
            } catch (e: Exception) {
                if (e is IOException) conectado = false
                notificar(mensajeDeError(e))
            } finally {
                procesando = false
            }
        }
    }

    fun guardarDemo(tipo: String, centavos: Long, detalle: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    dao.insertar(Movimiento(
                        tipo = tipo, montoCentavos = centavos, detalle = detalle,
                        fechaMillis = System.currentTimeMillis(), esDemo = true
                    ))
                }
                notificar("Movimiento DEMO guardado. No cambió el saldo.")
            } catch (e: Exception) { notificar("No se pudo guardar DEMO: ${mensajeDeError(e)}") }
        }
    }

    Scaffold(
        containerColor = Fondo,
        snackbarHost = { SnackbarHost(avisos) },
        bottomBar = { BarraInferior(pagina, alSeleccionar = { pagina = it }) }
    ) { padding ->
        when (pagina) {
            0 -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 22.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Marca()
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(saludoDelDia() + " ✦", color = GrisTexto, fontSize = 11.sp)
                            Text("BilleteraDistribuida", color = AzulNoche, fontSize = 17.sp,
                                fontWeight = FontWeight.ExtraBold)
                        }
                        Box(Modifier.size(42.dp).background(Blanco, CircleShape)
                            .border(1.dp, Borde, CircleShape).clickable { pagina = 2 },
                            contentAlignment = Alignment.Center) {
                            Text("◯", color = AzulMedio, fontSize = 24.sp)
                        }
                    }
                }
                item {
                    TarjetaSaldo(
                        cuentaActual, saldoCentavos, saldoVisible, conectado, estadoWebSocket,
                        sincronizando, { saldoVisible = !saldoVisible },
                        { scope.launch { sincronizarHistorial() } }, { copiarCuenta() }
                    )
                }
                item { EncabezadoSeccion("¿Qué quieres hacer?", "Gestiona tus operaciones") }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OperacionesVisuales.take(2).forEach { op ->
                                Box(Modifier.weight(1f)) {
                                    TarjetaOperacion(op, !procesando) { operacion = op.nombre }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OperacionesVisuales.drop(2).forEach { op ->
                                Box(Modifier.weight(1f)) {
                                    TarjetaOperacion(op, !procesando) { operacion = op.nombre }
                                }
                            }
                        }
                    }
                }
                item {
                    EncabezadoSeccion("Resumen del historial", "Movimientos confirmados guardados en este dispositivo")
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TarjetaResumen("Total de ingresos", entradas, true, Modifier.weight(1f))
                        TarjetaResumen("Total de salidas", salidas, false, Modifier.weight(1f))
                    }
                }
                item { ActividadSemanal(reales) }
                item {
                    EncabezadoSeccion("Actividad reciente", "Últimos movimientos guardados",
                        "Ver todos") { pagina = 1 }
                }
                if (ultimos.isEmpty()) {
                    item { TarjetaVacia("Aún sin movimientos", "Tus operaciones confirmadas aparecerán aquí.") }
                } else {
                    item {
                        Column(Modifier.background(Blanco, RoundedCornerShape(22.dp))
                            .border(1.dp, Borde, RoundedCornerShape(22.dp))
                            .padding(horizontal = 15.dp, vertical = 5.dp)) {
                            ultimos.forEachIndexed { index, movimiento ->
                                FilaMovimientoPremium(movimiento) {
                                    movimientoSeleccionado = movimiento
                                }
                                if (index != ultimos.lastIndex) HorizontalDivider(color = Borde)
                            }
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().background(Color(0xFFE6F8F2), RoundedCornerShape(17.dp))
                        .padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("✦", fontSize = 22.sp, color = VerdeTexto)
                        Spacer(Modifier.width(10.dp))
                        Text("Modo académico · Solo dinero ficticio", color = VerdeTexto,
                            fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            1 -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 25.dp, bottom = 22.dp),
                verticalArrangement = Arrangement.spacedBy(15.dp)
            ) {
                item {
                    Text("Mis movimientos", fontSize = 27.sp, color = AzulNoche, fontWeight = FontWeight.ExtraBold)
                    Text("Tu historial local, disponible sin internet", color = GrisTexto, fontSize = 13.sp)
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TarjetaResumen("Ingresos guardados", entradas, true, Modifier.weight(1f))
                        TarjetaResumen("Salidas guardadas", salidas, false, Modifier.weight(1f))
                    }
                }
                item {
                    OutlinedTextField(
                        value = busqueda, onValueChange = { busqueda = it },
                        label = { Text("Buscar en el historial") },
                        placeholder = { Text("Tipo o detalle del movimiento") },
                        leadingIcon = { Text("⌕", fontSize = 27.sp, color = GrisTexto) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        shape = RoundedCornerShape(18.dp)
                    )
                }
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Todos", "Ingresos", "Salidas", "Servicios", "DEMO").forEach { item ->
                            val elegido = filtro == item
                            Box(
                                Modifier.background(if (elegido) AzulNoche else Blanco, CircleShape)
                                    .border(1.dp, if (elegido) AzulNoche else Borde, CircleShape)
                                    .clickable { filtro = item }
                                    .padding(horizontal = 15.dp, vertical = 10.dp)
                            ) {
                                Text(item, color = if (elegido) Blanco else GrisTexto,
                                    fontWeight = if (elegido) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 12.sp)
                            }
                        }
                    }
                }
                item {
                    EncabezadoSeccion("${filtrados.size} resultados", "Datos leídos de Room", "Sincronizar") {
                        scope.launch { sincronizarHistorial() }
                    }
                }
                if (filtrados.isEmpty()) {
                    item { TarjetaVacia("Sin resultados", "Prueba otro filtro o sincroniza tu historial.") }
                } else {
                    porFecha.forEach { (dia, grupo) ->
                        item(key = "fecha_$dia") {
                            Text(
                                SimpleDateFormat("EEEE, d 'de' MMMM", EcLocale)
                                    .format(Date(grupo.first().fechaMillis))
                                    .replaceFirstChar { it.uppercase() },
                                fontWeight = FontWeight.SemiBold, color = GrisTexto, fontSize = 12.sp,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                        items(grupo, key = { it.id }) { movimiento ->
                            Column(Modifier.fillMaxWidth().background(Blanco, RoundedCornerShape(17.dp))
                                .border(1.dp, Borde, RoundedCornerShape(17.dp))
                                .padding(horizontal = 14.dp, vertical = 2.dp)) {
                                FilaMovimientoPremium(movimiento) {
                                    movimientoSeleccionado = movimiento
                                }
                            }
                        }
                    }
                }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 25.dp, bottom = 25.dp),
                verticalArrangement = Arrangement.spacedBy(17.dp)
            ) {
                item {
                    Text("Mi cuenta", fontSize = 27.sp, color = AzulNoche, fontWeight = FontWeight.ExtraBold)
                    Text("Tu espacio de configuración y pruebas", color = GrisTexto, fontSize = 13.sp)
                }
                item {
                    Column(Modifier.fillMaxWidth().background(Blanco, RoundedCornerShape(22.dp))
                        .border(1.dp, Borde, RoundedCornerShape(22.dp)).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Marca()
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Cuenta autenticada", fontWeight = FontWeight.Bold, color = AzulNoche)
                                Text("Token temporal · sesión en memoria", color = GrisTexto, fontSize = 11.sp)
                            }
                        }
                        HorizontalDivider(color = Borde)
                        Text("IDENTIFICADOR DE CUENTA", fontSize = 10.sp, color = GrisTexto, letterSpacing = 1.sp)
                        Text(cuentaActual, color = AzulNoche, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        OutlinedButton(onClick = { copiarCuenta() }, modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)) { Text("▢  Copiar código de cuenta") }
                    }
                }
                item {
                    Column(Modifier.fillMaxWidth().background(Blanco, RoundedCornerShape(22.dp))
                        .border(1.dp, Borde, RoundedCornerShape(22.dp)).padding(19.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        EncabezadoSeccion("Estado del sistema")
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Conexión a API REST", fontSize = 13.sp, modifier = Modifier.weight(1f))
                            EtiquetaEstado(if (conectado) "Conectada" else "Sin conexión", conectado)
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("WebSocket", fontSize = 13.sp, modifier = Modifier.weight(1f))
                            EtiquetaEstado(estadoWebSocket, estadoWebSocket == "En línea")
                        }
                        Button(onClick = { scope.launch { sincronizarHistorial() } },
                            enabled = !sincronizando, modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(13.dp)) {
                            Text(if (sincronizando) "Sincronizando…" else "⟳  Sincronizar movimientos")
                        }
                    }
                }
                item {
                    Column(Modifier.fillMaxWidth().background(Blanco, RoundedCornerShape(22.dp))
                        .border(1.dp, Borde, RoundedCornerShape(22.dp)).padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth().clickable { demoExpandido = !demoExpandido },
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Laboratorio Room", fontWeight = FontWeight.Bold, color = AzulNoche)
                                Text("Herramientas de prueba · No alteran el saldo", color = GrisTexto, fontSize = 11.sp)
                            }
                            Text(if (demoExpandido) "⌃" else "⌄", color = AzulMedio, fontSize = 24.sp)
                        }
                        AnimatedVisibility(visible = demoExpandido) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                HorizontalDivider(color = Borde)
                                OutlinedButton(onClick = { guardarDemo("DEPOSITO", 1250, "Ingreso DEMO") },
                                    modifier = Modifier.fillMaxWidth()) { Text("+ $12.50 de prueba") }
                                OutlinedButton(onClick = { guardarDemo("SERVICIO_BASICO", 725, "Servicio DEMO") },
                                    modifier = Modifier.fillMaxWidth()) { Text("− $7.25 de prueba") }
                                TextButton(modifier = Modifier.fillMaxWidth(), onClick = {
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) { dao.borrarMovimientosDemo() }
                                            notificar("Movimientos DEMO eliminados")
                                        } catch (e: Exception) { notificar(mensajeDeError(e)) }
                                    }
                                }) { Text("Borrar registros DEMO") }
                            }
                        }
                    }
                }
                item {
                    OutlinedButton(
                        onClick = { mostrarSalir = true }, modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, Color(0xFFFFCFD5)),
                        shape = RoundedCornerShape(16.dp)
                    ) { Text("Cerrar sesión", color = RojoTexto, fontWeight = FontWeight.Bold) }
                }
                item {
                    Text("BilleteraDistribuida · Proyecto académico · No procesa dinero real",
                        fontSize = 11.sp, color = GrisTexto, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    movimientoSeleccionado?.let { movimiento ->
        DetalleMovimiento(movimiento) { movimientoSeleccionado = null }
    }

    if (operacion != null) {
        FormularioOperacion(
            tipo = operacion!!, procesando = procesando,
            alCerrar = { if (!procesando) operacion = null },
            alConfirmar = { tipo, monto, destino -> realizarOperacion(tipo, monto, destino) }
        )
    }
    if (mostrarSalir) {
        AlertDialog(
            onDismissRequest = { mostrarSalir = false },
            title = { Text("¿Cerrar sesión?") },
            text = { Text("Para volver a entrar tendrás que escribir tu correo y contraseña. Tus movimientos Room permanecerán guardados.") },
            confirmButton = {
                TextButton(onClick = { mostrarSalir = false; alSalir() }) { Text("Cerrar sesión", color = RojoTexto) }
            },
            dismissButton = { TextButton(onClick = { mostrarSalir = false }) { Text("Cancelar") } }
        )
    }
}

@Composable
fun FormularioOperacion(tipo: String, procesando: Boolean, alCerrar: () -> Unit,
                        alConfirmar: (String, String, String) -> Unit) {
    var monto by remember(tipo) { mutableStateOf("") }
    var destino by remember(tipo) { mutableStateOf("") }
    var mostrarErrores by remember(tipo) { mutableStateOf(false) }
    val requiereDestino = tipo == "Transferir" || tipo == "Pagar servicios"
    val montoLimpio = monto.trim()
    val montoValido = Regex("""\d+([.,]\d{1,2})?""").matches(montoLimpio) &&
            (montoLimpio.replace(',', '.').toBigDecimalOrNull() ?: BigDecimal.ZERO) > BigDecimal.ZERO
    val destinoValido = !requiereDestino || destino.isNotBlank()
    val info = OperacionesVisuales.firstOrNull { it.nombre == tipo } ?: OperacionesVisuales[0]

    Dialog(onDismissRequest = alCerrar) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                .background(Blanco).padding(22.dp)
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(Modifier.size(55.dp).background(info.fondo, RoundedCornerShape(19.dp)),
                contentAlignment = Alignment.Center) {
                Text(info.signo, fontSize = 29.sp, color = info.tono, fontWeight = FontWeight.Bold)
            }
            Text(tipo, color = AzulNoche, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            Text(if (tipo == "Depositar") "Añade fondos ficticios a tu cuenta."
            else if (tipo == "Retirar") "Retira fondos según tu saldo disponible."
            else if (tipo == "Transferir") "Envía dinero ficticio a otra cuenta registrada."
            else "Paga un contrato de luz, agua o internet.",
                color = GrisTexto, fontSize = 13.sp)
            OutlinedTextField(
                value = monto, onValueChange = { monto = it; mostrarErrores = false },
                label = { Text("Monto en USD") }, placeholder = { Text("25.50") },
                leadingIcon = { Text("$", color = AzulMedio, fontWeight = FontWeight.Bold) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp), isError = mostrarErrores && !montoValido,
                supportingText = {
                    if (mostrarErrores && !montoValido) Text("Ingresa un monto positivo de hasta 2 decimales")
                }
            )
            if (requiereDestino) {
                OutlinedTextField(
                    value = destino, onValueChange = { destino = it; mostrarErrores = false },
                    label = { Text(if (tipo == "Transferir") "Cuenta de destino" else "Código de contrato") },
                    placeholder = { Text(if (tipo == "Transferir") "Código C... del destinatario" else "Ej. LUZ123") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp), isError = mostrarErrores && !destinoValido,
                    supportingText = { if (mostrarErrores && !destinoValido) Text("Campo obligatorio") }
                )
            }
            Row(Modifier.fillMaxWidth().background(Color(0xFFEAF7F4), RoundedCornerShape(14.dp))
                .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("✧", color = VerdeTexto, fontSize = 21.sp)
                Spacer(Modifier.width(8.dp))
                Text("El saldo lo confirma el servidor. Solo dinero ficticio.",
                    color = VerdeTexto, fontSize = 11.sp, modifier = Modifier.weight(1f))
            }
            if (procesando) LinearProgressIndicator(Modifier.fillMaxWidth(), color = VerdeTexto)
            Button(
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(15.dp), enabled = !procesando,
                onClick = {
                    mostrarErrores = true
                    if (montoValido && destinoValido) {
                        alConfirmar(tipo, montoLimpio.replace(',', '.'), destino.trim())
                    }
                }
            ) { Text(if (procesando) "Procesando…" else "Confirmar operación", fontWeight = FontWeight.Bold) }
            TextButton(onClick = alCerrar, enabled = !procesando,
                modifier = Modifier.fillMaxWidth()) { Text("Cancelar", color = GrisTexto) }
        }
    }
}

@Composable
fun PantallaAcceso(alAcceder: (RespuestaAcceso) -> Unit) {
    var registrar by remember { mutableStateOf(false) }
    var correo by remember { mutableStateOf("") }
    var clave by remember { mutableStateOf("") }
    var mostrandoClave by remember { mutableStateOf(false) }
    var esperando by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(Fondo).verticalScroll(rememberScrollState())) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 38.dp, bottomEnd = 38.dp))
                .background(Brush.linearGradient(listOf(AzulNoche, AzulMedio, Color(0xFF287893))))
        ) {
            Canvas(Modifier.matchParentSize()) {
                drawCircle(Blanco.copy(alpha = .055f), radius = size.width * .43f,
                    center = Offset(size.width * 1.02f, size.height * .02f))
                drawCircle(VerdeMenta.copy(alpha = .10f), radius = size.width * .28f,
                    center = Offset(-size.width * .06f, size.height * 1.02f))
            }
            Column(Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 54.dp, bottom = 42.dp),
                verticalArrangement = Arrangement.spacedBy(15.dp)) {
                Marca()
                Text("Tu billetera,\ntu mundo.", fontSize = 34.sp, lineHeight = 39.sp,
                    color = Blanco, fontWeight = FontWeight.ExtraBold)
                Text("Transfiere, organiza y consulta tus movimientos en tiempo real.",
                    color = Color(0xFFD5E5F2), fontSize = 13.sp, lineHeight = 20.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("●  REST", color = VerdeMenta, fontSize = 11.sp)
                    Text("●  WebSockets", color = Color(0xFFD2E4F1), fontSize = 11.sp)
                    Text("●  Room", color = Color(0xFFD2E4F1), fontSize = 11.sp)
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp)) {
            Text(if (registrar) "Crea tu cuenta" else "Bienvenido de nuevo",
                color = AzulNoche, fontWeight = FontWeight.ExtraBold, fontSize = 25.sp)
            Text(if (registrar) "Regístrate para obtener una cuenta individual."
            else "Ingresa para acceder a tu billetera.", fontSize = 13.sp, color = GrisTexto)
            OutlinedTextField(
                value = correo, onValueChange = { correo = it; error = "" },
                label = { Text("Correo electrónico") },
                placeholder = { Text("tu@correo.com") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true, shape = RoundedCornerShape(17.dp),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = clave, onValueChange = { clave = it; error = "" },
                label = { Text("Contraseña") },
                visualTransformation = if (mostrandoClave) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true, shape = RoundedCornerShape(17.dp),
                trailingIcon = {
                    TextButton(onClick = { mostrandoClave = !mostrandoClave }) {
                        Text(if (mostrandoClave) "Ocultar" else "Ver", fontSize = 11.sp)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Text("Usa una contraseña de al menos 12 caracteres.", color = GrisTexto, fontSize = 11.sp)
            if (error.isNotBlank()) {
                Text(error, color = RojoTexto, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().background(Color(0xFFFFECF0), RoundedCornerShape(12.dp))
                        .padding(12.dp))
            }
            Button(
                modifier = Modifier.fillMaxWidth().height(55.dp),
                shape = RoundedCornerShape(17.dp), enabled = !esperando,
                onClick = {
                    if (!Patterns.EMAIL_ADDRESS.matcher(correo.trim()).matches() || clave.length < 12) {
                        error = "Ingresa un correo válido y una contraseña de mínimo 12 caracteres."
                    } else {
                        scope.launch {
                            esperando = true
                            try {
                                val resultado = withContext(Dispatchers.IO) {
                                    val datos = Credenciales(correo.trim(), clave)
                                    if (registrar) BilleteraCliente.authApi.registrar(datos)
                                    else BilleteraCliente.authApi.login(datos)
                                }
                                clave = ""
                                alAcceder(resultado)
                            } catch (e: Exception) {
                                error = mensajeDeError(e)
                            } finally { esperando = false }
                        }
                    }
                }
            ) {
                if (esperando) {
                    CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = Blanco)
                    Spacer(Modifier.width(10.dp))
                }
                Text(if (esperando) "Conectando..." else if (registrar) "Crear cuenta" else "Iniciar sesión",
                    fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            TextButton(
                onClick = { registrar = !registrar; clave = ""; error = "" },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (registrar) "¿Ya tienes cuenta? Inicia sesión" else "¿Primera vez? Crear cuenta") }
            Text("Versión universitaria · Solo dinero ficticio. El servidor puede restringir nuevos registros.",
                fontSize = 11.sp, textAlign = TextAlign.Center, color = GrisTexto,
                modifier = Modifier.fillMaxWidth())
        }
    }
}
