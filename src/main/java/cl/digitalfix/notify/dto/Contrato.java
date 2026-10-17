package cl.digitalfix.notify.dto;

import java.time.Instant;
import java.util.UUID;
import jakarta.validation.constraints.*;
import tools.jackson.databind.JsonNode;

public final class Contrato {
    private Contrato() {}
    public record Sobre(@NotBlank @Pattern(regexp="1\\.0") String schemaVersion,
                        @NotBlank String type, @NotNull UUID eventId,
                        @NotNull Instant timestamp, @NotNull UUID traceId,
                        @NotBlank @Pattern(regexp="[1-9][0-9]*") String correlationId,
                        @NotNull JsonNode payload) {}

    public record Correo(@NotNull @Positive Long ordenId,
                         @NotBlank @Email @Size(max=254) String emailContacto) {}

    public record Despacho(@NotNull @Positive Long ordenId,
                           @NotBlank @Email @Size(max=254) String emailTecnico,
                           @NotBlank @Size(max=100) String tecnicoId,
                           @NotNull @Positive Long servicioId,
                           @NotBlank @Size(max=200) String servicioNombre,
                           @NotBlank @Size(max=300) String direccion) {}
}
