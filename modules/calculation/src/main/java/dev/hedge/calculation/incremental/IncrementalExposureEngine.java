package dev.hedge.calculation.incremental;

import dev.hedge.calculation.CalculationException;
import dev.hedge.calculation.MarketSnapshot;
import dev.hedge.calculation.incremental.CalculationNode.CashInput;
import dev.hedge.calculation.incremental.CalculationNode.CurrencyTotal;
import dev.hedge.calculation.incremental.CalculationNode.Derived;
import dev.hedge.calculation.incremental.CalculationNode.HedgeTarget;
import dev.hedge.calculation.incremental.CalculationNode.PortfolioTotal;
import dev.hedge.calculation.incremental.CalculationNode.PositionValue;
import dev.hedge.calculation.incremental.InputChange.CashChange;
import dev.hedge.calculation.incremental.InputChange.PriceChange;
import dev.hedge.calculation.incremental.InputChange.QuantityChange;
import dev.hedge.domain.exposure.CurrencyExposure;
import dev.hedge.domain.exposure.HedgePolicy;
import dev.hedge.domain.exposure.HedgeRequirement;
import dev.hedge.domain.exposure.PortfolioExposure;
import dev.hedge.domain.id.FundId;
import dev.hedge.domain.id.SecurityId;
import dev.hedge.domain.money.Money;
import dev.hedge.domain.money.Quantity;
import dev.hedge.domain.portfolio.Account;
import dev.hedge.domain.portfolio.ExposureWeight;
import dev.hedge.domain.portfolio.Forward;
import dev.hedge.domain.portfolio.Fund;
import dev.hedge.domain.portfolio.Position;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Keeps a fund's exposure up to date by recomputing only what a change reaches.
 *
 * <p>Unlike {@code FullExposureCalculator} this engine is <strong>stateful</strong>, and
 * deliberately does not implement {@code ExposureEngine}: incremental computation needs a previous
 * answer to update, so a stateless interface would be a lie about its cost. Phase 4 makes that
 * state versioned and safely publishable; until then one engine belongs to one thread.
 *
 * <p>Aggregate nodes are maintained by delta, not by re-summing. A position whose value moves from
 * {@code v0} to {@code v1} pushes {@code v1 - v0} into the currency total and the portfolio total,
 * so those nodes cost O(1) to update however many positions feed them. Exactness depends on
 * subtracting the same already-rounded contribution that was previously added, which is why each
 * position's rounded per-currency contribution is stored rather than recomputed.
 */
public final class IncrementalExposureEngine {

    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final FundId fundId;
    private final Currency base;
    private final HedgePolicy policy;
    private final MarketSnapshot market;
    private final DependencyGraph graph;
    private final ReverseDependencyIndex index;

    // Inputs, mutable.
    private final Map<SecurityId, Money> prices = new HashMap<>();
    private final Map<PositionValue, Quantity> quantities = new LinkedHashMap<>();
    private final Map<CashInput, Money> cashAmounts = new LinkedHashMap<>();
    private final Map<CashInput, Money> cashInBase = new LinkedHashMap<>();

    // Derived state, kept between changes.
    private final Map<PositionValue, PositionState> positions = new LinkedHashMap<>();
    private final Map<String, Money> securityTotals = new LinkedHashMap<>();
    private final Map<String, Money> cashTotals = new LinkedHashMap<>();
    private final Map<String, Money> forwardTotals = new LinkedHashMap<>();
    private final Map<String, HedgeRequirement> hedges = new LinkedHashMap<>();
    private Money portfolioTotal;

    // Deltas in flight during one apply().
    private final Map<String, Money> pendingSecurities = new LinkedHashMap<>();
    private final Map<String, Money> pendingCash = new LinkedHashMap<>();
    private Money pendingBase;

    /** A position's value plus the already-rounded contributions derived from it. */
    private record PositionState(Money value, Map<String, Money> perCurrency, Money inBase) {}

    private IncrementalExposureEngine(
            FundId fundId,
            Currency base,
            HedgePolicy policy,
            MarketSnapshot market,
            DependencyGraph graph,
            ReverseDependencyIndex index) {
        this.fundId = fundId;
        this.base = base;
        this.policy = policy;
        this.market = market;
        this.graph = graph;
        this.index = index;
        this.portfolioTotal = Money.zero(base.getCurrencyCode());
        this.pendingBase = Money.zero(base.getCurrencyCode());
    }

