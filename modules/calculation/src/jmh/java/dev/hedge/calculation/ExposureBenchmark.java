package dev.hedge.calculation;

import dev.hedge.domain.Forward;
import dev.hedge.domain.Fund;
import dev.hedge.domain.HedgePolicy;
import dev.hedge.domain.PortfolioExposure;
import dev.hedge.generator.GeneratedMarket;
import dev.hedge.generator.MarketGenerator;
import dev.hedge.generator.PortfolioGenerator;
import dev.hedge.generator.WorkloadSpec;
import java.time.LocalDate;
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
 * The cost of one full calculation, measured before any optimisation exists. Every number Phase 3
 * claims as an improvement is an improvement over this.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, warmups = 1, jvmArgsAppend = {"-Xmx4g"})
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class ExposureBenchmark {

    private static final int POSITIONS_PER_ACCOUNT = 100;
    private static final int SECURITIES = 200;
    private static final long SEED = 20_260_101L;
    private static final LocalDate AS_OF = LocalDate.of(2026, 1, 15);

    @Param({"1000", "10000", "50000", "100000", "500000"})
    public int positions;

    private final ExposureCalculator calculator = new ExposureCalculator();
    private final HedgePolicy policy = HedgePolicy.fullyHedged();

    private Fund fund;
    private List<Forward> forwards;
    private MarketSnapshot market;

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
    }

    @Benchmark
    public PortfolioExposure fullCalculation() {
        return calculator.calculate(fund, forwards, market, policy, AS_OF);
    }
}