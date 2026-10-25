"""BilleteraDistribuida, etapa 7: API académica con autenticación y PostgreSQL.

NO es un sistema para dinero real. Primero probar en local. Antes de abrir al
público se requieren controles adicionales (TLS, rate limit persistente, etc.).
"""
import asyncio
import hashlib
import json
import re
import os
import secrets
import time
import uuid
from collections import defaultdict
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Annotated

import jwt
import psycopg
from dotenv import load_dotenv
from fastapi import Depends, FastAPI, Header, HTTPException, Request, WebSocket, WebSocketDisconnect, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from jwt.exceptions import InvalidTokenError
from psycopg import errors
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb
from pwdlib import PasswordHash
from pydantic import BaseModel, EmailStr, Field, field_validator
from starlette.concurrency import run_in_threadpool
from datetime import datetime, timezone

load_dotenv(Path(__file__).with_name('.env'))

JWT_ALGORITHM = 'HS256'
JWT_ISSUER = 'billetera-distribuida'
JWT_AUDIENCE = 'billetera-android'
JWT_TTL_MINUTES = 30
PASSWORD_HASH = PasswordHash.recommended()
# Un hash real para no dejar obvia la existencia del correo por tiempos de respuesta.
DUMMY_HASH = PASSWORD_HASH.hash('contraseña-inexistente-para-comparación')
PUBLIC_MODE = os.getenv('PUBLIC_MODE', 'false').lower() == 'true'
REGISTRATION_ENABLED = os.getenv('REGISTRATION_ENABLED', 'false').lower() == 'true'
REGISTRATION_ALLOWED_EMAILS = {
    e.strip().lower() for e in os.getenv('REGISTRATION_ALLOWED_EMAILS', '').split(',')
    if e.strip()
}
DEMO_REGISTRATION_ENABLED = (
    os.getenv("DEMO_REGISTRATION_ENABLED", "false")
    .strip()
    .lower() == "true"
)

DEMO_REGISTRATION_UNTIL = os.getenv(
    "DEMO_REGISTRATION_UNTIL", ""
).strip()
REQUIRE_IDEMPOTENCY = os.getenv('REQUIRE_IDEMPOTENCY', 'true').lower() == 'true'
# No habilitar registros públicos anónimos en este entorno académico.
def registro_demo_activo():
    if not DEMO_REGISTRATION_ENABLED:
        return False

    if not DEMO_REGISTRATION_UNTIL:
        return False

    try:
        fecha_fin = datetime.fromisoformat(
            DEMO_REGISTRATION_UNTIL.replace("Z", "+00:00")
        )

        if fecha_fin.tzinfo is None:
            return False

        return datetime.now(timezone.utc) < fecha_fin

    except ValueError:
        return False
        
def validar_configuracion_publica():
    if PUBLIC_MODE and REGISTRATION_ENABLED and not REGISTRATION_ALLOWED_EMAILS:
        if not DEMO_REGISTRATION_ENABLED or not DEMO_REGISTRATION_UNTIL:
            raise RuntimeError(
                "En PUBLIC_MODE, el registro requiere correos "
                "autorizados o un modo demo con fecha límite"
            )

    if PUBLIC_MODE and not REQUIRE_IDEMPOTENCY:
        raise RuntimeError(
            "En PUBLIC_MODE se exige REQUIRE_IDEMPOTENCY=true"
        )


def limitar_solicitudes(clave: str, maximo: int, segundos: int):
    # Ventana fija persistida en PostgreSQL
    huella = hashlib.sha256(clave.encode('utf-8')).hexdigest()
    with conectar() as con:
        dato = con.execute('''
            INSERT INTO limites_acceso (clave, inicio, cantidad)
            VALUES (%s, NOW(), 1)
            ON CONFLICT (clave) DO UPDATE SET
              cantidad = CASE WHEN limites_acceso.inicio < NOW() - (%s * INTERVAL '1 second')
                THEN 1 ELSE limites_acceso.cantidad + 1 END,
              inicio = CASE WHEN limites_acceso.inicio < NOW() - (%s * INTERVAL '1 second')
                THEN NOW() ELSE limites_acceso.inicio END
            RETURNING cantidad
        ''', (huella, segundos, segundos)).fetchone()
    if int(dato['cantidad']) > maximo:
        raise HTTPException(status_code=429, detail='Demasiadas solicitudes. Intenta más tarde.',
                            headers={'Retry-After': str(segundos)})


