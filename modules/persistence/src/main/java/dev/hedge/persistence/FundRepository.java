package dev.hedge.persistence;

import dev.hedge.domain.id.AccountId;
import dev.hedge.domain.id.FundId;
import dev.hedge.domain.id.SecurityId;
import dev.hedge.domain.money.Money;
import dev.hedge.domain.money.Quantity;
import dev.hedge.domain.portfolio.Account;
import dev.hedge.domain.portfolio.Fund;
import dev.hedge.domain.portfolio.Position;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
public final class FundRepository {

    private final DataSource dataSource;

    public FundRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    public void save(Fund fund) {
        Objects.requireNonNull(fund, "a fund is required");
        try (Connection connection = dataSource.getConnection()) {
            try {
                writeFund(connection, fund);
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw new PersistenceException("could not save fund " + fund.id(), failure);
            }
        } catch (SQLException failure) {
            throw new PersistenceException("could not obtain a connection", failure);
        }
    }

    private static void writeFund(Connection connection, Fund fund) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("insert into funds (fund_id, base_currency) values (?, ?)")) {
            statement.setString(1, fund.id().value());
            statement.setString(2, fund.baseCurrency().getCurrencyCode());
            statement.executeUpdate();
        }
        for (Account account : fund.accounts()) {
            try (PreparedStatement statement =
                         connection.prepareStatement("insert into accounts (account_id, fund_id) values (?, ?)")) {
                statement.setString(1, account.id().value());
                statement.setString(2, fund.id().value());
                statement.executeUpdate();
            }
            writePositions(connection, account);
            writeCash(connection, account);
        }
    }

    private static void writePositions(Connection connection, Account account) throws SQLException {
        try (PreparedStatement security = connection.prepareStatement(
                "insert into securities (security_id) values (?) on conflict do nothing");
             PreparedStatement position = connection.prepareStatement(
                     "insert into positions (account_id, security_id, quantity) values (?, ?, ?)")) {
            for (Position held : account.positions()) {
                security.setString(1, held.securityId().value());
                security.executeUpdate();

                position.setString(1, held.accountId().value());
                position.setString(2, held.securityId().value());
                position.setBigDecimal(3, held.quantity().value());
                position.addBatch();
            }
            position.executeBatch();
        }
    }

    private static void writeCash(Connection connection, Account account) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into cash_balances (account_id, currency, amount) values (?, ?, ?)")) {
            for (Money cash : account.cashBalances()) {
                statement.setString(1, account.id().value());
                statement.setString(2, cash.currency().getCurrencyCode());
                statement.setBigDecimal(3, cash.amount());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    public Optional<Fund> findById(FundId id) {
        Objects.requireNonNull(id, "a fund id is required");
        try (Connection connection = dataSource.getConnection()) {
            Currency baseCurrency = readBaseCurrency(connection, id);
            if (baseCurrency == null) {
                return Optional.empty();
            }
            List<AccountId> accountIds = readAccountIds(connection, id);
            Map<AccountId, List<Position>> positions = readPositions(connection, id);
            Map<AccountId, List<Money>> cash = readCash(connection, id);

            List<Account> accounts = new ArrayList<>(accountIds.size());
            for (AccountId accountId : accountIds) {
                accounts.add(new Account(
                        accountId,
                        positions.getOrDefault(accountId, List.of()),
                        cash.getOrDefault(accountId, List.of())));
            }
            return Optional.of(new Fund(id, baseCurrency, accounts));
        } catch (SQLException failure) {
            throw new PersistenceException("could not read fund " + id, failure);
        }
    }

    private static Currency readBaseCurrency(Connection connection, FundId id) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("select base_currency from funds where fund_id = ?")) {
            statement.setString(1, id.value());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Currency.getInstance(rows.getString(1).trim()) : null;
            }
        }
    }

    private static List<AccountId> readAccountIds(Connection connection, FundId id) throws SQLException {
        List<AccountId> accounts = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "select account_id from accounts where fund_id = ? order by account_id")) {
            statement.setString(1, id.value());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    accounts.add(AccountId.of(rows.getString(1)));
                }
            }
        }
        return accounts;
    }

    private static Map<AccountId, List<Position>> readPositions(Connection connection, FundId id)
            throws SQLException {
        Map<AccountId, List<Position>> byAccount = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "select p.account_id, p.security_id, p.quantity from positions p"
                        + " join accounts a on a.account_id = p.account_id"
                        + " where a.fund_id = ? order by p.account_id, p.security_id")) {
            statement.setString(1, id.value());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    AccountId accountId = AccountId.of(rows.getString(1));
                    byAccount.computeIfAbsent(accountId, key -> new ArrayList<>())
                            .add(new Position(
                                    accountId,
                                    SecurityId.of(rows.getString(2)),
                                    new Quantity(rows.getBigDecimal(3))));
                }
            }
        }
        return byAccount;
    }

    private static Map<AccountId, List<Money>> readCash(Connection connection, FundId id) throws SQLException {
        Map<AccountId, List<Money>> byAccount = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "select c.account_id, c.currency, c.amount from cash_balances c"
                        + " join accounts a on a.account_id = c.account_id"
                        + " where a.fund_id = ? order by c.account_id, c.currency")) {
            statement.setString(1, id.value());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    AccountId accountId = AccountId.of(rows.getString(1));
                    byAccount.computeIfAbsent(accountId, key -> new ArrayList<>())
                            .add(new Money(rows.getBigDecimal(3), Currency.getInstance(rows.getString(2).trim())));
                }
            }
        }
        return byAccount;
    }

    public long countPositions(FundId id) {
        Objects.requireNonNull(id, "a fund id is required");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select count(*) from positions p"
                             + " join accounts a on a.account_id = p.account_id"
                             + " where a.fund_id = ?")) {
            statement.setString(1, id.value());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        } catch (SQLException failure) {
            throw new PersistenceException("could not count positions for fund " + id, failure);
        }
    }

    public List<Position> findPositions(FundId id, int limit, long offset) {
        Objects.requireNonNull(id, "a fund id is required");
        List<Position> positions = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select p.account_id, p.security_id, p.quantity from positions p"
                             + " join accounts a on a.account_id = p.account_id"
                             + " where a.fund_id = ?"
                             + " order by p.account_id, p.security_id"
                             + " limit ? offset ?")) {
            statement.setString(1, id.value());
            statement.setInt(2, limit);
            statement.setLong(3, offset);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    positions.add(new Position(
                            AccountId.of(rows.getString(1)),
                            SecurityId.of(rows.getString(2)),
                            new Quantity(rows.getBigDecimal(3))));
                }
            }
        } catch (SQLException failure) {
            throw new PersistenceException("could not read positions for fund " + id, failure);
        }
        return positions;
    }
}