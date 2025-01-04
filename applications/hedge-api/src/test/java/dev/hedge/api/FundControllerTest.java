package dev.hedge.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hedge.domain.Fund;
import dev.hedge.generator.PortfolioGenerator;
import dev.hedge.generator.WorkloadSpec;
import dev.hedge.persistence.FundRepository;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FundControllerTest {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private FundRepository repository;

    private final HttpClient client = HttpClient.newHttpClient();
    private Fund fund;

    @BeforeEach
    void seed() {
        fund = new PortfolioGenerator().generate(WorkloadSpec.tiny(2024)).funds().get(0);
        if (repository.findById(fund.id()).isEmpty()) {
            repository.save(fund);
        }
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("a known fund is returned with its accounts and positions")
    void a_known_fund_is_returned() throws Exception {
        HttpResponse<String> response = get("/api/funds/" + fund.id().value());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"fundId\":\"" + fund.id().value() + "\"");
        assertThat(response.body()).contains("\"positionCount\":" + fund.positionCount());
    }

    @Test
    @DisplayName("an unknown fund returns 404 with a readable error code")
    void an_unknown_fund_returns_404() throws Exception {
        HttpResponse<String> response = get("/api/funds/NO-SUCH-FUND");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":\"FUND_NOT_FOUND\"");
    }

    @Test
    @DisplayName("a page larger than the limit is refused before the database is touched")
    void an_oversized_page_is_refused() throws Exception {
        HttpResponse<String> response = get("/api/funds/" + fund.id().value() + "/positions?size=5000");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"INVALID_INPUT\"");
        assertThat(response.body()).contains("must not exceed 200");
    }

    @Test
    @DisplayName("positions are returned one page at a time with the full total")
    void positions_are_paged() throws Exception {
        HttpResponse<String> response = get("/api/funds/" + fund.id().value() + "/positions?page=0&size=2");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"page\":0", "\"size\":2", "\"total\":" + fund.positionCount());
    }

    @Test
    @DisplayName("a blank fund id is refused by the domain type, not by the controller")
    void a_blank_fund_id_is_refused() throws Exception {
        HttpResponse<String> response = get("/api/funds/%20");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"INVALID_INPUT\"");
    }

    @Test
    @DisplayName("the health endpoint reports the database separately from liveness")
    void liveness_does_not_depend_on_the_database() throws Exception {
        assertThat(get("/actuator/health/liveness").body()).doesNotContain("\"db\"");
        assertThat(get("/actuator/health/readiness").body()).contains("\"db\"");
    }
}