package dev.hedge.api.error;

public class FundNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public FundNotFoundException(String fundId) {
        super("no fund with id " + fundId);
    }
}