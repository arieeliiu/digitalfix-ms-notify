package cl.digitalfix.notify.service;

import org.springframework.stereotype.Component;

@Component
public class Espera {
    public void pausar(long milisegundos) throws InterruptedException { Thread.sleep(milisegundos); }
}