def preparar_idempotencia(clave: str | None) -> str | None:
    if clave is None or not clave.strip():
        if REQUIRE_IDEMPOTENCY:
            raise HTTPException(status_code=428, detail='Falta Idempotency-Key')
        return None
    clave = clave.strip()
    if len(clave) > 128 or not re.fullmatch(r'[A-Za-z0-9._:-]{8,128}', clave):
        raise HTTPException(status_code=400, detail='Idempotency-Key inválida')
    return clave


def idempotente(con, cuenta: str, ruta: str, cuerpo: dict, clave: str | None, ejecutar):
    # Reserva, operación y respuesta en una misma transacción
    if clave is None:
        return ejecutar(), True
    huella = hashlib.sha256(json.dumps(cuerpo, sort_keys=True,
                                      separators=(',', ':')).encode()).hexdigest()
    insertada = con.execute('''
      INSERT INTO operaciones_idempotentes (cuenta, clave, ruta, huella)
      VALUES (%s,%s,%s,%s)
      ON CONFLICT (cuenta, clave) DO NOTHING
      RETURNING clave
    ''', (cuenta, clave, ruta, huella)).fetchone()
    if insertada is None:
        previa = con.execute('''SELECT ruta, huella, respuesta
            FROM operaciones_idempotentes WHERE cuenta=%s AND clave=%s''',
            (cuenta, clave)).fetchone()
        if not previa or previa['ruta'] != ruta or previa['huella'] != huella:
            raise HTTPException(status_code=409, detail='Idempotency-Key usada con otra operación')
        if previa['respuesta'] is None:
            raise HTTPException(status_code=503, detail='Reintenta la solicitud')
        return previa['respuesta'], False
    respuesta = ejecutar()
    con.execute('''UPDATE operaciones_idempotentes SET respuesta = %s
        WHERE cuenta=%s AND clave=%s''', (Jsonb(respuesta), cuenta, clave))
    return respuesta, True


def configurar_jwt():
    secreto = os.getenv('JWT_SECRET', '')
    if len(secreto) < 64:
        raise RuntimeError('Falta JWT_SECRET fuerte en .env. Usa: openssl rand -hex 32')
    return secreto


def conectar():
    url = os.getenv('DATABASE_URL')
    if not url:
        raise RuntimeError('Falta DATABASE_URL en .env')
    return psycopg.connect(url, row_factory=dict_row, connect_timeout=10)


def iniciar_bd():
    with conectar() as con:
        # Idempotente; preserva cuentas y movimientos de Etapa 6.
        con.execute('''CREATE TABLE IF NOT EXISTS cuentas (
            cuenta TEXT PRIMARY KEY,
            saldo_centavos BIGINT NOT NULL DEFAULT 0 CHECK (saldo_centavos >= 0))''')
        con.execute('''CREATE TABLE IF NOT EXISTS movimientos (
            id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
            cuenta TEXT NOT NULL REFERENCES cuentas(cuenta),
            tipo TEXT NOT NULL,
            monto_centavos BIGINT NOT NULL CHECK (monto_centavos > 0),
            detalle TEXT NOT NULL,
            fecha_millis BIGINT NOT NULL)''')
        con.execute('''CREATE INDEX IF NOT EXISTS ix_movimientos_cuenta_id
            ON movimientos (cuenta, id DESC)''')
        con.execute('''CREATE TABLE IF NOT EXISTS usuarios (
            id TEXT PRIMARY KEY,
            email TEXT NOT NULL UNIQUE,
            password_hash TEXT NOT NULL,
            cuenta TEXT NOT NULL UNIQUE REFERENCES cuentas(cuenta),
            creado_en TIMESTAMPTZ NOT NULL DEFAULT NOW())''')
        # Las cuentas preexistentes CUENTA001 y CUENTA002 NO se asignan a nadie.
        con.execute('''CREATE TABLE IF NOT EXISTS limites_acceso (
            clave TEXT PRIMARY KEY,
            inicio TIMESTAMPTZ NOT NULL,
            cantidad INTEGER NOT NULL)''')
        con.execute('''CREATE TABLE IF NOT EXISTS operaciones_idempotentes (
            cuenta TEXT NOT NULL REFERENCES cuentas(cuenta),
            clave TEXT NOT NULL,
            ruta TEXT NOT NULL,
            huella TEXT NOT NULL,
            respuesta JSONB,
            PRIMARY KEY (cuenta, clave))''')


