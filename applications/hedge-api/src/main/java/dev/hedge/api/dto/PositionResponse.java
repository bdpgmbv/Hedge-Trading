package dev.hedge.api.dto;

import dev.hedge.domain.Position;

public record PositionResponse(String securityId, String quantity) {

    public static PositionResponse from(Position position) {
        return new PositionResponse(
                position.securityId().value(), position.quantity().value().toPlainString());
    }
}
