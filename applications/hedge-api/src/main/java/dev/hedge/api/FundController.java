package dev.hedge.api;

import dev.hedge.api.ApiResponses.FundResponse;
import dev.hedge.api.ApiResponses.PageResponse;
import dev.hedge.api.ApiResponses.PositionResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/funds")
class FundController {

    private final FundQueryService funds;

    FundController(FundQueryService funds) {
        this.funds = funds;
    }

    @GetMapping("/{fundId}")
    FundResponse fund(@PathVariable String fundId) {
        return funds.fund(fundId);
    }

    @GetMapping("/{fundId}/positions")
    PageResponse<PositionResponse> positions(
            @PathVariable String fundId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return funds.positions(fundId, page, size);
    }
}