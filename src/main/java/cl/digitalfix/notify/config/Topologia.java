package cl.digitalfix.notify.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("notify.rabbit")
public record Topologia(String direct, String topic, String dlx, Ruta email, Ruta dispatch) {
    public record Ruta(String queue, String dlq, String directKey, String topicKey, String deadKey) {}
}