@asynccontextmanager
async def lifespan(app: FastAPI):
    configurar_jwt()  # Fail closed: impide iniciar sin secreto fuerte.
    validar_configuracion_publica()
    await run_in_threadpool(iniciar_bd)
    yield


app = FastAPI(title='BilleteraDistribuida API DEMO', version='5.0', lifespan=lifespan,
              docs_url=None if PUBLIC_MODE else '/docs',
              redoc_url=None if PUBLIC_MODE else '/redoc',
              openapi_url=None if PUBLIC_MODE else '/openapi.json')
seguridad = HTTPBearer(auto_error=False)


class Credenciales(BaseModel):
    email: EmailStr
    password: str = Field(min_length=12, max_length=128)

    @field_validator('email')
    @classmethod
    def normalizar_correo(cls, email: str):
        return email.strip().lower()


class AuthRespuesta(BaseModel):
    access_token: str
    token_type: str = 'bearer'
    cuenta: str


class MontoSolicitud(BaseModel):
    cuenta: str = Field(min_length=1)
    montoCentavos: int = Field(gt=0, le=100_000_000)


class TransferenciaSolicitud(MontoSolicitud):
    destino: str = Field(min_length=1)


class ServicioSolicitud(MontoSolicitud):
    codigoContrato: str = Field(min_length=1, max_length=100)


def crear_token(usuario_id: str) -> str:
    ahora = datetime.now(timezone.utc)
    return jwt.encode({
        'sub': usuario_id,
        'iss': JWT_ISSUER,
        'aud': JWT_AUDIENCE,
        'iat': ahora,
        'exp': ahora + timedelta(minutes=JWT_TTL_MINUTES),
        'jti': secrets.token_hex(16),
    }, configurar_jwt(), algorithm=JWT_ALGORITHM)


def decodificar_token(token: str) -> dict:
    try:
        return jwt.decode(
            token, configurar_jwt(), algorithms=[JWT_ALGORITHM],
            audience=JWT_AUDIENCE, issuer=JWT_ISSUER,
            options={'require': ['sub', 'iss', 'aud', 'iat', 'exp', 'jti']},
        )
    except InvalidTokenError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail='Sesión inválida o vencida',
            headers={'WWW-Authenticate': 'Bearer'},
        )


def leer_usuario_id(token: str) -> str:
    datos = decodificar_token(token)
    try:
        return str(uuid.UUID(datos['sub']))
    except (ValueError, TypeError, KeyError):
        raise HTTPException(status_code=401, detail='Sesión inválida')


def buscar_usuario(usuario_id: str):
    with conectar() as con:
        return con.execute(
            'SELECT id, email, cuenta FROM usuarios WHERE id = %s', (usuario_id,)
        ).fetchone()


def usuario_autenticado(
    cred: Annotated[HTTPAuthorizationCredentials | None, Depends(seguridad)],
):
    if cred is None or cred.scheme.lower() != 'bearer':
        raise HTTPException(status_code=401, detail='Debes iniciar sesión',
                            headers={'WWW-Authenticate': 'Bearer'})
    usuario = buscar_usuario(leer_usuario_id(cred.credentials))
    if not usuario:
        raise HTTPException(status_code=401, detail='Usuario no válido')
    return usuario


def exigir_propiedad(cuenta: str, usuario: dict):
    if cuenta != usuario['cuenta']:
        raise HTTPException(status_code=403, detail='No puedes operar esta cuenta')


