package dev.hedge.generator;

import dev.hedge.domain.Fund;
import java.util.List;
import org.junit.jupiter.api.Test;

class GenProbe {
    @Test
    void run() {
        PortfolioGenerator g = new PortfolioGenerator();
//        System.out.println("PROBE hello");
//
//
//        System.out.println("PROBE same seed   : "
//                + g.generate(WorkloadSpec.tiny(42)).equals(g.generate(WorkloadSpec.tiny(42))));
//        System.out.println("PROBE other seed  : "
//                + g.generate(WorkloadSpec.tiny(42)).equals(g.generate(WorkloadSpec.tiny(43))));
//
//
//        WorkloadSpec one = new WorkloadSpec(42, 1, 2, 3, 5, List.of("USD", "EUR"));
//        WorkloadSpec many = new WorkloadSpec(42, 10, 2, 3, 5, List.of("USD", "EUR"));
//        Fund a = g.generate(one).funds().get(0);
//        Fund b = g.generate(many).funds().get(0);
//        System.out.println("PROBE fund0 same? : " + a.equals(b));


//        WorkloadSpec jpy = new WorkloadSpec(5, 1, 1, 1, 2, List.of("JPY", "USD"));
//        System.out.println("PROBE " + g.generate(jpy).prices());

        WorkloadSpec big = new WorkloadSpec(1, 200, 5, 50, 500, List.of("USD", "EUR", "GBP", "JPY"));
        long start = System.nanoTime();
        GeneratedPortfolio p = g.generate(big);
        long ms = (System.nanoTime() - start) / 1_000_000;
        System.out.println("PROBE " + p.positionCount() + " positions in " + ms + " ms");


        GeneratedPortfolio portfolio = g.generate(WorkloadSpec.typical(2024));

        System.out.println("PROBE funds     : " + portfolio.funds().size());
        System.out.println("PROBE positions : " + portfolio.positionCount());
        System.out.println("PROBE securities: " + portfolio.prices().size());
        System.out.println("PROBE rates     : " + portfolio.rates());

        Fund first = portfolio.funds().get(0);
        System.out.println("PROBE fund      : " + first.id() + " base=" + first.baseCurrency());
        System.out.println("PROBE accounts  : " + first.accounts().size());
        System.out.println("PROBE 1st pos   : " + first.accounts().get(0).positions().get(0));
    }
}