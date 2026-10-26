package com.example.billeteradistribuida

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.PI

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

// Premium V6: efectos visuales sin dependencia adicional. El usuario y Android pueden
// reducir movimiento; ni el saldo ni las operaciones dependen de animaciones.
private val LocalMovimientoExtra = compositionLocalOf { true }

// La configuración "reducir movimiento" del sistema siempre tiene prioridad.
@Composable
private fun permitirMovimiento(): Boolean = ValueAnimator.areAnimatorsEnabled() && LocalMovimientoExtra.current

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var efectosActivos by rememberSaveable { mutableStateOf(true) }
            CompositionLocalProvider(LocalMovimientoExtra provides efectosActivos) {
                MaterialTheme(colorScheme = EsquemaBilletera) {
                    val animar = permitirMovimiento()
                    var sesion by remember { mutableStateOf<RespuestaAcceso?>(null) }
                    var mostrandoSplash by remember { mutableStateOf(true) }
                    LaunchedEffect(Unit) {
                        if (animar) delay(1150)
                        mostrandoSplash = false
                    }
                    Crossfade(
                        targetState = mostrandoSplash to sesion,
                        animationSpec = tween(if (animar) 400 else 0),
                        label = "inicio_y_sesion"
                    ) { (splash, acceso) ->
                        if (splash) {
                            PantallaSplashPremium()
                        } else if (acceso == null) {
                            PantallaAcceso(alAcceder = { sesion = it })
                        } else {
                            key(acceso.cuenta) {
                                PantallaBilletera(
                                    cuentaActual = acceso.cuenta,
                                    token = acceso.access_token,
                                    efectosActivos = efectosActivos,
                                    alCambiarEfectos = { efectosActivos = it },
                                    alSalir = { sesion = null }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// Partículas de ambiente deterministas, dibujadas en Canvas (no usan red ni imágenes).
@Composable
private fun CieloDeChispas(modifier: Modifier = Modifier, fiesta: Boolean = false) {
    val mover = permitirMovimiento()
    val transicion = rememberInfiniteTransition(label = "cielo_v6")
    val avance by transicion.animateFloat(
        initialValue = 0f,
        targetValue = if (mover) 1f else 0f,
        animationSpec = infiniteRepeatable(tween(5200, easing = LinearEasing), RepeatMode.Restart),
        label = "particulas_v6"
    )
    Canvas(modifier.clearAndSetSemantics {}) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        repeat(if (fiesta) 34 else 20) { indice ->
            val x = size.width * ((indice * .6180339f + avance * .13f) % 1f)
            val y = size.height * ((indice * .381966f + 1f - avance * .20f) % 1f)
            val radio = (if (fiesta) 2.7f else 2.1f) * density * (1f + indice % 3 * .32f)
            val tono = when (indice % 4) {
                0 -> VerdeMenta
                1 -> Color(0xFFFFD991)
                2 -> Color(0xFFC5AEFF)
                else -> Color.White
            }
            drawCircle(tono.copy(alpha = if (fiesta) .70f else .24f), radio, Offset(x, y))
            if (indice % 5 == 0) {
                drawLine(tono.copy(alpha = .55f),
                    Offset(x - radio * 2f, y), Offset(x + radio * 2f, y), 1.7f * density)
                drawLine(tono.copy(alpha = .55f),
                    Offset(x, y - radio * 2f), Offset(x, y + radio * 2f), 1.7f * density)
            }
        }
    }
}

// "Chispa": muñequita/monedita simpática, con ojos que parpadean,
// brazos que saludan, pies y movimiento flotante.
@Composable
private fun MascotaChispa(
    modifier: Modifier = Modifier,
    celebrando: Boolean = false,
    violeta: Boolean = false
) {
    val mover = permitirMovimiento()
    val transicion = rememberInfiniteTransition(label = "chispa_personaje")
    val fase by transicion.animateFloat(
        initialValue = 0f,
        targetValue = if (mover) (2f * PI.toFloat()) else 0f,
        animationSpec = infiniteRepeatable(
            tween(if (celebrando) 1750 else 3100, easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "chispa_saludo"
    )
    Canvas(modifier.clearAndSetSemantics {}) {
        val d = size.minDimension
        if (d <= 0f) return@Canvas
        val cx = size.width * .50f
        val salto = sin(fase.toDouble()).toFloat() * (if (celebrando) .040f else .027f) * d
        val cy = size.height * .46f + salto
        val r = d * .255f
        val onda = sin(fase.toDouble() * 1.2).toFloat() * .055f * d
        val grosor = d * .048f

        // Sombra suave debajo del personaje.
        drawOval(Color(0xFF112B4B).copy(alpha = .13f),
            topLeft = Offset(cx - .23f*d, size.height*.86f),
            size = Size(.46f*d, .055f*d))
        // Piernas con zapatitos.
        drawLine(AzulMedio, Offset(cx - .115f*d, cy + r*.72f),
            Offset(cx - .135f*d, cy + r + .17f*d), grosor, cap = StrokeCap.Round)
        drawLine(AzulMedio, Offset(cx + .115f*d, cy + r*.72f),
            Offset(cx + .14f*d, cy + r + .17f*d), grosor, cap = StrokeCap.Round)
        drawOval(Morado, Offset(cx - .22f*d, cy + r + .135f*d), Size(.17f*d,.065f*d))
        drawOval(Morado, Offset(cx + .055f*d, cy + r + .135f*d), Size(.17f*d,.065f*d))

        // Manitas que saludan.
        drawLine(AzulMedio, Offset(cx - r*.90f, cy + .025f*d),
            Offset(cx - .41f*d, cy - .10f*d - onda), grosor, cap=StrokeCap.Round)
        drawLine(AzulMedio, Offset(cx + r*.90f, cy + .025f*d),
            Offset(cx + .41f*d, cy - .12f*d + onda), grosor, cap=StrokeCap.Round)
        drawCircle(Color(0xFFFFDAAD), d*.050f,
            Offset(cx - .41f*d, cy - .10f*d - onda))
        drawCircle(Color(0xFFFFDAAD), d*.050f,
            Offset(cx + .41f*d, cy - .12f*d + onda))

        // Cuerpo: moneda de menta brillante con borde blanco.
        drawCircle(
            brush=Brush.radialGradient(
                colors=if (violeta)
                    listOf(Color(0xFFFFF8FF), Color(0xFFDCD0FF), Color(0xFFAC9CF2), Morado)
                else listOf(Color(0xFFEDFFF4), Color(0xFF9EF1D4), VerdeMenta, VerdeTexto),
                center=Offset(cx - .13f*d, cy - .14f*d), radius=d*.62f
            ), radius=r, center=Offset(cx,cy)
        )
        drawCircle(Color.White.copy(alpha=.82f), radius=r, center=Offset(cx,cy),
            style=Stroke(width=d*.018f))
        drawCircle(Color.White.copy(alpha=.34f), radius=r*.76f, center=Offset(cx,cy),
            style=Stroke(width=d*.011f))

        val parpadeo = if (mover && fase > 5.80f) .17f else 1f
        val ojoAncho = .056f*d
        val ojoAlto = .078f*d*parpadeo
        listOf(cx - .084f*d, cx + .084f*d).forEach { ex ->
            drawOval(AzulNoche,
                Offset(ex - ojoAncho/2f, cy - .060f*d - ojoAlto/2f),
                Size(ojoAncho, ojoAlto))
        }
        drawCircle(Color(0xFFFCA8A9).copy(alpha=.63f),d*.040f,
            Offset(cx - .164f*d,cy+.041f*d))
        drawCircle(Color(0xFFFCA8A9).copy(alpha=.63f),d*.040f,
            Offset(cx + .164f*d,cy+.041f*d))
        val sonrisa = Path().apply {
            moveTo(cx - .070f*d,cy+.072f*d)
            quadraticBezierTo(cx,cy+.160f*d,cx+.070f*d,cy+.072f*d)
        }
        drawPath(sonrisa, color=AzulNoche,
            style=Stroke(width=d*.018f,cap=StrokeCap.Round))
        // Reflejo con forma de estrellita encima de la moneda.
        drawCircle(Color.White.copy(alpha=.85f),d*.018f,
            Offset(cx-.125f*d,cy-.140f*d))
        if (celebrando) {
            val starY = cy - .30f*d + onda
            drawLine(Color(0xFFFFD17B),Offset(cx + .34f*d,starY-.05f*d),
                Offset(cx+.34f*d,starY+.05f*d),d*.024f,cap=StrokeCap.Round)
            drawLine(Color(0xFFFFD17B),Offset(cx + .29f*d,starY),
                Offset(cx+.39f*d,starY),d*.024f,cap=StrokeCap.Round)
        }
    }
}

@Composable
private fun TarjetaChispaBienvenida(conectado: Boolean, saldoCentavos: Long) {
    val animar = permitirMovimiento()
    var saldoAnterior by remember { mutableLongStateOf(saldoCentavos) }
    var celebrando by remember { mutableStateOf(false) }
    LaunchedEffect(saldoCentavos) {
        if (saldoCentavos > saldoAnterior) {
            celebrando = true
            if (animar) delay(1800)
            celebrando = false
        }
        saldoAnterior = saldoCentavos
    }
    val transicion = rememberInfiniteTransition(label="bienvenida_color")
    val brillo by transicion.animateFloat(
        initialValue=.85f, targetValue=if(animar)1f else .85f,
        animationSpec=infiniteRepeatable(tween(2100),RepeatMode.Reverse),
        label="chispa_resplandor"
    )
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
        .background(Brush.horizontalGradient(listOf(Color(0xFFEAF8F4),Color(0xFFEAEFFC))))
        .border(1.dp,Color(0xFFDDEDEB),RoundedCornerShape(24.dp))) {
        if (celebrando) CieloDeChispas(Modifier.matchParentSize(),fiesta=true)
        Row(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=9.dp),
            verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(9.dp)) {
            MascotaChispa(Modifier.size(77.dp).graphicsLayer { alpha = brillo },
                celebrando=celebrando)
            Column(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(if(celebrando)"¡Nuevo ingreso recibido! ✨" else
                    if(conectado)"¡Todo listo para brillar!" else "¡Qué bueno verte!",
                    color=AzulNoche,fontWeight=FontWeight.ExtraBold,fontSize=14.sp)
                Text(if(conectado)"Soy Chispa ✨ Tu billetera está conectada."
                else "Soy Chispa ✨ Estoy preparando tu billetera.",
                    color=AzulMedio,fontSize=11.sp,lineHeight=16.sp)
                Text("Dinero ficticio · Aprendamos juntos",color=VerdeTexto,
                    fontSize=10.sp,fontWeight=FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun PantallaSplashPremium() {
    val animar = permitirMovimiento()
    val escala = remember { Animatable(if (animar) .72f else 1f) }
    val entrada = remember { Animatable(if (animar) 0f else 1f) }
    LaunchedEffect(Unit) {
        escala.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        entrada.animateTo(1f, tween(if (animar) 460 else 0))
    }
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(AzulNoche, AzulMedio, Color(0xFF116F7D)))
        ),
        contentAlignment = Alignment.Center
    ) {
        CieloDeChispas(Modifier.matchParentSize())
        Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
            drawCircle(Color.White.copy(alpha = .055f), radius = size.width * .64f,
                center = Offset(size.width * 1.05f, size.height * .1f))
            drawCircle(VerdeMenta.copy(alpha = .13f), radius = size.width * .50f,
                center = Offset(-size.width * .1f, size.height * .85f))
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp)
                .graphicsLayer { alpha = entrada.value },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                Modifier.size(165.dp).graphicsLayer {
                    scaleX = escala.value
                    scaleY = escala.value
                }, contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(145.dp).background(Blanco.copy(alpha=.08f),CircleShape))
                MascotaChispa(Modifier.size(165.dp))
                MascotaChispa(Modifier.size(72.dp).align(Alignment.BottomEnd),violeta=true)
            }
            Spacer(Modifier.height(5.dp))
            Text("BilleteraDistribuida", fontSize = 27.sp, fontWeight = FontWeight.ExtraBold,
                color = Blanco, textAlign = TextAlign.Center)
            Text("TU BILLETERA, TU MUNDO", fontSize = 11.sp, letterSpacing = 2.sp,
                color = Color(0xFFD6F5F1), textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(Modifier.size(24.dp), color = VerdeMenta, strokeWidth = 2.dp)
            Text("Preparando tu experiencia", color = Color(0xFFB8D5E4), fontSize = 12.sp)
            Text("Proyecto académico · Dinero ficticio", color = Color(0xFFA8C0D2), fontSize = 10.sp)
        }
    }
}

@Composable
private fun EntradaSuave(orden: Int = 0, contenido: @Composable () -> Unit) {
    val animar = permitirMovimiento()
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val duracion = if (animar) 420 else 0
    val retardo = if (animar) (orden * 65).coerceAtMost(325) else 0
    val alpha by animateFloatAsState(
        if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = duracion, delayMillis = retardo),
        label = "entrada_alpha"
    )
    val desplazamiento by animateFloatAsState(
        if (visible) 0f else 22f,
        animationSpec = tween(durationMillis = duracion, delayMillis = retardo),
        label = "entrada_vertical"
    )
    Box(Modifier.fillMaxWidth().graphicsLayer {
        this.alpha = alpha
        translationY = desplazamiento * density
    }) { contenido() }
}

@Composable
private fun PuntoVivo(activo: Boolean, color: Color, dimension: androidx.compose.ui.unit.Dp = 7.dp) {
    val animar = permitirMovimiento()
    val transicion = rememberInfiniteTransition(label = "pulso_conexion")
    val escala by transicion.animateFloat(
        initialValue = 1f,
        targetValue = if (activo && animar) 1.34f else 1f,
        animationSpec = infiniteRepeatable(tween(1050), RepeatMode.Reverse),
        label = "tamano_punto"
    )
    val opacidad by transicion.animateFloat(
        initialValue = 1f,
        targetValue = if (activo && animar) .68f else 1f,
        animationSpec = infiniteRepeatable(tween(1050), RepeatMode.Reverse),
        label = "brillo_punto"
    )
    Box(Modifier.size(dimension).graphicsLayer {
        scaleX = escala; scaleY = escala; alpha = opacidad
    }.background(color, CircleShape))
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

// Comprobante construido únicamente a partir de una operación confirmada por la API.
// No almacena tokens, contraseñas ni datos inventados.
private data class ComprobanteOperacion(
    val idServidor: Long,
    val tipo: String,
    val montoCentavos: Long,
    val fechaMillis: Long,
    val cuenta: String,
    val referencia: String?,
    val detalle: String,
    val saldoPosteriorCentavos: Long,
    val guardadoEnRoom: Boolean
)

private fun fechaComprobante(millis: Long): String =
    SimpleDateFormat("dd/MM/yyyy · HH:mm:ss", EcLocale).format(Date(millis))

private fun cuentaParaCompartir(cuenta: String): String =
    if (cuenta.length <= 6) cuenta else "${cuenta.take(2)}••••${cuenta.takeLast(4)}"

private fun textoComprobante(comprobante: ComprobanteOperacion): String = buildString {
    appendLine("BILLETERADISTRIBUIDA | COMPROBANTE")
    appendLine("Proyecto académico | Solo dinero ficticio")
    appendLine("Estado: Confirmada por el servidor")
    appendLine("Movimiento: #${comprobante.idServidor}")
    appendLine("Operación: ${nombreMovimiento(comprobante.tipo)}")
    appendLine("Monto: USD ${precio(comprobante.montoCentavos)}")
    appendLine("Fecha: ${fechaComprobante(comprobante.fechaMillis)}")
    appendLine("Cuenta: ${cuentaParaCompartir(comprobante.cuenta)}")
    comprobante.referencia?.takeIf { it.isNotBlank() }?.let {
        val referenciaPublica = if (comprobante.tipo == "TRANSFERENCIA_ENVIADA")
            cuentaParaCompartir(it) else it
        appendLine("${if (comprobante.tipo == "TRANSFERENCIA_ENVIADA") "Destino" else "Contrato"}: $referenciaPublica")
    }
    appendLine("Detalle: ${comprobante.detalle.replace(Regex("C[A-Z0-9]{10,}")) { cuentaParaCompartir(it.value) }}")
    appendLine("No constituye comprobante bancario ni representa dinero real.")
}

private fun textoMovimientoConfirmado(movimiento: Movimiento): String = buildString {
    appendLine("BILLETERADISTRIBUIDA | MOVIMIENTO")
    appendLine("Proyecto académico | Solo dinero ficticio")
    appendLine("Estado: Confirmado por el servidor")
    appendLine("Movimiento: #${movimiento.serverId}")
    appendLine("Operación: ${nombreMovimiento(movimiento.tipo)}")
    appendLine("Monto: USD ${precio(movimiento.montoCentavos)}")
    appendLine("Fecha: ${fechaComprobante(movimiento.fechaMillis)}")
    // Evitar compartir el identificador completo de otra persona al reenviar historial.
    appendLine("Descripción: ${movimiento.detalle.replace(Regex("C[A-Z0-9]{10,}")) { cuentaParaCompartir(it.value) }}")
    appendLine("No constituye comprobante bancario ni representa dinero real.")
}

// Utiliza el menú nativo de Android: comparte texto, sin permisos de almacenamiento,
// sin FileProvider, y sin instalar dependencias adicionales.
private fun compartirTexto(contexto: Context, texto: String) {
    val enviar = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Comprobante BilleteraDistribuida")
        putExtra(Intent.EXTRA_TEXT, texto)
    }
    contexto.startActivity(Intent.createChooser(enviar, "Compartir comprobante"))
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
    val contextoCompartir = LocalContext.current
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!movimiento.esDemo && movimiento.serverId != null) {
                    TextButton(onClick = {
                        compartirTexto(contextoCompartir, textoMovimientoConfirmado(movimiento))
                    }) { Text("Compartir", fontWeight = FontWeight.SemiBold) }
                }
                TextButton(onClick = alCerrar) { Text("Cerrar", fontWeight = FontWeight.Bold) }
            }
        }
    )
}

