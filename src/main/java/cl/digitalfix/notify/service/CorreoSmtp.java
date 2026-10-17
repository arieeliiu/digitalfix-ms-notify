package cl.digitalfix.notify.service;

import java.nio.file.Path;
import jakarta.mail.MessagingException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class CorreoSmtp {
    private final JavaMailSender sender;
    private final String remitente;
    public CorreoSmtp(JavaMailSender sender, @Value("${notify.from}") String remitente) {
        this.sender = sender; this.remitente = remitente;
    }
    public void enviar(String destinatario, String asunto, String texto, Path adjunto) throws MessagingException {
        var mensaje = sender.createMimeMessage();
        var helper = new MimeMessageHelper(mensaje, adjunto != null, "UTF-8");
        helper.setFrom(remitente); helper.setTo(destinatario);
        helper.setSubject(asunto); helper.setText(texto, false);
        if (adjunto != null) helper.addAttachment("ticket-despacho.pdf", adjunto.toFile());
        sender.send(mensaje);
    }
}
