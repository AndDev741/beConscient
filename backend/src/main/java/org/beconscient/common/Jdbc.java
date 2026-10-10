package org.beconscient.common;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Plain JDBC for the high-volume tables (raw events, segments, blocks). Inside a
 * JTA transaction Agroal hands out the transaction's connection, so these
 * statements commit or roll back together with any Panache work around them.
 */
@ApplicationScoped
public class Jdbc {

    @Inject
    AgroalDataSource ds;

    @FunctionalInterface
    public interface Rows<T> {
        T map(ResultSet rs) throws SQLException;
    }

    @FunctionalInterface
    public interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    /** A bigint[] parameter. */
    public record LongArray(long[] values) {
    }

    /** A text[] parameter. */
    public record TextArray(List<String> values) {
    }

    public <T> T work(Work<T> work) {
        try (Connection c = ds.getConnection()) {
            return work.run(c);
        } catch (SQLException e) {
            throw new DataAccessException(e);
        }
    }

    public <T> List<T> list(String sql, Rows<T> mapper, Object... params) {
        return work(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(c, ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    List<T> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(mapper.map(rs));
                    }
                    return out;
                }
            }
        });
    }

    public <T> Optional<T> one(String sql, Rows<T> mapper, Object... params) {
        List<T> rows = list(sql, mapper, params);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    public long count(String sql, Object... params) {
        return one(sql, rs -> rs.getLong(1), params).orElse(0L);
    }

    public int update(String sql, Object... params) {
        return work(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(c, ps, params);
                return ps.executeUpdate();
            }
        });
    }

    /** Runs the same statement for each parameter row as one JDBC batch. */
    public void batch(String sql, List<Object[]> rows) {
        if (rows.isEmpty()) {
            return;
        }
        work(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (Object[] row : rows) {
                    bind(c, ps, row);
                    ps.addBatch();
                }
                ps.executeBatch();
                return null;
            }
        });
    }

    static void bind(Connection c, PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            int idx = i + 1;
            switch (p) {
                case null -> ps.setNull(idx, Types.NULL);
                case UUID u -> ps.setObject(idx, u);
                case Instant t -> ps.setTimestamp(idx, Timestamp.from(t));
                case LocalDate d -> ps.setObject(idx, d);
                case byte[] b -> ps.setBytes(idx, b);
                case LongArray a -> {
                    Long[] boxed = new Long[a.values().length];
                    for (int j = 0; j < boxed.length; j++) {
                        boxed[j] = a.values()[j];
                    }
                    Array arr = c.createArrayOf("bigint", boxed);
                    ps.setArray(idx, arr);
                }
                case TextArray a -> ps.setArray(idx, c.createArrayOf("text", a.values().toArray()));
                case UUID[] u -> ps.setArray(idx, c.createArrayOf("uuid", u));
                default -> ps.setObject(idx, p);
            }
        }
    }

    public static UUID uuid(ResultSet rs, String col) throws SQLException {
        return rs.getObject(col, UUID.class);
    }

    public static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    public static Integer nullableInt(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    public static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    public static LocalDate date(ResultSet rs, String col) throws SQLException {
        return rs.getObject(col, LocalDate.class);
    }

    public static long[] longs(ResultSet rs, String col) throws SQLException {
        Array a = rs.getArray(col);
        if (a == null) {
            return new long[0];
        }
        Object[] values = (Object[]) a.getArray();
        long[] out = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = ((Number) values[i]).longValue();
        }
        return out;
    }

    public static List<String> texts(ResultSet rs, String col) throws SQLException {
        Array a = rs.getArray(col);
        if (a == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object o : (Object[]) a.getArray()) {
            out.add((String) o);
        }
        return out;
    }

    public static class DataAccessException extends RuntimeException {
        public DataAccessException(SQLException cause) {
            super(cause);
        }
    }
}
