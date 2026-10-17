package cl.digitalfix.notify.service;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import jakarta.mail.SendFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import cl.digitalfix.notify.dto.Contrato.*;

@Service
public class Procesador {
    public enum Dominio { EMAIL, DISPATCH }
    private static final Logger log = LoggerFactory.getLogger(Procesador.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final Validator validator;
    private final RegistroPersistente registro;
    private final TicketPdf tickets;
    private final CorreoSmtp correo;
    private final Espera espera;

    public Procesador(Validator validator, RegistroPersistente registro, TicketPdf tickets,
                      CorreoSmtp correo, Espera espera) {
        this.validator = validator; this.registro = registro; this.tickets = tickets;
        this.correo = correo; this.espera = espera;
    }

    // Serializacion deliberada: una instancia y un volumen, sin carreras entre listeners.
    public synchronized void procesar(byte[] cuerpo, Dominio dominio) throws Exception {
        Sobre sobre = null;
        Object payload;
        try {
            if (cuerpo.length > 65536) throw new ErrorPermanente("Mensaje mayor a 64 KiB");
            sobre = json.readValue(cuerpo, Sobre.class);
            validar(sobre);
            if (dominio == Dominio.EMAIL) {
                if (!Set.of("ORDEN_ASIGNADA", "TECNICO_EN_CAMINO", "ORDEN_CERRADA").contains(sobre.type()))
                    throw new ErrorPermanente("Tipo incompatible con email");
                payload = json.treeToValue(sobre.payload(), Correo.class);
            } else {
                if (!"DESPACHO_TECNICO".equals(sobre.type())) throw new ErrorPermanente("Tipo incompatible con dispatch");
                payload = json.treeToValue(sobre.payload(), Despacho.class);
            }
            validar(payload);
            Long orden = payload instanceof Correo c ? c.ordenId() : ((Despacho) payload).ordenId();
            if (!orden.toString().equals(sobre.correlationId())) throw new ErrorPermanente("correlationId no coincide con ordenId");
        } catch (Exception e) {
            // No registrar JSON ni excepciones de conversion: pueden contener datos personales.
            String orden = sobre != null && sobre.correlationId() != null
                    && sobre.correlationId().matches("[1-9][0-9]{0,18}") ? sobre.correlationId() : "desconocida";
            log.warn("mensaje_rechazado dominio={} eventId={} traceId={} orden={} intento=0 motivo={}", dominio,
                    sobre == null ? "desconocido" : sobre.eventId(),
                    sobre == null ? "desconocido" : sobre.traceId(), orden,
                    e instanceof ErrorPermanente ? e.getMessage() : "json_invalido");
            throw new ErrorPermanente("Contrato JSON invalido");
        }
        String huella = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.writeValueAsBytes(java.util.List.of(dominio, sobre.schemaVersion(), sobre.type(),
                        sobre.eventId(), sobre.timestamp(), sobre.traceId(), sobre.correlationId(), payload))));
        boolean efectoCompletado = false;
        for (int intento = 1; intento <= 3; intento++) {
            try {
                if (registro.completado(sobre.eventId(), huella)) {
                    registrar(sobre, intento, "duplicado_completado"); return;
                }
                if (!efectoCompletado) {
                    if (payload instanceof Correo c) {
                        String estado = switch (sobre.type()) {
                            case "ORDEN_ASIGNADA" -> "Tu orden fue asignada a un técnico.";
                            case "TECNICO_EN_CAMINO" -> "El técnico está en camino.";
                            default -> "Tu orden fue cerrada.";
                        };
                        correo.enviar(c.emailContacto(), "DigitalFix - Orden " + c.ordenId(), estado, null);
                    } else {
                        var d = (Despacho) payload;
                        var archivo = registro.documento(sobre.eventId());
                        tickets.generarSiFalta(archivo, sobre, d);
                        correo.enviar(d.emailTecnico(), "DigitalFix - Despacho orden " + d.ordenId(),
                                "Se adjunta el ticket de despacho de la orden " + d.ordenId() + ".", archivo);
                    }
                    efectoCompletado = true;
                }
                registro.completar(sobre.eventId());
                registrar(sobre, intento, "completado"); return;
            } catch (Exception e) {
                registrar(sobre, intento, e.getClass().getSimpleName());
                if (permanente(e) || intento == 3) throw e;
                try { espera.pausar(intento == 1 ? 2000 : 5000); }
                catch (InterruptedException interrumpido) { Thread.currentThread().interrupt(); throw interrumpido; }
            }
        }
    }

    private void validar(Object objeto) {
        if (objeto == null || !validator.validate(objeto).isEmpty()) throw new ErrorPermanente("Datos obligatorios invalidos");
    }
    private boolean permanente(Exception error) {
        var pendientes = new java.util.ArrayDeque<Throwable>();
        var vistos = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        pendientes.add(error);
        while (!pendientes.isEmpty()) {
            Throwable e = pendientes.remove();
            if (!vistos.add(e)) continue;
            if (e instanceof ErrorPermanente || e instanceof MailAuthenticationException || e instanceof AddressException)
                return true;
            if (e instanceof SMTPAddressFailedException smtp && smtp.getReturnCode() >= 500) return true;
            if (e instanceof SMTPSendFailedException smtp && smtp.getReturnCode() >= 500) return true;
            if (e instanceof SendFailedException fallo && fallo.getInvalidAddresses() != null
                    && fallo.getInvalidAddresses().length > 0) return true;
            if (e instanceof MailSendException envio)
                pendientes.addAll(java.util.Arrays.asList(envio.getMessageExceptions()));
            if (e.getCause() != null) pendientes.add(e.getCause());
            if (e instanceof MessagingException mensaje && mensaje.getNextException() != null)
                pendientes.add(mensaje.getNextException());
        }
        return false;
    }
    private void registrar(Sobre s, int intento, String motivo) {
        log.info("mensaje eventId={} traceId={} orden={} intento={} motivo={}",
                s.eventId(), s.traceId(), s.correlationId(), intento, motivo);
    }
}
