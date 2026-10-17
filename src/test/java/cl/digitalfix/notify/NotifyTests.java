package cl.digitalfix.notify;

import cl.digitalfix.notify.dto.Contrato.*;
import cl.digitalfix.notify.messaging.Consumidores;
import cl.digitalfix.notify.service.*;
import cl.digitalfix.notify.service.Procesador.Dominio;
import com.rabbitmq.client.Channel;
import jakarta.validation.Validation;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.core.*;
import org.springframework.mail.MailSendException;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotifyTests {
    @TempDir Path dir;
    RegistroPersistente registro;
    CorreoSmtp smtp;
    Espera espera;
    Procesador procesador;
    final JsonMapper json = JsonMapper.builder().build();
    final UUID id = UUID.randomUUID();
    final UUID trace = UUID.randomUUID();
    String fuente() {
        return Files.exists(Path.of("C:/Windows/Fonts/arial.ttf")) ? "C:/Windows/Fonts/arial.ttf"
                : "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf";
    }
    @BeforeEach void preparar() throws Exception {
        registro = new RegistroPersistente(dir.toString());
        smtp = mock(CorreoSmtp.class); espera = mock(Espera.class);
        procesador = nuevoProcesador();
    }
    Procesador nuevoProcesador() {
        return new Procesador(Validation.buildDefaultValidatorFactory().getValidator(), registro,
                new TicketPdf(fuente()), smtp, espera);
    }
    @AfterEach void cerrar() throws Exception { registro.cerrar(); }
    byte[] mensaje(String tipo, Object payload) {
        return json.writeValueAsBytes(new Sobre("1.0", tipo, id, Instant.parse("2026-10-17T14:00:00Z"),
                trace, "15", json.valueToTree(payload)));
    }
    byte[] email() { return mensaje("ORDEN_ASIGNADA", new Correo(15L,"cliente@example.org")); }
    byte[] despacho() { return mensaje("DESPACHO_TECNICO", new Despacho(15L,"tecnico@example.org",
            "tecnico-01", 2L,"Revisión eléctrica", "Avenida Ñuñoa 123")); }

    @Test void deduplicaInclusoTrasReabrirRegistro() throws Exception {
        procesador.procesar(email(), Dominio.EMAIL);
        registro.cerrar(); registro = new RegistroPersistente(dir.toString()); procesador = nuevoProcesador();
        procesador.procesar(email(), Dominio.EMAIL);
        verify(smtp, times(1)).enviar(anyString(), anyString(), anyString(), isNull());
    }
    @Test void losTresEstadosEnviables() throws Exception {
        for (String tipo : List.of("ORDEN_ASIGNADA", "TECNICO_EN_CAMINO", "ORDEN_CERRADA")) {
            byte[] cuerpo = json.writeValueAsBytes(new Sobre("1.0",tipo,UUID.randomUUID(),Instant.now(),trace,"15",
                    json.valueToTree(new Correo(15L,"cliente@example.org"))));
            procesador.procesar(cuerpo, Dominio.EMAIL);
        }
        verify(smtp,times(3)).enviar(eq("cliente@example.org"), anyString(),anyString(),isNull());
    }
    @Test void reintentaConEsperasExactasYLuegoFalla() throws Exception {
        doThrow(new MailSendException("sin conexion")).when(smtp).enviar(anyString(),anyString(),anyString(),any());
        assertThrows(MailSendException.class, () -> procesador.procesar(email(),Dominio.EMAIL));
        verify(smtp,times(3)).enviar(anyString(),anyString(),anyString(),any());
        verify(espera).pausar(2000); verify(espera).pausar(5000);
        assertFalse(Files.exists(dir.resolve(id+".ok")));
    }
    @Test void smtp550EsPermanenteSinEsperas() throws Exception {
        var causa = new org.eclipse.angus.mail.smtp.SMTPAddressFailedException(
                new jakarta.mail.internet.InternetAddress("cliente@example.org"),"RCPT TO",550,"rechazado");
        doThrow(new MailSendException("fallo",causa)).when(smtp).enviar(anyString(),anyString(),anyString(),any());
        assertThrows(MailSendException.class, () -> procesador.procesar(email(),Dominio.EMAIL));
        verify(smtp,times(1)).enviar(anyString(),anyString(),anyString(),any());
        verifyNoInteractions(espera);
    }
    @Test void bloqueaSegundaInstanciaSobreMismoVolumen() {
        assertThrows(java.nio.channels.OverlappingFileLockException.class, () -> new RegistroPersistente(dir.toString()));
    }
    @Test void falloAlGuardarExitoNoReenviaCorreoEnMismaEjecucion() throws Exception {
        var almacenamiento = mock(RegistroPersistente.class);
        doThrow(new java.io.IOException("fallo temporal")).doNothing().when(almacenamiento).completar(id);
        var servicio = new Procesador(Validation.buildDefaultValidatorFactory().getValidator(), almacenamiento,
                new TicketPdf(fuente()),smtp,espera);
        servicio.procesar(email(),Dominio.EMAIL);
        verify(smtp,times(1)).enviar(anyString(),anyString(),anyString(),isNull());
        verify(almacenamiento,times(2)).completar(id);
    }
    @Test void pdfPersistenteSeReutilizaDespuesDeFalloYReinicio() throws Exception {
        doThrow(new MailSendException("sin conexion")).when(smtp).enviar(anyString(),anyString(),anyString(),any());
        assertThrows(MailSendException.class, () -> procesador.procesar(despacho(),Dominio.DISPATCH));
        Path pdf = registro.documento(id);
        byte[] original = Files.readAllBytes(pdf);
        var fecha = Files.getLastModifiedTime(pdf);
        try (var documento = Loader.loadPDF(original)) {
            assertEquals(1,documento.getNumberOfPages());
            String texto = new PDFTextStripper().getText(documento);
            assertTrue(texto.contains("Avenida Ñuñoa 123"));
            assertTrue(texto.contains("Revisión eléctrica"));
        }
        registro.cerrar(); registro = new RegistroPersistente(dir.toString());
        reset(smtp); procesador = nuevoProcesador(); procesador.procesar(despacho(),Dominio.DISPATCH);
        assertArrayEquals(original,Files.readAllBytes(pdf));
        assertEquals(fecha,Files.getLastModifiedTime(pdf));
        assertTrue(Files.exists(dir.resolve(id+".ok")));
        verify(smtp).enviar(eq("tecnico@example.org"),anyString(),anyString(),eq(pdf));
    }
    @Test void rechazaJsonInvalidoSinEfectos() {
        assertThrows(ErrorPermanente.class, () -> procesador.procesar("{invalido".getBytes(),Dominio.EMAIL));
        verifyNoInteractions(smtp,espera);
    }
    @Test void rechazaOidComoDestinatario() {
        assertThrows(ErrorPermanente.class, () -> procesador.procesar(
                mensaje("ORDEN_ASIGNADA",new Correo(15L,UUID.randomUUID().toString())),Dominio.EMAIL));
        verifyNoInteractions(smtp,espera);
    }
    @Test void rechazaCorrelacionInconsistente() {
        assertThrows(ErrorPermanente.class, () -> procesador.procesar(
                mensaje("ORDEN_ASIGNADA",new Correo(16L,"cliente@example.org")),Dominio.EMAIL));
        verifyNoInteractions(smtp);
    }
    @Test void rechazaMismoIdConOtroContenido() throws Exception {
        procesador.procesar(email(),Dominio.EMAIL);
        assertThrows(ErrorPermanente.class, () -> procesador.procesar(
                mensaje("ORDEN_CERRADA",new Correo(15L,"cliente@example.org")),Dominio.EMAIL));
        verify(smtp,times(1)).enviar(anyString(),anyString(),anyString(),isNull());
    }
    @Test void ackSoloDespuesDeCompletar() throws Exception {
        var canal = mock(Channel.class); var p = mock(Procesador.class);
        var mensaje = new Message(email(),new MessageProperties()); mensaje.getMessageProperties().setDeliveryTag(7);
        new Consumidores(p).email(mensaje,canal);
        var orden = inOrder(p,canal); orden.verify(p).procesar(any(),eq(Dominio.EMAIL)); orden.verify(canal).basicAck(7,false);
    }
    @Test void falloVaADlqSinAck() throws Exception {
        var canal = mock(Channel.class); var p = mock(Procesador.class);
        doThrow(new ErrorPermanente("invalido")).when(p).procesar(any(),any());
        var mensaje = new Message(new byte[0],new MessageProperties()); mensaje.getMessageProperties().setDeliveryTag(8);
        new Consumidores(p).despacho(mensaje,canal);
        verify(canal).basicNack(8,false,false); verify(canal,never()).basicAck(anyLong(),anyBoolean());
    }
}
