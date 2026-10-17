package cl.digitalfix.notify.messaging;

import java.io.IOException;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import cl.digitalfix.notify.service.Procesador;
import cl.digitalfix.notify.service.Procesador.Dominio;

@Component
public class Consumidores {
    private static final Logger log = LoggerFactory.getLogger(Consumidores.class);
    private final Procesador procesador;
    public Consumidores(Procesador procesador) { this.procesador = procesador; }

    @RabbitListener(queues="${notify.rabbit.email.queue}")
    public void email(Message mensaje, Channel canal) throws IOException { consumir(mensaje, canal, Dominio.EMAIL); }

    @RabbitListener(queues="${notify.rabbit.dispatch.queue}")
    public void despacho(Message mensaje, Channel canal) throws IOException { consumir(mensaje, canal, Dominio.DISPATCH); }

    private void consumir(Message mensaje, Channel canal, Dominio dominio) throws IOException {
        long etiqueta = mensaje.getMessageProperties().getDeliveryTag();
        try { procesador.procesar(mensaje.getBody(), dominio); }
        catch (Exception e) {
            log.warn("envio_dlq dominio={} motivo={}", dominio, e.getClass().getSimpleName());
            canal.basicNack(etiqueta, false, false);
            return;
        }
        // Un fallo del ACK no debe repetir el SMTP dentro de esta entrega.
        canal.basicAck(etiqueta, false);
    }
}
