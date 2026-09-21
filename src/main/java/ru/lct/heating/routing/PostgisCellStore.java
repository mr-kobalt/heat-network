package ru.lct.heating.routing;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Состояние клеток сетки с вытеснением страниц в PostGIS TEMP (ADR-0033,
 * фаза B). Соединение закрепляется на прогон, чтобы TEMP-таблица жила в сессии;
 * страницы 256×256 клеток кэшируются в ОЗУ (LRU) и сбрасываются в БД.
 *
 * <p>TEMP-таблица привязана к сессии соединения из пула, поэтому при создании
 * стора таблица очищается ({@code TRUNCATE}) — каждый проход/прогон стартует
 * с пустого состояния и не видит страницы предыдущего.</p>
 */
public final class PostgisCellStore implements CellStore {

    private static final int BYTES_PER_CELL = 12;

    private final Connection connection;
    private final int pageCells;
    private final long maxCacheBytes;
    private final Map<Long, ByteBuffer> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final Set<Long> dirty = new HashSet<>();
    private final long[] settledBits;
    private final long[] forestBits;
    private long cacheBytes;
    private PreparedStatement select;
    private PreparedStatement upsert;

    public PostgisCellStore(Connection connection, int pageSide, int cacheMb, int cells)
            throws SQLException {
        this.connection = connection;
        this.pageCells = Math.max(1, pageSide) * Math.max(1, pageSide);
        this.maxCacheBytes = Math.max(1L, cacheMb) * 1024L * 1024L;
        this.settledBits = new long[(cells + 63) / 64];
        this.forestBits = new long[(cells + 63) / 64];
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TEMP TABLE IF NOT EXISTS grid_page ("
                    + "page_id bigint PRIMARY KEY, payload bytea NOT NULL)");
            statement.execute("TRUNCATE grid_page");
        }
        this.select = connection.prepareStatement(
                "SELECT payload FROM grid_page WHERE page_id = ?");
        this.upsert = connection.prepareStatement(
                "INSERT INTO grid_page (page_id, payload) VALUES (?, ?) "
                        + "ON CONFLICT (page_id) DO UPDATE SET payload = EXCLUDED.payload");
    }

    @Override
    public double dist(int cell) {
        return buffer(cell).getDouble(offset(cell));
    }

    @Override
    public void setDist(int cell, double value) {
        buffer(cell).putDouble(offset(cell), value);
        dirty.add(pageId(cell));
    }

    @Override
    public int parent(int cell) {
        return buffer(cell).getInt(offset(cell) + 8);
    }

    @Override
    public void setParent(int cell, int value) {
        buffer(cell).putInt(offset(cell) + 8, value);
        dirty.add(pageId(cell));
    }

    @Override
    public boolean settled(int cell) {
        return (settledBits[cell >>> 6] & (1L << (cell & 63))) != 0;
    }

    @Override
    public void setSettled(int cell, boolean value) {
        if (value) {
            settledBits[cell >>> 6] |= 1L << (cell & 63);
        } else {
            settledBits[cell >>> 6] &= ~(1L << (cell & 63));
        }
    }

    @Override
    public boolean inForest(int cell) {
        return (forestBits[cell >>> 6] & (1L << (cell & 63))) != 0;
    }

    @Override
    public void setInForest(int cell, boolean value) {
        if (value) {
            forestBits[cell >>> 6] |= 1L << (cell & 63);
        } else {
            forestBits[cell >>> 6] &= ~(1L << (cell & 63));
        }
    }

    private int offset(int cell) {
        return (int) (cell % pageCells) * BYTES_PER_CELL;
    }

    private long pageId(int cell) {
        return cell / pageCells;
    }

    private ByteBuffer buffer(int cell) {
        long id = pageId(cell);
        ByteBuffer page = cache.get(id);
        if (page == null) {
            byte[] payload = load(id);
            page = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
            cache.put(id, page);
            cacheBytes += payload.length;
            evict();
        }
        return page;
    }

    private byte[] load(long pageId) {
        try {
            select.setLong(1, pageId);
            try (ResultSet resultSet = select.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getBytes(1);
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Чтение страницы сетки не удалось: " + pageId,
                    exception);
        }
        return emptyPage();
    }

    private byte[] emptyPage() {
        byte[] page = new byte[pageCells * BYTES_PER_CELL];
        ByteBuffer buffer = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < pageCells; i++) {
            buffer.putDouble(i * BYTES_PER_CELL, Double.POSITIVE_INFINITY);
            buffer.putInt(i * BYTES_PER_CELL + 8, -1);
        }
        return page;
    }

    private void evict() {
        Iterator<Map.Entry<Long, ByteBuffer>> iterator = cache.entrySet().iterator();
        while (cacheBytes > maxCacheBytes && iterator.hasNext()) {
            Map.Entry<Long, ByteBuffer> eldest = iterator.next();
            if (dirty.contains(eldest.getKey())) {
                write(eldest.getKey(), eldest.getValue().array());
                dirty.remove(eldest.getKey());
            }
            cacheBytes -= eldest.getValue().array().length;
            iterator.remove();
        }
    }

    private void write(long pageId, byte[] payload) {
        try {
            upsert.setLong(1, pageId);
            upsert.setBytes(2, payload);
            upsert.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Запись страницы сетки не удалась: " + pageId,
                    exception);
        }
    }

    @Override
    public void flush() {
        for (Map.Entry<Long, ByteBuffer> entry : cache.entrySet()) {
            if (dirty.contains(entry.getKey())) {
                write(entry.getKey(), entry.getValue().array());
            }
        }
        dirty.clear();
    }

    @Override
    public void close() {
        try {
            flush();
            if (select != null) {
                select.close();
            }
            if (upsert != null) {
                upsert.close();
            }
        } catch (SQLException exception) {
            // игнорируем проблемы закрытия
        } finally {
            try {
                connection.close();
            } catch (SQLException exception) {
                // игнорируем
            }
        }
    }
}