@app.post('/auth/registro', response_model=AuthRespuesta, status_code=201)
def registrar_usuario(datos: Credenciales, request: Request):
    # Por defecto, no se crean cuentas desde internet sin autorización expresa.
    if not REGISTRATION_ENABLED:
        raise HTTPException(
            status_code=403,
            detail="Registro cerrado. Contacta al responsable del proyecto."
        )

    if REGISTRATION_ALLOWED_EMAILS:
        if str(datos.email) not in REGISTRATION_ALLOWED_EMAILS:
            raise HTTPException(
                status_code=403,
                detail="Correo no autorizado para esta demo"
            )
    
    elif PUBLIC_MODE and not registro_demo_activo():
        raise HTTPException(
            status_code=403,
            detail="El registro de demostración está cerrado o ha vencido"
        )
    limitar_solicitudes(f'registro:correo:{datos.email}', 3, 3600)
    limitar_solicitudes(f'registro:ip:{request.client.host if request.client else "unknown"}', 15, 3600)
    # Argon2 antes de abrir transacción: evita retener bloqueos mientras calcula hash.
    hash_clave = PASSWORD_HASH.hash(datos.password)
    usuario_id = str(uuid.uuid4())
    nueva_cuenta = 'C' + uuid.uuid4().hex[:20].upper()
    try:
        with conectar() as con:
            con.execute('INSERT INTO cuentas (cuenta, saldo_centavos) VALUES (%s, 0)',
                        (nueva_cuenta,))
            con.execute(
                'INSERT INTO usuarios (id, email, password_hash, cuenta) VALUES (%s,%s,%s,%s)',
                (usuario_id, str(datos.email), hash_clave, nueva_cuenta),
            )
    except errors.UniqueViolation:
        raise HTTPException(status_code=409, detail='No se pudo crear la cuenta; revisa tus datos')
    return AuthRespuesta(access_token=crear_token(usuario_id), cuenta=nueva_cuenta)


@app.post('/auth/login', response_model=AuthRespuesta)
def login(datos: Credenciales, request: Request):
    limitar_solicitudes(f'login:correo:{datos.email}', 8, 600)
    limitar_solicitudes(f'login:ip:{request.client.host if request.client else "unknown"}', 60, 600)
    with conectar() as con:
        fila = con.execute('SELECT id, cuenta, password_hash FROM usuarios WHERE email = %s',
                           (str(datos.email),)).fetchone()
    if not fila:
        PASSWORD_HASH.verify(datos.password, DUMMY_HASH)
        raise HTTPException(status_code=401, detail='Correo o contraseña incorrectos')
    if not PASSWORD_HASH.verify(datos.password, fila['password_hash']):
        raise HTTPException(status_code=401, detail='Correo o contraseña incorrectos')
    return AuthRespuesta(access_token=crear_token(fila['id']), cuenta=fila['cuenta'])


@app.get('/auth/yo')
def yo(usuario: Annotated[dict, Depends(usuario_autenticado)]):
    return {'email': usuario['email'], 'cuenta': usuario['cuenta']}


def consultar_saldo(con, cuenta: str, *, bloquear=False) -> int:
    sql = 'SELECT saldo_centavos FROM cuentas WHERE cuenta = %s'
    if bloquear:
        sql += ' FOR UPDATE'
    fila = con.execute(sql, (cuenta,)).fetchone()
    if fila is None:
        raise HTTPException(status_code=404, detail='Cuenta inexistente')
    return int(fila['saldo_centavos'])


def movimiento(con, cuenta: str, tipo: str, centavos: int, detalle: str):
    instante = int(time.time() * 1000)
    fila = con.execute('''INSERT INTO movimientos
        (cuenta, tipo, monto_centavos, detalle, fecha_millis)
        VALUES (%s,%s,%s,%s,%s) RETURNING id''',
        (cuenta, tipo, centavos, detalle, instante)).fetchone()
    return {'id': int(fila['id']), 'cuenta': cuenta, 'tipo': tipo,
            'montoCentavos': centavos, 'detalle': detalle, 'fechaMillis': instante,
            'saldoCentavos': consultar_saldo(con, cuenta)}


