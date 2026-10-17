# DigitalFix — Notify

Microservicio Spring Boot 4.1.1, Java 21, Maven, Spring AMQP y PDFBox 3.0.8.
Consume RabbitMQ, envía correos SMTP y genera/almacena/envía tickets PDF.
Sin API HTTP, Oracle, Redis, Kafka ni llamadas a Workorders/Catalog.

## Responsabilidades

| Cola | Operación completada antes del ACK |
|---|---|
| `q.cmd.email` | SMTP acepta la notificación y se persiste el registro de éxito |
| `q.cmd.dispatch` | Se guarda el PDF, SMTP acepta el adjunto y se persiste el éxito |

El destinatario sale de `emailContacto` o `emailTecnico`, nunca del `oid`.
Aceptación SMTP no garantiza lectura ni entrega final. Si SMTP acepta y el proceso
cae antes de registrar el éxito, o se pierde la respuesta SMTP, puede haber duplicados.
No se promete exactamente una vez. Si solo falla guardar el registro después del
SMTP dentro de la misma ejecución, se reintenta el registro sin reenviar.

## Contrato para Workorders

JSON UTF-8, máximo 64 KiB. Campos obligatorios; no enviar JWT ni entidades JPA.

| Campo del sobre | Tipo y significado |
|---|---|
| `schemaVersion` | String `"1.0"` |
| `type` | Uno de los tipos admitidos por la cola |
| `eventId` | UUID nuevo por comando; conservarlo en reintentos/redelivery |
| `timestamp` | Instante ISO 8601 con zona; producir en UTC (`Z`) |
| `traceId` | UUID de la transición, compartible entre comandos |
| `correlationId` | ID positivo de orden como texto, igual a `payload.ordenId` |
| `payload` | Objeto específico de cada operación |

### Correo al cliente

Tipos: `ORDEN_ASIGNADA`, `TECNICO_EN_CAMINO`, `ORDEN_CERRADA`.
Payload: `ordenId` (Long positivo), `emailContacto` (email no vacío, máximo 254).

```json
{
  "schemaVersion": "1.0",
  "type": "TECNICO_EN_CAMINO",
  "eventId": "0d380b56-4cc8-4fb4-ab39-d3112964c38c",
  "timestamp": "2026-10-17T14:00:00Z",
  "traceId": "8abe3111-eaf2-4bc1-bc12-8188b2eddfe6",
  "correlationId": "15",
  "payload": {"ordenId": 15, "emailContacto": "cliente@example.org"}
}
```

### Despacho al técnico

Tipo: `DESPACHO_TECNICO`, originado al asignar la orden.

| Campo payload | Restricción |
|---|---|
| `ordenId` | Long positivo |
| `emailTecnico` | Email válido no vacío, máximo 254 caracteres |
| `tecnicoId` | Texto no vacío, máximo 100 caracteres |
| `servicioId` | Long positivo |
| `servicioNombre` | Texto no vacío, máximo 200 caracteres |
| `direccion` | Texto no vacío, máximo 300 caracteres |

```json
{
  "schemaVersion": "1.0",
  "type": "DESPACHO_TECNICO",
  "eventId": "4167b23a-2b03-463b-a622-7ea080ba40c1",
  "timestamp": "2026-10-17T14:00:00Z",
  "traceId": "8abe3111-eaf2-4bc1-bc12-8188b2eddfe6",
  "correlationId": "15",
  "payload": {
    "ordenId": 15,
    "emailTecnico": "tecnico@example.org",
    "tecnicoId": "tecnico-01",
    "servicioId": 2,
    "servicioNombre": "Revisión eléctrica",
    "direccion": "Avenida Ñuñoa 123"
  }
}
```

El PDF usa `timestamp` como fecha de despacho y el nombre del servicio recibido,
sin consultar Catalog. Se guarda en `/data/<eventId>.pdf` y se adjunta como
`ticket-despacho.pdf`. DejaVu Sans permite acentos; se ajustan líneas por ancho.
Los caracteres sin glifo se rechazan, no se sustituyen silenciosamente.

