package dev.hedge.calculation;

import dev.hedge.domain.exposure.HedgePolicy;
import dev.hedge.domain.exposure.PortfolioExposure;
import dev.hedge.domain.portfolio.Forward;
import dev.hedge.domain.portfolio.Fund;
import java.time.LocalDate;
import java.util.List;

/**
 * One contract, two implementations: the full recalculation that serves as the correctness oracle,
 * and the incremental engine that must produce an identical answer for less work.
 */
public interface ExposureEngine {

    PortfolioExposure calculate(
            Fund fund, List<Forward> forwards, MarketSnapshot market, HedgePolicy policy, LocalDate asOf);
}