class GestorWebSockets:
    # El proceso único funciona con --workers 1; para más réplicas hace falta pub/sub.
    def __init__(self):
        self.clientes: dict[str, set[WebSocket]] = defaultdict(set)
        self.bloqueos: dict[str, asyncio.Lock] = defaultdict(asyncio.Lock)

    async def conectar(self, cuenta: str, socket: WebSocket):
        await socket.accept()
        self.clientes[cuenta].add(socket)

    def desconectar(self, cuenta: str, socket: WebSocket):
        self.clientes[cuenta].discard(socket)

    async def notificar(self, cuenta: str, info: dict):
        evento = {'evento': 'TRANSFERENCIA_RECIBIDA', 'movimiento': info}
        async with self.bloqueos[cuenta]:
            for socket in list(self.clientes[cuenta]):
                try:
                    await socket.send_json(evento)
                except Exception:
                    self.desconectar(cuenta, socket)


gestor = GestorWebSockets()


@app.websocket('/ws/{cuenta}')
async def socket_cuenta(websocket: WebSocket, cuenta: str):
    # OkHttp Android permite enviar Authorization en el handshake (no usar URL ?token=).
    encabezado = websocket.headers.get('authorization', '')
    if not encabezado.lower().startswith('bearer '):
        await websocket.close(code=1008)
        return
    token = encabezado[7:].strip()
    try:
        info = decodificar_token(token)
        usuario_id = leer_usuario_id(token)
        usuario = await run_in_threadpool(buscar_usuario, usuario_id)
        if usuario is None or usuario['cuenta'] != cuenta:
            raise HTTPException(status_code=403, detail='Cuenta ajena')
    except HTTPException:
        await websocket.close(code=1008)
        return
    # La sesión WebSocket tampoco debe superar la expiración del token.
    expiracion = float(info['exp'])
    await gestor.conectar(cuenta, websocket)
    try:
        while True:
            segundos = expiracion - time.time()
            if segundos <= 0:
                await websocket.close(code=1008, reason='Sesión vencida')
                break
            try:
                await asyncio.wait_for(websocket.receive_text(), timeout=segundos)
            except asyncio.TimeoutError:
                await websocket.close(code=1008, reason='Sesión vencida')
                break
    except WebSocketDisconnect:
        pass
    finally:
        gestor.desconectar(cuenta, websocket)


@app.get('/salud')
def salud():
    with conectar() as con:
        con.execute('SELECT 1')
    return {'estado': 'ok'}


@app.get('/cuentas/{cuenta}')
def obtener_cuenta(cuenta: str, usuario: Annotated[dict, Depends(usuario_autenticado)]):
    exigir_propiedad(cuenta, usuario)
    with conectar() as con:
        return {'cuenta': cuenta, 'saldoCentavos': consultar_saldo(con, cuenta)}


@app.get('/movimientos/{cuenta}')
def obtener_movimientos(cuenta: str, usuario: Annotated[dict, Depends(usuario_autenticado)]):
    exigir_propiedad(cuenta, usuario)
    with conectar() as con:
        consultar_saldo(con, cuenta)
        return con.execute('''SELECT id, cuenta, tipo,
            monto_centavos AS "montoCentavos", detalle,
            fecha_millis AS "fechaMillis"
            FROM movimientos WHERE cuenta = %s ORDER BY id DESC''', (cuenta,)).fetchall()


@app.post('/depositos')
def depositar(datos: MontoSolicitud, usuario: Annotated[dict, Depends(usuario_autenticado)],
             clave: Annotated[str | None, Header(alias='Idempotency-Key')] = None):
    exigir_propiedad(datos.cuenta, usuario)
    clave = preparar_idempotencia(clave)
    with conectar() as con:
        def ejecutar():
            consultar_saldo(con, datos.cuenta, bloquear=True)
            con.execute('UPDATE cuentas SET saldo_centavos = saldo_centavos + %s WHERE cuenta = %s',
                        (datos.montoCentavos, datos.cuenta))
            return movimiento(con, datos.cuenta, 'DEPOSITO', datos.montoCentavos, 'Depósito REST')
        return idempotente(con, datos.cuenta, '/depositos', datos.model_dump(), clave, ejecutar)[0]


