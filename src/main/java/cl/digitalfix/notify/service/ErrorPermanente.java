package cl.digitalfix.notify.service;

public class ErrorPermanente extends RuntimeException {
    public ErrorPermanente(String motivo) { super(motivo); }
}
