package dev.hedge.calculation.incremental;

import dev.hedge.calculation.MarketSnapshot;
import dev.hedge.calculation.full.FullExposureCalculator;
import dev.hedge.calculation.incremental.InputChange.PriceChange;
import dev.hedge.domain.exposure.HedgePolicy;
import dev.hedge.domain.id.SecurityId;
import dev.hedge.domain.money.Money;
import dev.hedge.domain.portfolio.Forward;
import dev.hedge.domain.portfolio.Fund;
import dev.hedge.generator.GeneratedMarket;
import dev.hedge.generator.MarketGenerator;
import dev.hedge.generator.PortfolioGenerator;
import dev.hedge.generator.WorkloadSpec;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** One price tick: full recalculation versus incremental update. */
class SpeedupProbe {

    private static final java.time.LocalDate AS_OF = java.time.LocalDate.of(2026, 1, 15);
    private static final HedgePolicy POLICY = HedgePolicy.fullyHedged();
    private static final int TICKS = 2_000;

    @Test
    void run() {
        measure("1k pos ", new WorkloadSpec(42, 1, 20, 50, 200, List.of("USD", "EUR", "GBP", "JPY")));
        measure("10k pos", new WorkloadSpec(42, 1, 100, 100, 200, List.of("USD", "EUR", "GBP", "JPY")));
    }

    private static void measure(String label, WorkloadSpec spec) {
        Fund fund = new PortfolioGenerator().generate(spec).funds().get(0);
        GeneratedMarket generated = new MarketGenerator().generate(spec);
        MarketSnapshot market = MarketSnapshot.builder()
                .securities(generated.securities())
                .prices(generated.prices())
                .rates(generated.rates())
                .build();
        List<Forward> forwards = generated.forwards();

        // The securities this fund actually holds, so every tick lands on something.
        List<SecurityId> held = new ArrayList<>();
        fund.accounts().forEach(a -> a.positions().forEach(p -> held.add(p.securityId())));

        FullExposureCalculator full = new FullExposureCalculator();
        IncrementalExposureEngine engine =
                IncrementalExposureEngine.start(fund, forwards, market, POLICY, AS_OF);

        // Warm up both paths so the JIT is not part of the measurement.
        for (int i = 0; i < 200; i++) {
            full.calculate(fund, forwards, market, POLICY, AS_OF);
            engine.apply(tick(market, held, i));
        }

        long fullStart = System.nanoTime();
        for (int i = 0; i < TICKS; i++) {
            full.calculate(fund, forwards, market, POLICY, AS_OF);
        }
        double fullUs = (System.nanoTime() - fullStart) / 1_000.0 / TICKS;

        long incStart = System.nanoTime();
        for (int i = 0; i < TICKS; i++) {
            engine.apply(tick(market, held, i));
        }
        double incUs = (System.nanoTime() - incStart) / 1_000.0 / TICKS;

        System.out.printf(
                "PROBE %s positions=%-6d  full=%8.1f us/tick   incremental=%7.2f us/tick   speedup=%6.1fx%n",
                label, fund.positionCount(), fullUs, incUs, fullUs / incUs);
    }

    /** A deterministic price nudge on one held security. */
    private static PriceChange tick(MarketSnapshot market, List<SecurityId> held, int i) {
        SecurityId security = held.get(i % held.size());
        Money base = market.price(security);
        BigDecimal nudge = BigDecimal.valueOf((i % 7) + 1);
        return new PriceChange(security, new Money(base.amount().add(nudge), base.currency()));
    }
}
