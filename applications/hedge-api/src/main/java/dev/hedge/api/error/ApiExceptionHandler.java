package dev.hedge.api.error;

import dev.hedge.persistence.PersistenceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> invalidInput(IllegalArgumentException failure) {
        LOG.info("rejected a request: {}", failure.getMessage());
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_INPUT", failure.getMessage()));
    }

    @ExceptionHandler(FundNotFoundException.class)
    ResponseEntity<ApiError> notFound(FundNotFoundException failure) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("FUND_NOT_FOUND", failure.getMessage()));
    }

    @ExceptionHandler(PersistenceException.class)
    ResponseEntity<ApiError> databaseUnavailable(PersistenceException failure) {
        LOG.error("the database could not serve a request", failure);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of("DATABASE_UNAVAILABLE", "the service cannot reach its database"));
    }
}