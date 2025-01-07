package dev.hedge.ingestor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A separate process from the API on purpose.
 *
 * <p>A file watcher and a request-serving web server fail differently, scale differently and need
 * to restart independently. A stuck batch should not stop anyone querying exposure, and a redeploy
 * of the API should not abandon a half-read custodian file.
 */
@SpringBootApplication
public class HedgeIngestorApplication {

    public static void main(String[] args) {
        SpringApplication.run(HedgeIngestorApplication.class, args);
    }
}
