package dev.hedge.calculation;
public class CalculationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CalculationException(String message) {
        super(message);
    }

    public CalculationException(String message, Throwable cause) {
        super(message, cause);
    }
}