## Topología y configuración

Nombres centralizados en `application.yml`; beans en `config/RabbitConfig`.
Exchanges durables, colas classic durables, sin auto-delete.

| Exchange | Tipo | Binding | Cola |
|---|---|---|---|
| `cmd.direct` | direct | `email.send` | `q.cmd.email` |
| `cmd.topic` | topic | `email.*` | `q.cmd.email` |
| `cmd.direct` | direct | `dispatch.ticket` | `q.cmd.dispatch` |
| `cmd.topic` | topic | `dispatch.#` | `q.cmd.dispatch` |
| `cmd.dead.dlx` | direct | `email.dead` | `q.cmd.email.dlq` |
| `cmd.dead.dlx` | direct | `dispatch.dead` | `q.cmd.dispatch.dlq` |

Cada cola principal declara `x-dead-letter-exchange=cmd.dead.dlx` y su clave de
error. Las DLQ no retornan mensajes automáticamente. No se declara Report.
Workorders publicará mensajes persistentes una sola vez por comando: direct/email.send
para asignación/cierre, topic/email.en_camino para desplazamiento y direct/dispatch.ticket
para despacho. Los confirms y mandatory/returns del productor son independientes del ACK.

## Preparación y ejecución

Levantar primero el clúster del Paso 1. Crear desde Management UI un usuario
`digitalfix_notify` sin tags administrativos, contraseña aleatoria y estos permisos
en el vhost `digitalfix`:

| Permiso | Expresión regular |
|---|---|
| Configure | `^(cmd\.(direct|topic|dead\.dlx)|q\.cmd\.(email|dispatch)(\.dlq)?)$` |
| Write | `^(cmd\.dead\.dlx|q\.cmd\.(email|dispatch)(\.dlq)?)$` |
| Read | `^(cmd\.(direct|topic|dead\.dlx)|q\.cmd\.(email|dispatch))$` |

Los permisos permiten declarar/bindear sus recursos y dead-lettering. No autorizan
Report ni consumo de DLQ. No usar el administrador como credencial de aplicación.

```sh
cp .env.example .env
# Completar credenciales reales sin versionarlas.
docker compose config --quiet
docker compose up -d --build
docker compose logs --tail 100
```

En PowerShell: `Copy-Item .env.example .env`.

| Variable | Uso |
|---|---|
| `RABBITMQ_ADDRESSES` | `rabbitmq1:5672,rabbitmq2:5672` por defecto |
| `RABBITMQ_VHOST` | `digitalfix` |
| `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD` | Usuario limitado |
| `SMTP_HOST`, `SMTP_PORT` | Servidor real; puerto predeterminado 587 |
| `SMTP_USERNAME`, `SMTP_PASSWORD` | Credenciales SMTP |
| `SMTP_FROM` | Remitente autorizado |
| `SMTP_AUTH` | `true` por defecto |
| `SMTP_STARTTLS` | `true`: STARTTLS obligatorio, sin degradación a texto plano |
| `SMTP_SSL` | `false`; para TLS implícito/465 usar true y STARTTLS false |
| `NOTIFY_STORAGE` | `/data`; configurable al ejecutar Java directamente |
| `NOTIFY_FONT` | Ruta TTF; DejaVu Sans instalada en Docker |

Timeout SMTP de conexión: 5 s; lectura/escritura: 10 s. Docker usa UID/GID 10001,
directorio de datos privado y volumen propio. No publica puertos. La red externa
`digitalfix-messaging` debe existir; no usar localhost para acceder al broker desde
el contenedor. No se modifica ningún otro Compose ni repositorio.

```sh
./mvnw test
./mvnw package
# Con variables exportadas y fuente/almacenamiento locales:
java -jar target/digitalfix-ms-notify-0.0.1-SNAPSHOT.jar
```

Windows: `mvnw.cmd`. Las pruebas PDF usan Arial del sistema en Windows y DejaVu Sans
en Linux (`fonts-dejavu-core`). Para Java fuera de Docker se necesita conectividad
privada al broker: el Paso 1 no publica AMQP en el host.