@app.post('/retiros')
def retirar(datos: MontoSolicitud, usuario: Annotated[dict, Depends(usuario_autenticado)],
            clave: Annotated[str | None, Header(alias='Idempotency-Key')] = None):
    exigir_propiedad(datos.cuenta, usuario)
    clave = preparar_idempotencia(clave)
    with conectar() as con:
        def ejecutar():
            saldo = consultar_saldo(con, datos.cuenta, bloquear=True)
            if saldo < datos.montoCentavos:
                raise HTTPException(status_code=400, detail='Saldo insuficiente')
            con.execute('UPDATE cuentas SET saldo_centavos = saldo_centavos - %s WHERE cuenta = %s',
                        (datos.montoCentavos, datos.cuenta))
            return movimiento(con, datos.cuenta, 'RETIRO', datos.montoCentavos, 'Retiro REST')
        return idempotente(con, datos.cuenta, '/retiros', datos.model_dump(), clave, ejecutar)[0]


@app.post('/transferencias')
async def transferir(datos: TransferenciaSolicitud,
                    usuario: Annotated[dict, Depends(usuario_autenticado)],
                    clave: Annotated[str | None, Header(alias='Idempotency-Key')] = None):
    exigir_propiedad(datos.cuenta, usuario)
    origen, destino = datos.cuenta, datos.destino.strip()
    if not destino or origen == destino:
        raise HTTPException(status_code=400, detail='Cuenta destino inválida')
    clave = preparar_idempotencia(clave)

    def efectuar():
        with conectar() as con:
            def ejecutar():
                if not con.execute('SELECT 1 FROM usuarios WHERE cuenta = %s', (destino,)).fetchone():
                    raise HTTPException(status_code=404, detail='Cuenta destino no registrada')
                for cuenta in sorted((origen, destino)):
                    consultar_saldo(con, cuenta, bloquear=True)
                if consultar_saldo(con, origen) < datos.montoCentavos:
                    raise HTTPException(status_code=400, detail='Saldo insuficiente')
                con.execute('UPDATE cuentas SET saldo_centavos = saldo_centavos - %s WHERE cuenta = %s',
                            (datos.montoCentavos, origen))
                con.execute('UPDATE cuentas SET saldo_centavos = saldo_centavos + %s WHERE cuenta = %s',
                            (datos.montoCentavos, destino))
                salida = movimiento(con, origen, 'TRANSFERENCIA_ENVIADA', datos.montoCentavos,
                                    f'Para {destino}')
                entrada = movimiento(con, destino, 'TRANSFERENCIA_RECIBIDA', datos.montoCentavos,
                                     f'Desde {origen}')
                return {'salida': salida, 'entrada': entrada}
            paquete, es_nueva = idempotente(
                con, origen, '/transferencias',
                {**datos.model_dump(), 'destino': destino}, clave, ejecutar)
        return paquete, es_nueva

    paquete, es_nueva = await run_in_threadpool(efectuar)
    if es_nueva:
        await gestor.notificar(destino, paquete['entrada'])
    return paquete['salida']


@app.post('/servicios/pagar')
def pagar_servicio(datos: ServicioSolicitud,
                   usuario: Annotated[dict, Depends(usuario_autenticado)],
                   clave: Annotated[str | None, Header(alias='Idempotency-Key')] = None):
    exigir_propiedad(datos.cuenta, usuario)
    clave = preparar_idempotencia(clave)
    codigo = datos.codigoContrato.strip()
    if not codigo:
        raise HTTPException(status_code=400, detail='Código de contrato obligatorio')
    with conectar() as con:
        def ejecutar():
            saldo = consultar_saldo(con, datos.cuenta, bloquear=True)
            if saldo < datos.montoCentavos:
                raise HTTPException(status_code=400, detail='Saldo insuficiente')
            con.execute('UPDATE cuentas SET saldo_centavos = saldo_centavos - %s WHERE cuenta = %s',
                        (datos.montoCentavos, datos.cuenta))
            return movimiento(con, datos.cuenta, 'SERVICIO_BASICO', datos.montoCentavos,
                              f'Servicio Básico - contrato {codigo}')
        return idempotente(con, datos.cuenta, '/servicios/pagar',
                          {**datos.model_dump(), 'codigoContrato': codigo}, clave, ejecutar)[0]