    /**
     * Builds the graph and evaluates every node once. This cold pass costs the same as a full
     * recalculation; the saving appears on every change after it.
     */
    public static IncrementalExposureEngine start(
            Fund fund,
            List<Forward> forwards,
            MarketSnapshot market,
            HedgePolicy policy,
            LocalDate asOf) {
        Objects.requireNonNull(fund, "a fund is required");
        Objects.requireNonNull(forwards, "forwards must not be null");
        Objects.requireNonNull(market, "a market snapshot is required");
        Objects.requireNonNull(policy, "a hedge policy is required");
        Objects.requireNonNull(asOf, "a valuation date is required");

        DependencyGraph graph = DependencyGraph.of(fund, forwards, market, asOf);
        IncrementalExposureEngine engine = new IncrementalExposureEngine(
                fund.id(),
                fund.baseCurrency(),
                policy,
                market,
                graph,
                ReverseDependencyIndex.from(graph));
        engine.seed(fund, forwards, asOf);
        return engine;
    }

    private void seed(Fund fund, List<Forward> forwards, LocalDate asOf) {
        for (Account account : fund.accounts()) {
            for (Position position : account.positions()) {
                PositionValue node = new PositionValue(account.id(), position.securityId());
                prices.put(position.securityId(), market.price(position.securityId()));
                quantities.put(node, position.quantity());

                Money value = valueOf(node);
                Map<String, Money> perCurrency = contributionsOf(position.securityId(), value);
                Money inBase = market.convert(value, base);
                positions.put(node, new PositionState(value, perCurrency, inBase));

                perCurrency.forEach((code, amount) -> securityTotals.merge(code, amount, Money::plus));
                portfolioTotal = portfolioTotal.plus(inBase);
            }

            for (Money balance : account.cashBalances()) {
                CashInput node = new CashInput(account.id(), balance.currency());
                cashAmounts.put(node, balance);
                Money inBase = market.convert(balance, base);
                cashInBase.put(node, inBase);
                cashTotals.merge(balance.currency().getCurrencyCode(), balance, Money::plus);
                portfolioTotal = portfolioTotal.plus(inBase);
            }
        }

        for (Forward forward : forwards) {
            if (!forward.isLiveOn(asOf)) {
                continue;
            }
            forwardTotals.merge(
                    forward.sell().currency().getCurrencyCode(), negated(forward.sell()), Money::plus);
            forwardTotals.merge(
                    forward.buy().currency().getCurrencyCode(), forward.buy(), Money::plus);
        }

        for (String code : currencyCodes()) {
            if (!Currency.getInstance(code).equals(base)) {
                hedges.put(code, HedgeRequirement.from(exposureIn(code), policy));
            }
        }
    }

    /**
     * Applies one change and returns the updated answer. The work done is proportional to the
     * affected closure, not to the size of the fund.
     */
    public PortfolioExposure apply(InputChange change) {
        Objects.requireNonNull(change, "a change is required");
        pendingSecurities.clear();
        pendingCash.clear();
        pendingBase = Money.zero(base.getCurrencyCode());

        CalculationNode seed = change.node();
        switch (change) {
            case PriceChange price -> {
                if (!graph.nodes().contains(seed)) {
                    return report(); // a price for something this fund does not hold changes nothing
                }
                Money quoted = prices.get(price.security());
                if (!quoted.currency().equals(price.price().currency())) {
                    throw new CalculationException(price.security().value() + " is quoted in "
                            + quoted.currency().getCurrencyCode() + " but was repriced in "
                            + price.price().currency().getCurrencyCode()
                            + "; the currency a security is priced in decides which fx edges the graph"
                            + " has, so re-quoting it needs a rebuild rather than an incremental update");
                }
                prices.put(price.security(), price.price());
            }
            case QuantityChange quantity -> {
                PositionValue node = new PositionValue(quantity.account(), quantity.security());
                if (!quantities.containsKey(node)) {
                    throw new CalculationException("this fund has no position " + node.key()
                            + "; adding or removing a position changes the graph itself,"
                            + " which needs a rebuild rather than an incremental update");
                }
                quantities.put(node, quantity.quantity());
            }
            case CashChange cash -> applyCashChange(cash);
        }

        for (CalculationNode node : AffectedClosure.of(Set.of(seed), graph, index).nodes()) {
            recompute(node);
        }
        return report();
    }

    private void applyCashChange(CashChange change) {
        CashInput node = new CashInput(change.account(), change.amount().currency());
        Money previous = cashAmounts.get(node);
        if (previous == null) {
            throw new CalculationException("this fund has no "
                    + change.amount().currency().getCurrencyCode() + " cash balance in account "
                    + change.account().value());
        }
        cashAmounts.put(node, change.amount());
        pendingCash.merge(
                change.amount().currency().getCurrencyCode(),
                change.amount().minus(previous),
                Money::plus);

        Money inBase = market.convert(change.amount(), base);
        pendingBase = pendingBase.plus(inBase.minus(cashInBase.get(node)));
        cashInBase.put(node, inBase);
    }

