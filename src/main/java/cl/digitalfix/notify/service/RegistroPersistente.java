package cl.digitalfix.notify.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.UUID;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import static java.nio.file.StandardOpenOption.*;

@Component
public class RegistroPersistente {
    private final Path directorio;
    private final FileChannel canal;
    private final FileLock bloqueo;

    public RegistroPersistente(@Value("${notify.storage}") String ruta) throws IOException {
        directorio = Path.of(ruta).toAbsolutePath();
        Files.createDirectories(directorio);
        canal = FileChannel.open(directorio.resolve("notify.lock"), CREATE, WRITE);
        try { bloqueo = canal.tryLock(); }
        catch (IOException | RuntimeException e) { canal.close(); throw e; }
        if (bloqueo == null) { canal.close(); throw new IOException("El volumen ya esta en uso"); }
    }

    public Path documento(UUID id) { return directorio.resolve(id + ".pdf"); }

    public boolean completado(UUID id, String huella) throws IOException {
        comprobarIdentidad(id, huella);
        return Files.exists(directorio.resolve(id + ".ok"));
    }

    private void comprobarIdentidad(UUID id, String huella) throws IOException {
        Path archivo = directorio.resolve(id + ".identidad");
        if (Files.exists(archivo)) {
            if (!Files.readString(archivo).equals(huella))
                throw new ErrorPermanente("eventId reutilizado con contenido diferente");
        } else escribirAtomico(archivo, huella.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public void completar(UUID id) throws IOException {
        escribirAtomico(directorio.resolve(id + ".ok"), new byte[]{1});
    }

    public static void escribirAtomico(Path destino, byte[] contenido) throws IOException {
        Path temporal = Files.createTempFile(destino.getParent(), ".notify-", ".tmp");
        try {
            try (var salida = FileChannel.open(temporal, WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(contenido);
                while (buffer.hasRemaining()) salida.write(buffer);
                salida.force(true);
            }
            Files.move(temporal, destino, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporal); }
    }

    @PreDestroy
    public void cerrar() throws IOException { bloqueo.release(); canal.close(); }
}