## ACK, errores e idempotencia

Los listeners reciben `Message` crudo. Jackson se ejecuta dentro del procesamiento
controlado: JSON, UUID y fechas inválidos no escapan antes del listener.

- Datos inválidos, tipo incompatible, conflicto de identidad o dirección SMTP
  rechazada como inválida: fallo permanente, NACK sin requeue inmediato.
- Autenticación SMTP fallida: corregir configuración y reprocesar manualmente.
- Fallos transitorios SMTP/E/S: 3 intentos totales, esperas de 2 y 5 segundos.
- Fallo definitivo: NACK con `requeue=false`; RabbitMQ enruta a DLQ.
- Éxito o mensaje previamente completado: ACK manual, nunca en un `finally`.
- Logs: eventId, traceId, orden, intento y categoría. No se registran cuerpos,
  correos, credenciales ni documentos. JSON ilegible tiene IDs desconocidos.

En el volumen se guardan `<eventId>.identidad` (huella del contrato validado),
`<eventId>.pdf` si corresponde y `<eventId>.ok` después de SMTP. Los archivos se
escriben como temporales, se fuerzan a disco y se mueven atómicamente. Se requiere
un filesystem con movimiento atómico. La existencia del PDF no equivale a éxito.
Una identidad no puede reutilizarse para cambiar destinatario/contenido.

Procesamiento serializado entre ambos listeners, prefetch 1 por cola y bloqueo
exclusivo del volumen. Usar UNA instancia, no escalar con volúmenes independientes:
se perdería deduplicación compartida. Esta decisión limita throughput y evita otra
base de datos para el alcance académico. Conservar los registros entre reinicios;
no borrar `.ok` ni ejecutar `down -v`. Respaldar el volumen completo y restringir
acceso porque contiene documentos y datos privados. No se garantiza recuperación
ante pérdida del disco o del host.

### Reprocesamiento de DLQ

1. Corregir la causa (SMTP/configuración), conservando cuerpo y `eventId`.
2. Inspeccionar en Management UI con requeue habilitado para conservar el original.
3. Republicar UNA vez al exchange/key original con `delivery_mode=2`. Verificar que
   se enrutó y que Notify lo procesó antes de retirar el original identificado.
   No purgar la cola completa ni añadir un retorno automático.
4. Un éxito previo se deduplica; un fallo previo reintenta reutilizando el PDF.
   Cambiar contenido con un ID ya registrado se rechaza: requiere un comando nuevo
   o una intervención explícita auditada sobre el registro fallido.

Los tres intentos son por entrega; una caída o republicación manual inicia otra.
Un `requeue=true` automático indefinido no forma parte del procesamiento.

## Pruebas reales

Consultar [evidencias EP3/EP4](docs/EVIDENCIAS.md). Para probar fallos, usar las cuatro
colas vacías y sin otros productores, con SMTP deliberadamente indisponible:

```dotenv
SMTP_HOST=127.0.0.1
SMTP_PORT=1
SMTP_AUTH=false
SMTP_STARTTLS=false
SMTP_FROM=pruebas@example.org
```

```sh
docker compose up -d --build
python scripts/verificar_fallos.py --infra ../digitalfix-infra
```

La prueba usa RabbitMQ real; comprueba ambas DLQ, tres intentos, JSON inválido,
reinicio y PDF persistente. Lee credenciales de Management del Compose de Infra
solo en memoria. Retira únicamente los mensajes que espera encontrar; no ejecutar
con tráfico concurrente. Produce `evidencias-locales/ticket-prueba.pdf`, ignorado.

Para demostrar éxito: configurar SMTP real y destinatarios autorizados, publicar
los ejemplos con IDs nuevos, comprobar recepción/adjunto, `.ok` y ausencia de
mensajes pendientes/unacked. Republicar el mismo JSON y reiniciar: no debe producir
un segundo envío. Un mock unitario no demuestra recepción externa.
