"""Prueba destructiva SOLO para mensajes de prueba en colas vacias, broker real.

Requiere Notify configurado con SMTP_HOST=127.0.0.1 y SMTP_PORT=1.
No simula RabbitMQ ni SMTP: provoca una conexion SMTP realmente rechazada.
Lee las credenciales administrativas en memoria del Compose de Infra, sin editarlas.
"""
import argparse
import base64
import datetime
import json
from pathlib import Path
import subprocess
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--infra", type=Path, default=ROOT.parent / "digitalfix-infra")
    args = parser.parse_args()
    compose = ["docker", "compose", "--project-directory", str(ROOT)]

    def run(*command):
        return subprocess.check_output([*compose, *command], text=True, timeout=120).strip()

    config = json.loads(run("config", "--format", "json"))
    env = config["services"]["notify"]["environment"]
    if env["SMTP_HOST"] != "127.0.0.1" or str(env["SMTP_PORT"]) != "1":
        raise SystemExit("Solo ejecutar con SMTP local indisponible, puerto 1")
    infra = json.loads(subprocess.check_output([
        "docker", "compose", "--env-file", str(args.infra / "mq/.env"),
        "-f", str(args.infra / "mq/compose.yml"), "config", "--format", "json"]))
    admin = infra["services"]["rabbitmq1"]["environment"]
    token = base64.b64encode((admin["RABBITMQ_DEFAULT_USER"] + ":" + admin["RABBITMQ_DEFAULT_PASS"]).encode()).decode()

    def api(method, route, data=None):
        req = urllib.request.Request("http://127.0.0.1:15672/api/" + route, method=method,
            data=None if data is None else json.dumps(data).encode(),
            headers={"Authorization": "Basic " + token, "Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=15) as response:
            content = response.read()
            return json.loads(content) if content else None

    queues = ["q.cmd.email", "q.cmd.dispatch"]
    for queue in queues:
        for name in (queue, queue + ".dlq"):
            data = api("GET", "queues/digitalfix/" + name)
            if data.get("messages", 0) or data.get("messages_unacknowledged", 0):
                raise SystemExit("Hay mensajes existentes; no se consume ni purga contenido ajeno")
        if api("GET", "queues/digitalfix/" + queue)["consumers"] != 1:
            raise SystemExit("Debe existir exactamente un consumidor por cola")

    def envelope(kind, payload):
        return {"schemaVersion": "1.0", "type": kind, "eventId": str(uuid.uuid4()),
                "timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                "traceId": str(uuid.uuid4()), "correlationId": "15", "payload": payload}

    email = envelope("ORDEN_ASIGNADA", {"ordenId": 15, "emailContacto": "cliente@example.org"})
    dispatch = envelope("DESPACHO_TECNICO", {"ordenId": 15, "emailTecnico": "tecnico@example.org",
        "tecnicoId": "tecnico-01", "servicioId": 2, "servicioNombre": "Revisión eléctrica",
        "direccion": "Avenida Ñuñoa 123"})

    def publicar(exchange, key, payload):
        result = api("POST", "exchanges/digitalfix/" + exchange + "/publish", {
            "properties": {"delivery_mode": 2, "content_type": "application/json"},
            "routing_key": key, "payload": payload, "payload_encoding": "string"})
        assert result["routed"], "Mensaje sin ruta"

    def esperar(dlq, count):
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            if api("GET", "queues/digitalfix/" + dlq).get("messages_ready", 0) == count:
                return
            time.sleep(2)
        raise RuntimeError("No llego la cantidad esperada a " + dlq)

    def retirar(dlq, expected):
        messages = api("POST", "queues/digitalfix/" + dlq + "/get", {
            "count": len(expected), "ackmode": "ack_requeue_false", "encoding": "auto"})
        assert sorted(m["payload"] for m in messages) == sorted(expected)
        assert all(m["properties"]["headers"]["x-death"][0]["reason"] == "rejected" for m in messages)

    raw_email = json.dumps(email, ensure_ascii=False)
    raw_dispatch = json.dumps(dispatch, ensure_ascii=False)
    invalid = "{json-invalido-" + str(uuid.uuid4())
    publicar("cmd.topic", "email.en_camino", raw_email)
    publicar("cmd.direct", "dispatch.ticket", raw_dispatch)
    publicar("cmd.direct", "email.send", invalid)
    publicar("cmd.topic", "dispatch.ticket.prueba", invalid)
    esperar("q.cmd.email.dlq", 2); esperar("q.cmd.dispatch.dlq", 2)
    logs = run("logs", "--no-color")
    for message in (email, dispatch):
        for attempt in (1, 2, 3):
            assert any(message["eventId"] in line and f"intento={attempt} " in line for line in logs.splitlines())
        run("exec", "-T", "notify", "test", "!", "-f", "/data/" + message["eventId"] + ".ok")
    pdf = "/data/" + dispatch["eventId"] + ".pdf"
    before = run("exec", "-T", "notify", "sha256sum", pdf)
    retirar("q.cmd.email.dlq", [raw_email, invalid]); retirar("q.cmd.dispatch.dlq", [raw_dispatch, invalid])
    print("OK: ambas colas, direct/topic, tres intentos SMTP y rechazo de JSON hacia DLQ reales")

    run("restart", "notify")
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        if all(api("GET", "queues/digitalfix/" + q)["consumers"] == 1 for q in queues):
            break
        time.sleep(2)
    else:
        raise RuntimeError("Notify no recupero consumidores")
    publicar("cmd.direct", "dispatch.ticket", raw_dispatch)
    esperar("q.cmd.dispatch.dlq", 1)
    assert run("exec", "-T", "notify", "sha256sum", pdf) == before
    retirar("q.cmd.dispatch.dlq", [raw_dispatch])
    output = ROOT / "evidencias-locales"
    output.mkdir(exist_ok=True)
    run("cp", "notify:" + pdf, str(output / "ticket-prueba.pdf"))
    print("OK: reinicio, mismo eventId reprocesado, PDF persistente y reutilizado")
    print("PENDIENTE: SMTP exitoso/recepcion externa y ACK de exito real; no se simularon")


if __name__ == "__main__":
    main()
