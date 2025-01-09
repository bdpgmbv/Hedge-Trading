package dev.hedge.ingestion.legacy;

import dev.hedge.ingestion.source.IngestionException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * How the old system ingested positions: parse the file, replace the table, forget everything else.
 *
 * <p>Kept, built and tested rather than described, because the comparison is the whole argument
 * for the new pipeline and an argument against a strawman proves nothing. This is not deliberately
 * crippled code — it is a perfectly reasonable implementation of the wrong design, which is what
 * makes it a fair baseline.
 *
 * <p>What it cannot do, by construction:
 *
 * <ul>
 *   <li>say where a number came from — nothing records the provider
 *   <li>answer what was known yesterday — the previous value was overwritten
 *   <li>refuse a file it has already seen — nothing remembers the bytes
 *   <li>report a row it could not understand — unparseable lines are skipped
 * </ul>
 */
public final class LegacyOverwriteIngester {

    private final DataSource dataSource;

    public LegacyOverwriteIngester(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    /**
     * Replaces the position table with whatever this file contains.
     *
     * @return how many rows were written, the only feedback the old system gave
     */
    public int ingest(String csv) {
        Objects.requireNonNull(csv, "a file is required");
        Map<String, String> positions = new LinkedHashMap<>();

        for (String line : csv.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("HEADER") || trimmed.startsWith("TRAILER")) {
                continue;
            }
            String[] fields = trimmed.split(",");
            if (fields.length < 4 || !"POSITION".equals(fields[0])) {
                continue; // anything unrecognised simply vanishes, and nobody is told
            }
            positions.put(fields[1] + "\u0000" + fields[2], fields[3]);
        }

        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement delete = connection.prepareStatement("delete from legacy_positions")) {
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into legacy_positions (account_code, security_code, quantity)"
                            + " values (?, ?, cast(? as numeric))")) {
                for (Map.Entry<String, String> entry : positions.entrySet()) {
                    String[] key = entry.getKey().split("\u0000");
                    insert.setString(1, key[0]);
                    insert.setString(2, key[1]);
                    insert.setString(3, entry.getValue());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            connection.commit();
            return positions.size();
        } catch (SQLException failure) {
            throw new IngestionException("the legacy ingest failed", failure);
        }
    }

    /** The only question the old schema can answer: what is the quantity now. */
    public java.util.Optional<String> quantityOf(String account, String security) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "select quantity from legacy_positions"
                                + " where account_code = ? and security_code = ?")) {
            statement.setString(1, account);
            statement.setString(2, security);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next()
                        ? java.util.Optional.of(rows.getBigDecimal(1).stripTrailingZeros().toPlainString())
                        : java.util.Optional.empty();
            }
        } catch (SQLException failure) {
            throw new IngestionException("could not read the legacy position", failure);
        }
    }

    public int rowCount() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("select count(*) from legacy_positions");
                ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getInt(1) : 0;
        } catch (SQLException failure) {
            throw new IngestionException("could not count legacy positions", failure);
        }
    }
}
