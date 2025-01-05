package dev.hedge.api.service;

import dev.hedge.api.dto.FundResponse;
import dev.hedge.api.dto.PageResponse;
import dev.hedge.api.dto.PositionResponse;
import dev.hedge.api.error.FundNotFoundException;
import dev.hedge.domain.FundId;
import dev.hedge.persistence.FundRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class FundQueryService {

    private final FundRepository repository;
    private final int maximumPageSize;

    public FundQueryService(FundRepository repository, @Value("${hedge.api.max-page-size}") int maximumPageSize) {
        this.repository = repository;
        this.maximumPageSize = maximumPageSize;
    }

    public FundResponse fund(String rawFundId) {
        FundId fundId = FundId.of(rawFundId);
        return repository
                .findById(fundId)
                .map(FundResponse::from)
                .orElseThrow(() -> new FundNotFoundException(rawFundId));
    }

    public PageResponse<PositionResponse> positions(String rawFundId, int page, int size) {
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