    /**
     * The exhaustive switch is the point of making {@link CalculationNode} sealed: adding a node
     * type without handling it here is a compile error, not a silently wrong number.
     */
    private void recompute(CalculationNode node) {
        if (!(node instanceof Derived derived)) {
            throw new CalculationException(
                    "only derived nodes are recomputed, but the closure contained " + node.key());
        }
        switch (derived) {
            case PositionValue position -> recomputePosition(position);
            case CurrencyTotal total -> absorbPending(total.currency());
            case HedgeTarget target -> {
                String code = target.currency().getCurrencyCode();
                hedges.put(code, HedgeRequirement.from(exposureIn(code), policy));
            }
            case PortfolioTotal ignored -> {
                portfolioTotal = portfolioTotal.plus(pendingBase);
                pendingBase = Money.zero(base.getCurrencyCode());
            }
        }
    }

    private void recomputePosition(PositionValue node) {
        PositionState previous = positions.get(node);
        Money value = valueOf(node);
        Map<String, Money> perCurrency = contributionsOf(node.security(), value);
        Money inBase = market.convert(value, base);

        Set<String> touched = new LinkedHashSet<>(previous.perCurrency().keySet());
        touched.addAll(perCurrency.keySet());
        for (String code : touched) {
            Money before = previous.perCurrency().getOrDefault(code, Money.zero(code));
            Money after = perCurrency.getOrDefault(code, Money.zero(code));
            pendingSecurities.merge(code, after.minus(before), Money::plus);
        }
        pendingBase = pendingBase.plus(inBase.minus(previous.inBase()));

        positions.put(node, new PositionState(value, perCurrency, inBase));
    }

    /**
     * A delta must be applied exactly once, so it is removed as it is consumed. Double-applying one
     * would corrupt the running total permanently rather than for a single calculation.
     */
    private void absorbPending(Currency currency) {
        String code = currency.getCurrencyCode();
        Money securities = pendingSecurities.remove(code);
        if (securities != null) {
            securityTotals.merge(code, securities, Money::plus);
        }
        Money cash = pendingCash.remove(code);
        if (cash != null) {
            cashTotals.merge(code, cash, Money::plus);
        }
    }

    /** The current answer, assembled from running state. Costs O(currencies), not O(positions). */
    public PortfolioExposure report() {
        List<CurrencyExposure> exposures = new ArrayList<>();
        List<HedgeRequirement> requirements = new ArrayList<>();
        for (String code : currencyCodes()) {
            exposures.add(exposureIn(code));
            HedgeRequirement requirement = hedges.get(code);
            if (requirement != null) {
                requirements.add(requirement);
            }
        }
        return new PortfolioExposure(fundId, base, portfolioTotal, exposures, requirements);
    }

    /** How many nodes a change would touch, for measurement and for monitoring. */
    public int closureSizeFor(InputChange change) {
        Objects.requireNonNull(change, "a change is required");
        return AffectedClosure.of(Set.of(change.node()), graph, index).size();
    }

    public int nodeCount() {
        return graph.nodeCount();
    }

    private Set<String> currencyCodes() {
        Set<String> codes = new TreeSet<>();
        codes.addAll(securityTotals.keySet());
        codes.addAll(cashTotals.keySet());
        codes.addAll(forwardTotals.keySet());
        return codes;
    }

    private CurrencyExposure exposureIn(String code) {
        return CurrencyExposure.of(
                securityTotals.getOrDefault(code, Money.zero(code)),
                cashTotals.getOrDefault(code, Money.zero(code)),
                forwardTotals.getOrDefault(code, Money.zero(code)));
    }

    private Money valueOf(PositionValue node) {
        return prices.get(node.security()).multipliedBy(quantities.get(node).value(), ROUNDING);
    }

    private Map<String, Money> contributionsOf(SecurityId security, Money value) {
        Map<String, Money> contributions = new LinkedHashMap<>();
        for (ExposureWeight weight : market.security(security).effectiveExposures()) {
            contributions.merge(
                    weight.currency().getCurrencyCode(),
                    market.convert(value, weight.currency()).multipliedBy(weight.weight(), ROUNDING),
                    Money::plus);
        }
        return contributions;
    }

    private static Money negated(Money amount) {
        return Money.zero(amount.currency().getCurrencyCode()).minus(amount);
    }
}
