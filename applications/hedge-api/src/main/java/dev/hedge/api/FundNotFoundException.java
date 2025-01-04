package dev.hedge.api;

class FundNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    FundNotFoundException(String fundId) {
        super("no fund with id " + fundId);
    }
}