package dev.hedge.persistence;

import dev.hedge.domain.Fund;
import dev.hedge.generator.PortfolioGenerator;
import dev.hedge.generator.WorkloadSpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class DbProbe {

    @Test
    void run() {
        DataSource ds = TestDatabase.start(5);
        TestDatabase.clear(ds);
        FundRepository repository = new FundRepository(ds);

        Fund fund = new PortfolioGenerator().generate(WorkloadSpec.tiny(42)).funds().get(0);

        long start = System.nanoTime();
        repository.save(fund);
        long saveMs = (System.nanoTime() - start) / 1_000_000;

        start = System.nanoTime();
        Fund loaded = repository.findById(fund.id()).orElseThrow();
        long loadMs = (System.nanoTime() - start) / 1_000_000;

        System.out.println("PROBE saved    : " + fund.id() + " positions=" + fund.positionCount() + " in " + saveMs + " ms");
        System.out.println("PROBE loaded   : " + loaded.id() + " positions=" + loaded.positionCount() + " in " + loadMs + " ms");
        System.out.println("PROBE identical: " + fund.equals(loaded));
        DataSources.close(ds);
    }
}