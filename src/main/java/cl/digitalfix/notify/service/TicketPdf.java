package cl.digitalfix.notify.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import cl.digitalfix.notify.dto.Contrato.*;

@Service
public class TicketPdf {
    private final Path fuente;
    public TicketPdf(@Value("${notify.font}") String fuente) { this.fuente = Path.of(fuente); }

    public void generarSiFalta(Path destino, Sobre sobre, Despacho datos) throws IOException {
        if (Files.exists(destino)) return;
        try (var documento = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            var font = PDType0Font.load(documento, fuente.toFile());
            var pagina = new PDPage();
            documento.addPage(pagina);
            try (var contenido = new PDPageContentStream(documento, pagina)) {
                contenido.beginText();
                contenido.setFont(font, 11);
                contenido.setLeading(17);
                contenido.newLineAtOffset(45, 745);
                for (String texto : List.of("DigitalFix - Ticket de despacho", "",
                        "Orden: " + datos.ordenId(), "Tecnico: " + datos.tecnicoId(),
                        "Servicio: " + datos.servicioId() + " - " + datos.servicioNombre(),
                        "Direccion: " + datos.direccion(), "Fecha UTC: " + sobre.timestamp(),
                        "Mensaje: " + sobre.eventId())) {
                    // Limites del contrato garantizan que las lineas caben en una pagina.
                    StringBuilder linea = new StringBuilder();
                    for (int cp : texto.codePoints().toArray()) {
                        String caracter = new String(Character.toChars(cp));
                        if (Character.isISOControl(cp)) caracter = " ";
                        try { font.encode(caracter); }
                        catch (IllegalArgumentException e) { throw new ErrorPermanente("Caracter sin glifo en fuente PDF"); }
                        if (font.getStringWidth(linea + caracter) * 11 / 1000 > 510) {
                            contenido.showText(linea.toString()); contenido.newLine(); linea.setLength(0);
                        }
                        linea.append(caracter);
                    }
                    contenido.showText(linea.toString()); contenido.newLine();
                }
                contenido.endText();
            }
            documento.save(bytes);
            RegistroPersistente.escribirAtomico(destino, bytes.toByteArray());
        }
    }
}
