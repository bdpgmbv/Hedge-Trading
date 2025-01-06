package dev.hedge.calculation;

import dev.hedge.calculation.full.FullExposureCalculator;
import dev.hedge.calculation.incremental.IncrementalExposureEngine;
import dev.hedge.calculation.incremental.InputChange;
import dev.hedge.calculation.incremental.InputChange.PriceChange;
import dev.hedge.domain.exposure.HedgePolicy;
import dev.hedge.domain.exposure.PortfolioExposure;
import dev.hedge.domain.id.SecurityId;
import dev.hedge.domain.money.Money;
import dev.hedge.domain.portfolio.Forward;
import dev.hedge.domain.portfolio.Fund;
import dev.hedge.generator.GeneratedMarket;
import dev.hedge.generator.MarketGenerator;
import dev.hedge.generator.PortfolioGenerator;
import dev.hedge.generator.WorkloadSpec;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Layer 15. One price tick, measured two ways on identical data: the full recalculation that was
 * the only option before, and the incremental update. Both answers are proven identical by the
 * differential property tests, so this compares cost and nothing else.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, warmups = 1, jvmArgsAppend = {"-Xmx6g"})
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class ExposureBenchmark {

    private static final int POSITIONS_PER_ACCOUNT = 100;
    private static final int SECURITIES = 200;
    private static final int TICKS = 1024; // a power of two, so the cursor can mask instead of divide
    private static final long SEED = 20_260_101L;
    private static final LocalDate AS_OF = LocalDate.of(2026, 1, 15);

    @Param({"1000", "10000", "50000", "100000", "500000"})
    public int positions;

    private final FullExposureCalculator calculator = new FullExposureCalculator();
    private final HedgePolicy policy = HedgePolicy.fullyHedged();

    private Fund fund;
    private List<Forward> forwards;
    private MarketSnapshot market;
    private IncrementalExposureEngine engine;
    private InputChange[] ticks;
    private int cursor;

    @Setup(Level.Trial)
    public void setUp() {
        WorkloadSpec spec = new WorkloadSpec(
                SEED,
                1,
                positions / POSITIONS_PER_ACCOUNT,
                POSITIONS_PER_ACCOUNT,
                SECURITIES,
                List.of("USD", "EUR", "GBP", "JPY"));

        fund = new PortfolioGenerator().generate(spec).funds().get(0);

        GeneratedMarket generated = new MarketGenerator().generate(spec);
        market = MarketSnapshot.builder()
                .securities(generated.securities())
                .prices(generated.prices())
                .rates(generated.rates())
                .build();
        forwards = generated.forwards();

        engine = IncrementalExposureEngine.start(fund, forwards, market, policy, AS_OF);

        // Pre-build the ticks so allocation is not inside the measured path.
        List<SecurityId> held = new ArrayList<>();
        fund.accounts().forEach(account -> account.positions().forEach(p -> held.add(p.securityId())));
        ticks = new InputChange[TICKS];
        for (int index = 0; index < TICKS; index++) {
            SecurityId security = held.get(index % held.size());
            Money quoted = market.price(security);
            ticks[index] = new PriceChange(
                    security,
                    new Money(quoted.amount().add(BigDecimal.valueOf((index % 9) + 1)), quoted.currency()));
        }
    }

    /** What the legacy engine does on every tick: revalue the entire fund. */
    @Benchmark
    public PortfolioExposure fullRecalculation() {
        return calculator.calculate(fund, forwards, market, policy, AS_OF);
    }

    /** What the incremental engine does: recompute only the affected closure. */
    @Benchmark
    public PortfolioExposure incrementalTick() {
        return engine.apply(ticks[cursor++ & (TICKS - 1)]);
    }
}
