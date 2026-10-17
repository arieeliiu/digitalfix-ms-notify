package cl.digitalfix.notify.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {
    @Bean
    Declarables recursosNotify(Topologia t) {
        var direct = new DirectExchange(t.direct(), true, false);
        var topic = new TopicExchange(t.topic(), true, false);
        var dlx = new DirectExchange(t.dlx(), true, false);
        List<Declarable> recursos = new ArrayList<>(List.of(direct, topic, dlx));
        for (var ruta : List.of(t.email(), t.dispatch())) {
            var cola = QueueBuilder.durable(ruta.queue()).withArgument("x-queue-type", "classic")
                    .deadLetterExchange(t.dlx()).deadLetterRoutingKey(ruta.deadKey()).build();
            var dlq = QueueBuilder.durable(ruta.dlq()).withArgument("x-queue-type", "classic").build();
            recursos.addAll(List.of(cola, dlq,
                    BindingBuilder.bind(cola).to(direct).with(ruta.directKey()),
                    BindingBuilder.bind(cola).to(topic).with(ruta.topicKey()),
                    BindingBuilder.bind(dlq).to(dlx).with(ruta.deadKey())));
        }
        return new Declarables(recursos);
    }
}
