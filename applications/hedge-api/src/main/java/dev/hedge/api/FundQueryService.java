package dev.hedge.api;

import dev.hedge.api.ApiResponses.FundResponse;
import dev.hedge.api.ApiResponses.PageResponse;
import dev.hedge.api.ApiResponses.PositionResponse;
import dev.hedge.domain.FundId;
import dev.hedge.persistence.FundRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
class FundQueryService {

    private final FundRepository repository;
    private final int maximumPageSize;

    FundQueryService(FundRepository repository, @Value("${hedge.api.max-page-size}") int maximumPageSize) {
        this.repository = repository;
        this.maximumPageSize = maximumPageSize;
    }

    FundResponse fund(String rawFundId) {
        FundId fundId = FundId.of(rawFundId);
        return repository
                .findById(fundId)
                .map(FundResponse::from)
                .orElseThrow(() -> new FundNotFoundException(rawFundId));
    }

    PageResponse<PositionResponse> positions(String rawFundId, int page, int size) {
        FundId fundId = FundId.of(rawFundId);
        requirePageIsValid(page, size);

        long total = repository.countPositions(fundId);
        if (total == 0 && repository.findById(fundId).isEmpty()) {
            throw new FundNotFoundException(rawFundId);
        }
        return new PageResponse<>(
                repository.findPositions(fundId, size, (long) page * size).stream()
                        .map(PositionResponse::from)
                        .toList(),
                page,
                size,
                total);
    }

    private void requirePageIsValid(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative, was " + page);
        }
        if (size < 1) {
            throw new IllegalArgumentException("size must be at least 1, was " + size);
        }
        if (size > maximumPageSize) {
            throw new IllegalArgumentException(
                    "size must not exceed " + maximumPageSize + ", was " + size);
        }
    }
}