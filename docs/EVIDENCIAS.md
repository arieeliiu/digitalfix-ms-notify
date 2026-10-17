# Evidencias EP3/EP4 — Paso 2 Notify

Fecha: 2026-10-17. Rama: `feature/rabbitmq-notify`.
Entorno: Windows, Java 21, Docker Desktop con contenedores Linux y clúster real
RabbitMQ 4.2.9 del Paso 1. No se modificaron archivos de Infra ni de otros servicios.

## Pruebas unitarias ejecutadas

`mvnw.cmd test`: **13 pruebas, 0 fallos, 0 errores, 0 omitidas**.

- Los tres tipos de correo seleccionan el destinatario del payload.
- Deduplicación después de cerrar y reabrir el registro persistente.
- Máximo de tres intentos y esperas de 2/5 segundos.
- SMTP 550 clasificado como permanente, sin esperas.
- PDF real legible con acentos; reutilización después de fallo SMTP y reinicio.
- JSON inválido, correo con formato de UUID y correlación inconsistente rechazados.
- Conflicto al reutilizar un eventId con otro contenido.
- ACK después de procesar; NACK sin requeue ante fallo, sin ACK de éxito.
- Bloqueo de una segunda instancia sobre el mismo volumen.
- Fallo al persistir éxito no repite SMTP dentro de la misma ejecución.

En estas pruebas SMTP está aislado con mocks. **No prueban entrega real de correo.**
PDFBox, los archivos y la reapertura del registro son reales.

## Integración ejecutada con RabbitMQ real

La imagen de producción se construyó y arrancó como usuario `digitalfix` (UID 10001).
Se creó un usuario RabbitMQ `digitalfix_notify` sin tags administrativos, limitado
a sus exchanges/colas. Notify declaró los tres exchanges y cuatro colas (dos
principales y dos DLQ), sin crear Report.

Se configuró SMTP deliberadamente indisponible (`127.0.0.1:1` dentro del contenedor).
Esto provoca un fallo de conexión real; no es un servidor SMTP simulado.

Comando reproducible:

```sh
python scripts/verificar_fallos.py --infra ../digitalfix-infra
```

Resultados observados:

- Consumo real de `q.cmd.email` y `q.cmd.dispatch` mediante los listeners Spring.
- Enrutamiento mediante direct y topic, incluidos `email.*` y `dispatch.#`.
- Tres intentos reales de conexión SMTP por mensaje válido y posterior DLQ.
- Mensajes JSON inválidos rechazados y enviados a ambas DLQ.
- `x-death.reason=rejected` en los mensajes recuperados de DLQ.
- Ningún archivo `.ok` para operaciones que no completaron SMTP.
- PDF real generado antes del fallo de SMTP.
- Reinicio de Notify, republicación controlada del mismo cuerpo/eventId y hash del
  PDF conservado: no se regeneró ni se perdió el documento.
- Mensajes de prueba retirados de las DLQ por el script sin purgas generales.

El PDF exportado a `evidencias-locales/ticket-prueba.pdf` se renderizó y revisó
visualmente: una página, texto legible, orden/técnico/servicio/dirección/fecha/UUID,
acentos y caracteres españoles correctos. Los artefactos locales no se versionan.

Docker Desktop perdió conexión durante una comprobación posterior; se recuperó
sin eliminar volúmenes. No se atribuye ese incidente al servicio ni se afirma
tolerancia ante pérdida del host. La comprobación completa posterior terminó con
exit 0 y su salida quedó en `evidencias-locales/integracion.log` (no versionada).
El script permite repetir la comprobación con colas vacías y sin otros productores.

## Registro inicial de funcionalidades

| Funcionalidad | Estado | Responsable | Evidencia |
|---|---|---|---|
| Topología centralizada durable | Implementada y comprobada | RabbitConfig / Topologia | Arranque real con usuario limitado |
| Consumidores por dominio | Implementados y comprobados | Consumidores | Integración en ambas colas |
| NACK, reintentos y DLQ | Implementados y comprobados | Procesador / Consumidores | Script real, x-death y logs |
| Generación y persistencia PDF | Implementada y comprobada | TicketPdf / RegistroPersistente | PDF real, hash antes/después, revisión visual |
| Deduplicación de éxito y ACK | Implementados; validación unitaria | RegistroPersistente / Consumidores | Pruebas de reapertura y orden de ACK |
| Envío SMTP y adjunto | Implementado; integración exitosa pendiente | CorreoSmtp | Falta SMTP autorizado disponible |
| Recuperación de operación fallida | Comprobada hasta nuevo fallo SMTP | Procesador | Reprocesamiento con mismo eventId, PDF reutilizado |

Esta tabla es parcial y no determina el porcentaje global del proyecto. No contar
el envío externo como funcionalidad demostrada hasta recibir correos reales.

## Pendientes explícitos

No se dispuso de credenciales SMTP ni destinatarios reales autorizados durante
esta implementación. Falta verificar:

1. Recepción real de las tres notificaciones al cliente.
2. Recepción del ticket PDF en el correo del técnico.
3. ACK de éxito y marcador `.ok` en el flujo RabbitMQ → SMTP real.
4. Redelivery de una operación exitosa y reinicio sin segundo correo real.
5. Recuperación desde DLQ después de restablecer SMTP con finalización exitosa.
6. Despliegue EC2, pruebas de carga e integración con Workorders.

La configuración de fallo local no debe utilizarse como configuración productiva.
No se ha abierto PR ni hecho merge; el usuario gestionará el PR.