@Composable
private fun DatoComprobante(titulo: String, valor: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(titulo.uppercase(), fontSize = 10.sp, color = GrisTexto, letterSpacing = .5.sp)
        Text(valor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AzulNoche)
    }
}

@Composable
private fun ComprobanteExito(
    comprobante: ComprobanteOperacion,
    alCompartir: () -> Unit,
    alCopiar: () -> Unit,
    alCerrar: () -> Unit
) {
    val animar = permitirMovimiento()
    val escalaCheck = remember(comprobante.idServidor) { Animatable(if (animar) .55f else 1f) }
    var detallesVisibles by remember(comprobante.idServidor) { mutableStateOf(false) }
    LaunchedEffect(comprobante.idServidor) {
        escalaCheck.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        detallesVisibles = true
    }
    val resplandor = rememberInfiniteTransition(label = "exito_pulso")
    val halo by resplandor.animateFloat(
        initialValue = 1f, targetValue = if (animar) 1.16f else 1f,
        animationSpec = infiniteRepeatable(tween(1250), RepeatMode.Reverse),
        label = "halo_exito"
    )
    Dialog(
        onDismissRequest = alCerrar,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                .heightIn(max = 690.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Blanco)
                .verticalScroll(rememberScrollState())
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Box(Modifier.fillMaxWidth().height(148.dp),contentAlignment=Alignment.Center) {
                CieloDeChispas(Modifier.matchParentSize(), fiesta=true)
                Row(verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                    MascotaChispa(Modifier.size(124.dp),celebrando=true)
                    Box(Modifier.size(85.dp), contentAlignment=Alignment.Center) {
                        Box(Modifier.size(78.dp).graphicsLayer {
                            scaleX=halo; scaleY=halo
                        }.background(Color(0xFFE1F8EF).copy(alpha=.75f),CircleShape))
                        Box(Modifier.size(69.dp).graphicsLayer {
                            scaleX=escalaCheck.value; scaleY=escalaCheck.value
                        }.shadow(6.dp,CircleShape)
                            .background(Brush.linearGradient(listOf(VerdeMenta,VerdeTexto)),CircleShape),
                            contentAlignment=Alignment.Center
                        ) { Text("✓",fontSize=37.sp,fontWeight=FontWeight.Bold,color=Blanco) }
                    }
                }
            }
            Text("¡Operación exitosa!", color = AzulNoche,
                fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
            Text("Confirmada por el servidor", fontSize = 12.sp,
                color = VerdeTexto, fontWeight = FontWeight.SemiBold)
            Text("$ ${precio(comprobante.montoCentavos)}", fontSize = 34.sp,
                fontWeight = FontWeight.ExtraBold, color = AzulNoche)
            Text(nombreMovimiento(comprobante.tipo), fontSize = 13.sp, color = GrisTexto)

            AnimatedVisibility(
                visible = detallesVisibles,
                enter = fadeIn(tween(if (animar) 420 else 0)) +
                        slideInVertically(initialOffsetY = { it / 7 },
                            animationSpec = tween(if (animar) 420 else 0))
            ) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(Fondo, RoundedCornerShape(18.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    DatoComprobante("Número de movimiento", "#${comprobante.idServidor}")
                    HorizontalDivider(color = Borde)
                    DatoComprobante("Fecha y hora", fechaComprobante(comprobante.fechaMillis))
                    DatoComprobante("Cuenta", comprobante.cuenta)
                    comprobante.referencia?.takeIf { it.isNotBlank() }?.let { referencia ->
                        DatoComprobante(
                            if (comprobante.tipo == "TRANSFERENCIA_ENVIADA")
                                "Cuenta de destino" else "Código de contrato",
                            referencia
                        )
                    }
                    DatoComprobante("Descripción", comprobante.detalle)
                    DatoComprobante("Saldo al confirmar", "$ ${precio(comprobante.saldoPosteriorCentavos)}")
                }
            }

            if (!comprobante.guardadoEnRoom) {
                Text(
                    "La operación está confirmada en el servidor, pero no pudo " +
                            "guardarse en Room. Sincroniza el historial; no repitas la operación.",
                    color = RojoTexto, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth()
                        .background(Color(0xFFFFEDF0), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                )
            }

            Text("Solo dinero ficticio · No es un comprobante bancario real",
                color = GrisTexto, fontSize = 11.sp, textAlign = TextAlign.Center)

            Button(
                onClick = alCompartir,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Compartir comprobante", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = alCopiar,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Copiar datos", fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = alCerrar, modifier = Modifier.fillMaxWidth()) {
                Text("Volver a mi billetera", color = GrisTexto)
            }
        }
    }
}

@Composable
private fun Marca(modifier: Modifier = Modifier) {
    val animar = permitirMovimiento()
    val transicion = rememberInfiniteTransition(label = "logo_luz")
    val brillo by transicion.animateFloat(
        initialValue = .94f, targetValue = if (animar) 1f else .94f,
        animationSpec = infiniteRepeatable(tween(2300), RepeatMode.Reverse),
        label = "logo_escala"
    )
    Box(
        modifier = modifier
            .size(44.dp)
            .graphicsLayer { scaleX = brillo; scaleY = brillo }
            .shadow(7.dp, RoundedCornerShape(15.dp))
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
        PuntoVivo(activo, if (activo) VerdeTexto else RojoTexto)
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
    val animar = permitirMovimiento()
    var saldoAnterior by remember { mutableLongStateOf(saldo) }
    val progreso = remember { Animatable(1f) }
    val destello = remember { Animatable(0f) }
    LaunchedEffect(saldo) {
        if (animar && saldo != saldoAnterior) {
            progreso.snapTo(0f)
            destello.snapTo(1f)
            progreso.animateTo(1f, tween(650))
            destello.animateTo(0f, tween(530))
        } else {
            progreso.snapTo(1f)
            destello.snapTo(0f)
        }
        saldoAnterior = saldo
    }
    val balanceVisual = if (progreso.value >= 1f) saldo else {
        (saldoAnterior.toDouble() +
                (saldo.toDouble() - saldoAnterior.toDouble()) * progreso.value)
            .coerceIn(minOf(saldoAnterior, saldo).toDouble(),
                maxOf(saldoAnterior, saldo).toDouble()).roundToLong()
    }
    Box(
        Modifier.fillMaxWidth().shadow(12.dp, forma)
            .clip(forma)
            .background(Brush.linearGradient(listOf(AzulNoche, Color(0xFF254A83), Color(0xFF246B8B))))
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(Color.White.copy(alpha = .05f), radius = size.width * .52f,
                center = Offset(size.width * 1.04f, -size.height * .12f))
            drawCircle(VerdeMenta.copy(alpha = .1f), radius = size.width * .34f,
                center = Offset(size.width * .96f, size.height * .93f))
            // Filigrana de estrellas para una tarjeta luminosa, sin ocultar los textos.
            for (indice in 0 until 8) {
                val x = size.width * (.09f + indice*.115f)
                val y = size.height * (.12f + (indice%3)*.10f)
                drawCircle(Color.White.copy(alpha=.09f), 1.5f*density,Offset(x,y))
            }
            if (destello.value > 0f) {
                drawCircle(VerdeMenta.copy(alpha = .16f * destello.value),
                    radius = size.width * (.30f + .12f * (1f - destello.value)),
                    center = Offset(size.width * .84f, size.height * .4f))
            }
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
                    targetState = saldoVisible,
                    modifier = Modifier.weight(1f),
                    animationSpec = tween(if (animar) 250 else 0),
                    label = "saldo_visible"
                ) { visible ->
                    Text(if (visible) "$ ${precio(balanceVisual)}" else "••••••",
                        color = Blanco, fontWeight = FontWeight.Bold,
                        fontSize = 37.sp, maxLines = 1, letterSpacing = (-1).sp,
                        overflow = TextOverflow.Ellipsis)
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
            AnimatedVisibility(visible = sincronizando,
                enter = fadeIn(tween(if (animar) 230 else 0)),
                exit = fadeOut(tween(if (animar) 230 else 0))) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp),
                        color = VerdeMenta, trackColor = Blanco.copy(alpha = .15f))
                    Text("Sincronizando tu billetera…", fontSize = 10.sp,
                        color = Color(0xFFD2E5F2))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val wsOnline = estadoWs == "En línea"
                Row(
                    Modifier.background(Blanco.copy(alpha = .12f), CircleShape)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PuntoVivo(apiConectada, if (apiConectada) VerdeMenta else Color(0xFFFFA6A6))
                    Text("API", fontSize = 11.sp, color = Blanco)
                }
                Row(
                    Modifier.weight(1f).background(Blanco.copy(alpha = .12f), CircleShape)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PuntoVivo(wsOnline, if (wsOnline) VerdeMenta else Color(0xFFFFA6A6))
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
    val animar = permitirMovimiento()
    val interaccion = remember { MutableInteractionSource() }
    val presionado by interaccion.collectIsPressedAsState()
    val escala by animateFloatAsState(
        if (presionado && habilitada && animar) .955f else 1f,
        animationSpec = tween(if (animar) 140 else 0), label = "toque_${operacion.nombre}"
    )
    val transicion = rememberInfiniteTransition(label = "accion_${operacion.nombre}")
    val levitar by transicion.animateFloat(
        initialValue = 0f, targetValue = if (animar) 3.2f else 0f,
        animationSpec = infiniteRepeatable(tween(1300+operacion.nombre.length*60),RepeatMode.Reverse),
        label="flotar_icono"
    )
    Column(
        Modifier.fillMaxWidth().graphicsLayer { scaleX = escala; scaleY = escala }
            .shadow(if (presionado) 2.dp else 5.dp, forma)
            .border(1.dp, Borde, forma)
            .background(Blanco, forma)
            .clickable(interactionSource = interaccion, indication = LocalIndication.current,
                enabled = habilitada, onClick = alPulsar)
            .padding(15.dp), verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        Box(Modifier.size(47.dp).graphicsLayer { translationY=-levitar*density }
            .background(operacion.fondo,RoundedCornerShape(15.dp)),
            contentAlignment=Alignment.Center) {
            Text(operacion.signo,fontSize=28.sp,color=operacion.tono,fontWeight=FontWeight.Bold)
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
    val animar = permitirMovimiento()
    var aparecio by remember(movimiento.id) { mutableStateOf(false) }
    LaunchedEffect(movimiento.id) { aparecio = true }
    val alfa by animateFloatAsState(if (aparecio) 1f else 0f,
        animationSpec = tween(if (animar) 330 else 0), label = "movimiento_aparece")
    val entrada by animateFloatAsState(if (aparecio) 0f else 15f,
        animationSpec = tween(if (animar) 330 else 0), label = "movimiento_desliza")
    Row(
        modifier = Modifier.fillMaxWidth().graphicsLayer {
            alpha = alfa; translationX = entrada * density
        }.clip(RoundedCornerShape(12.dp))
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
        MascotaChispa(Modifier.size(92.dp))
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
                val animar = permitirMovimiento()
                val resaltado by animateColorAsState(
                    if (pagina == indice) Color(0xFFEAF1FF) else Color.Transparent,
                    animationSpec = tween(if (animar) 260 else 0), label = "pestana"
                )
                val escala by animateFloatAsState(
                    if (pagina == indice) 1.06f else .96f,
                    animationSpec = tween(if (animar) 240 else 0), label = "pestana_escala"
                )
                val indicador by animateFloatAsState(
                    if (pagina == indice) 1f else 0f,
                    animationSpec = tween(if (animar) 260 else 0), label = "pestana_indicador"
                )
                Column(
                    Modifier.weight(1f).graphicsLayer { scaleX = escala; scaleY = escala }
                        .clip(RoundedCornerShape(15.dp))
                        .background(resaltado, RoundedCornerShape(15.dp))
                        .clickable { alSeleccionar(indice) }.padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(icono, fontSize = 22.sp, color = if (pagina == indice) AzulMedio else GrisTexto)
                    Text(titulo, fontSize = 10.sp, color = if (pagina == indice) AzulMedio else GrisTexto,
                        fontWeight = if (pagina == indice) FontWeight.Bold else FontWeight.Medium)
                    Box(Modifier.width(24.dp).height(3.dp)
                        .graphicsLayer { scaleX = indicador }
                        .background(VerdeMenta, CircleShape))
                }
            }
        }
    }
}

@Composable
fun PantallaBilletera(
    cuentaActual: String,
    token: String,
    efectosActivos: Boolean,
    alCambiarEfectos: (Boolean) -> Unit,
    alSalir: () -> Unit
) {
    val contextoCompartir = LocalContext.current
    val contexto = contextoCompartir.applicationContext
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
    var errorOperacion by remember { mutableStateOf<String?>(null) }
    var comprobanteExito by remember { mutableStateOf<ComprobanteOperacion?>(null) }
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
        // Protección contra dobles pulsaciones además de la idempotencia del servidor.
        if (procesando) return
        val centavos = try {
            BigDecimal(montoTexto).movePointRight(2).longValueExact()
        } catch (_: Exception) {
            errorOperacion = "Monto demasiado grande o inválido."
            return
        }
        if (centavos <= 0L || centavos > 100_000_000L) {
            errorOperacion = "El monto debe estar entre $0.01 y $1,000,000.00."
            return
        }
        if (tipo != "Depositar" && conectado && centavos > saldoCentavos) {
            errorOperacion = "Saldo insuficiente. Disponible: $ ${precio(saldoCentavos)}"
            return
        }
        errorOperacion = null
        procesando = true
        val claveOperacion = UUID.randomUUID().toString()
        scope.launch {
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
                val guardadoLocal = try { repositorio.guardar(respuesta); true }
                catch (_: Exception) { false }
                // Nunca mostrar un comprobante antes de que REST confirme la operación.
                comprobanteExito = ComprobanteOperacion(
                    idServidor = respuesta.id,
                    tipo = respuesta.tipo,
                    montoCentavos = respuesta.montoCentavos,
                    fechaMillis = respuesta.fechaMillis,
                    cuenta = respuesta.cuenta,
                    referencia = when (tipo) {
                        "Transferir", "Pagar servicios" -> destino
                        else -> null
                    },
                    detalle = respuesta.detalle,
                    saldoPosteriorCentavos = respuesta.saldoCentavos,
                    guardadoEnRoom = guardadoLocal
                )
                operacion = null
                errorOperacion = null
            } catch (e: Exception) {
                if (e is IOException) conectado = false
                // Error visible en el propio Dialog, no debajo de él.
                errorOperacion = mensajeDeError(e)
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

    val animarPantallas = permitirMovimiento()
    Scaffold(
        containerColor = Fondo,
        snackbarHost = { SnackbarHost(avisos) },
        bottomBar = { BarraInferior(pagina, alSeleccionar = { pagina = it }) }
    ) { padding ->
        Crossfade(targetState = pagina,
            animationSpec = tween(if (animarPantallas) 285 else 0),
            label = "navegacion_premium") { paginaMostrada ->
            when (paginaMostrada) {
                0 -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    item {
                        EntradaSuave(0) {
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
                    }
                    item {
                        EntradaSuave(1) { TarjetaChispaBienvenida(conectado,saldoCentavos) }
                    }
                    item {
                        EntradaSuave(2) {
                            TarjetaSaldo(
                                cuentaActual, saldoCentavos, saldoVisible, conectado, estadoWebSocket,
                                sincronizando, { saldoVisible = !saldoVisible },
                                { scope.launch { sincronizarHistorial() } }, { copiarCuenta() }
                            )
                        }
                    }
                    item { EntradaSuave(2) { EncabezadoSeccion("¿Qué quieres hacer?", "Gestiona tus operaciones") } }
                    item {
                        EntradaSuave(3) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OperacionesVisuales.take(2).forEach { op ->
                                        Box(Modifier.weight(1f)) {
                                            TarjetaOperacion(op, !procesando) {
                                                errorOperacion = null
                                                operacion = op.nombre
                                            }
                                        }
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OperacionesVisuales.drop(2).forEach { op ->
                                        Box(Modifier.weight(1f)) {
                                            TarjetaOperacion(op, !procesando) {
                                                errorOperacion = null
                                                operacion = op.nombre
                                            }
                                        }
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
                    item { EntradaSuave(4) { ActividadSemanal(reales) } }
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
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                                .background(Blanco)
                                .border(1.dp,Borde,RoundedCornerShape(20.dp))
                                .padding(horizontal=17.dp,vertical=10.dp),
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            MascotaChispa(Modifier.size(60.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Animaciones Premium",fontWeight=FontWeight.Bold,
                                    color=AzulNoche,fontSize=13.sp)
                                Text("Controla el movimiento y las partículas",
                                    fontSize=10.sp,color=GrisTexto)
                            }
                            Switch(checked=efectosActivos,onCheckedChange=alCambiarEfectos)
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
    }

    movimientoSeleccionado?.let { movimiento ->
        DetalleMovimiento(movimiento) { movimientoSeleccionado = null }
    }

    if (operacion != null) {
        FormularioOperacion(
            tipo = operacion!!,
            procesando = procesando,
            saldoCentavos = saldoCentavos,
            saldoConocido = conectado,
            cuentaActual = cuentaActual,
            errorServidor = errorOperacion,
            alLimpiarError = { errorOperacion = null },
            alCerrar = {
                if (!procesando) {
                    operacion = null
                    errorOperacion = null
                }
            },
            alConfirmar = { tipo, monto, destino -> realizarOperacion(tipo, monto, destino) }
        )
    }
    comprobanteExito?.let { comprobante ->
        ComprobanteExito(
            comprobante = comprobante,
            alCompartir = {
                runCatching {
                    compartirTexto(contextoCompartir, textoComprobante(comprobante))
                }.onFailure {
                    notificar("No se pudo abrir el menú para compartir.")
                }
            },
            alCopiar = {
                clipboard.setText(AnnotatedString(textoComprobante(comprobante)))
                notificar("Comprobante copiado")
            },
            alCerrar = { comprobanteExito = null }
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
fun FormularioOperacion(
    tipo: String,
    procesando: Boolean,
    saldoCentavos: Long,
    saldoConocido: Boolean,
    cuentaActual: String,
    errorServidor: String?,
    alLimpiarError: () -> Unit,
    alCerrar: () -> Unit,
    alConfirmar: (String, String, String) -> Unit
) {
    var monto by remember(tipo) { mutableStateOf("") }
    var destino by remember(tipo) { mutableStateOf("") }
    var revisando by remember(tipo) { mutableStateOf(false) }
    val foco = LocalFocusManager.current
    val teclado = LocalSoftwareKeyboardController.current
    val pideDestino = tipo == "Transferir" || tipo == "Pagar servicios"
    val usaSaldo = tipo != "Depositar"
    val normalizado = monto.trim().replace(',', '.')
    val centavos = remember(normalizado) {
        if (!Regex("""\d+(\.\d{1,2})?""").matches(normalizado)) null
        else runCatching { BigDecimal(normalizado).movePointRight(2).longValueExact() }.getOrNull()
    }
    // FastAPI limita cada operación a 100_000_000 centavos.
    val errorMonto: String? = when {
        monto.isBlank() -> null
        centavos == null -> "Introduce un monto válido (máximo dos decimales)."
        centavos <= 0L -> "El monto debe ser mayor que cero."
        centavos > 100_000_000L -> "Máximo permitido: $1,000,000.00."
        usaSaldo && saldoConocido && centavos > saldoCentavos ->
            "Saldo insuficiente. Disponible: $ ${precio(saldoCentavos)}"
        else -> null
    }
    val errorDestino: String? = when {
        !pideDestino || destino.isBlank() -> null
        tipo == "Transferir" && destino.trim().equals(cuentaActual, ignoreCase = true) ->
            "No puedes transferir a tu propia cuenta."
        else -> null
    }
    val camposCorrectos = monto.isNotBlank() && centavos != null && errorMonto == null &&
            (!pideDestino || (destino.isNotBlank() && errorDestino == null))
    val errorSaldoServidor = errorServidor?.contains("saldo", ignoreCase = true) == true
    val visual = OperacionesVisuales.firstOrNull { it.nombre == tipo } ?: OperacionesVisuales[0]

    // Un rechazo del servidor devuelve la interfaz al formulario con el error visible.
    LaunchedEffect(errorServidor) {
        if (!errorServidor.isNullOrBlank()) revisando = false
    }

    Dialog(
        onDismissRequest = alCerrar,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier.fillMaxWidth()
                .imePadding()
                .padding(horizontal = 18.dp)
                .heightIn(max = 600.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Blanco)
                .animateContentSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(),
                verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.SpaceBetween) {
                Box(Modifier.size(50.dp).background(visual.fondo,RoundedCornerShape(17.dp)),
                    contentAlignment=Alignment.Center) {
                    Text(visual.signo,fontSize=27.sp,color=visual.tono,fontWeight=FontWeight.Bold)
                }
                // Chispa acompaña el formulario; no altera validaciones ni montos.
                MascotaChispa(Modifier.size(69.dp))
            }

            if (revisando) {
                Text("Revisa tu transferencia", fontSize = 22.sp,
                    fontWeight = FontWeight.Bold, color = AzulNoche)
                Text("Confirma el destinatario y el monto antes de enviar.",
                    fontSize = 12.sp, color = GrisTexto)
                Column(
                    Modifier.fillMaxWidth()
                        .background(Fondo, RoundedCornerShape(18.dp)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Text("MONTO", fontSize = 11.sp, color = GrisTexto)
                    Text("$ ${precio(centavos ?: 0L)}", color = AzulNoche,
                        fontWeight = FontWeight.ExtraBold, fontSize = 27.sp)
                    HorizontalDivider(color = Borde)
                    Text("Cuenta de origen", color = GrisTexto, fontSize = 11.sp)
                    Text(cuentaActual, color = AzulNoche, fontWeight = FontWeight.SemiBold)
                    Text("Cuenta de destino", color = GrisTexto, fontSize = 11.sp)
                    Text(destino.trim(), color = AzulNoche, fontWeight = FontWeight.SemiBold)
                }
                if (procesando) LinearProgressIndicator(Modifier.fillMaxWidth(), color = VerdeTexto)
                Button(
                    onClick = {
                        if (camposCorrectos && !procesando)
                            alConfirmar(tipo, normalizado, destino.trim())
                    },
                    enabled = camposCorrectos && !procesando,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(15.dp)
                ) {
                    if (procesando) {
                        CircularProgressIndicator(Modifier.size(17.dp),
                            strokeWidth = 2.dp, color = Blanco)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (procesando) "Enviando…" else "Enviar transferencia",
                        fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { revisando = false }, enabled = !procesando,
                    modifier = Modifier.fillMaxWidth()) {
                    Text("Volver a editar", color = GrisTexto)
                }
            } else {
                Text(tipo, color = AzulNoche, fontSize = 23.sp,
                    fontWeight = FontWeight.Bold)
                Text(
                    when (tipo) {
                        "Depositar" -> "Añade fondos ficticios a tu cuenta."
                        "Retirar" -> "Retira fondos según tu saldo disponible."
                        "Transferir" -> "Envía dinero ficticio a una cuenta registrada."
                        else -> "Paga un contrato de luz, agua o internet."
                    }, color = GrisTexto, fontSize = 12.sp
                )
                if (usaSaldo && saldoConocido) {
                    Row(
                        Modifier.fillMaxWidth()
                            .background(Color(0xFFEAF7F4), RoundedCornerShape(13.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Saldo disponible", modifier = Modifier.weight(1f),
                            color = VerdeTexto, fontSize = 12.sp)
                        Text("$ ${precio(saldoCentavos)}", color = VerdeTexto,
                            fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
                OutlinedTextField(
                    value = monto,
                    onValueChange = {
                        if (it.length <= 22) {
                            monto = it
                            alLimpiarError()
                        }
                    },
                    label = { Text("Monto en USD") },
                    placeholder = { Text("25.50") },
                    leadingIcon = { Text("$", color = AzulMedio, fontWeight = FontWeight.Bold) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = if (pideDestino) ImeAction.Next else ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        foco.clearFocus()
                        teclado?.hide()
                    }),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    isError = errorMonto != null || errorSaldoServidor,
                    supportingText = {
                        Text(
                            errorMonto ?: if (errorSaldoServidor) errorServidor.orEmpty()
                            else "Monto positivo de máximo dos decimales.",
                            color = if (errorMonto != null || errorSaldoServidor) RojoTexto else GrisTexto
                        )
                    }
                )
                if (pideDestino) {
                    OutlinedTextField(
                        value = destino,
                        onValueChange = {
                            if (it.length <= 100) {
                                destino = it
                                alLimpiarError()
                            }
                        },
                        label = { Text(if (tipo == "Transferir") "Cuenta de destino" else "Código de contrato") },
                        placeholder = { Text(if (tipo == "Transferir") "Código C... del destinatario" else "Ej. LUZ123") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            foco.clearFocus()
                            teclado?.hide()
                        }),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        isError = errorDestino != null,
                        supportingText = {
                            Text(errorDestino ?: if (tipo == "Transferir")
                                "Usa el identificador C... de la otra cuenta."
                            else "Introduce el código del servicio.",
                                color = if (errorDestino != null) RojoTexto else GrisTexto)
                        }
                    )
                }
                // No usar Snackbar para errores de REST mientras hay un Dialog abierto.
                if (!errorServidor.isNullOrBlank() && !errorSaldoServidor) {
                    Row(
                        Modifier.fillMaxWidth()
                            .background(Color(0xFFFFEDF0), RoundedCornerShape(13.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("!", color = RojoTexto, fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold)
                        Text(errorServidor, color = RojoTexto, fontSize = 12.sp,
                            modifier = Modifier.weight(1f))
                    }
                }
                Row(
                    Modifier.fillMaxWidth()
                        .background(Color(0xFFEAF7F4), RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("✧", color = VerdeTexto, fontSize = 21.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("El servidor confirma el saldo. Solo dinero ficticio.",
                        color = VerdeTexto, fontSize = 11.sp,
                        modifier = Modifier.weight(1f))
                }
                if (procesando) LinearProgressIndicator(Modifier.fillMaxWidth(), color = VerdeTexto)
                Button(
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(15.dp),
                    enabled = !procesando && camposCorrectos,
                    onClick = {
                        foco.clearFocus()
                        teclado?.hide()
                        if (tipo == "Transferir") revisando = true
                        else alConfirmar(tipo, normalizado, destino.trim())
                    }
                ) {
                    if (procesando) {
                        CircularProgressIndicator(Modifier.size(17.dp),
                            strokeWidth = 2.dp, color = Blanco)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(when {
                        procesando -> "Procesando…"
                        tipo == "Transferir" -> "Revisar transferencia"
                        else -> "Confirmar operación"
                    }, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = alCerrar, enabled = !procesando,
                    modifier = Modifier.fillMaxWidth()) {
                    Text("Cancelar", color = GrisTexto)
                }
            }
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

    Column(Modifier.fillMaxSize().background(Fondo).imePadding().verticalScroll(rememberScrollState())) {
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
                EntradaSuave(0) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.SpaceBetween) {
                        Marca()
                        Box(Modifier.size(105.dp)) {
                            MascotaChispa(Modifier.size(96.dp).align(Alignment.CenterStart))
                            MascotaChispa(Modifier.size(47.dp).align(Alignment.BottomEnd),violeta=true)
                        }
                    }
                }
                EntradaSuave(1) {
                    Text("Tu billetera,\ntu mundo.", fontSize = 34.sp, lineHeight = 39.sp,
                        color = Blanco, fontWeight = FontWeight.ExtraBold)
                }
                EntradaSuave(2) {
                    Text("Transfiere, organiza y consulta tus movimientos en tiempo real.",
                        color = Color(0xFFD5E5F2), fontSize = 13.sp, lineHeight = 20.sp)
                }
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
