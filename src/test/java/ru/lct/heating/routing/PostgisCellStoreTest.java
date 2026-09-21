package ru.lct.heating.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Интеграционный тест спилла в PostGIS TEMP (ADR-0033, фаза B). Требует
 * доступной БД (docker-compose); иначе тест пропускается.
 */
@Tag("slow")
class PostgisCellStoreTest {

    private HikariDataSource dataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:postgresql://localhost:5432/heating");
        dataSource.setUsername("heating");
        dataSource.setPassword("heating");
        dataSource.setMaximumPoolSize(1);
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection).isNotNull();
        } catch (SQLException exception) {
            dataSource.close();
            return null;
        }
        return dataSource;
    }

    @Test
    void storesDistParentAndFlags() throws Exception {
        HikariDataSource dataSource = dataSource();
        assumeTrue(dataSource != null, "PostgreSQL недоступен (docker-compose db-up)");
        try (PostgisCellStore store = new PostgisCellStore(dataSource.getConnection(), 4, 1,
                100000)) {
            store.setDist(3, 42.5);
            store.setParent(3, 1);
            store.setSettled(3, true);
            store.setInForest(3, true);
            store.setDist(70000, 7.0);
            store.flush();

            assertThat(store.dist(3)).isEqualTo(42.5);
            assertThat(store.parent(3)).isEqualTo(1);
            assertThat(store.settled(3)).isTrue();
            assertThat(store.inForest(3)).isTrue();
            assertThat(store.dist(70000)).isEqualTo(7.0);
            assertThat(store.dist(4)).isEqualTo(Double.POSITIVE_INFINITY);
            assertThat(store.parent(4)).isEqualTo(-1);
        } finally {
            dataSource.close();
        }
    }

    /**
     * Соединение из пула переиспользует сессию, поэтому второй стор не должен
     * видеть страницы первого (TRUNCATE при создании).
     */
    @Test
    void secondStoreOnSameConnection_seesFreshState() throws Exception {
        HikariDataSource dataSource = dataSource();
        assumeTrue(dataSource != null, "PostgreSQL недоступен (docker-compose db-up)");
        try {
            try (PostgisCellStore first = new PostgisCellStore(dataSource.getConnection(), 4, 1,
                    100000)) {
                first.setDist(3, 42.5);
                first.setParent(3, 1);
                first.flush();
            }
            try (PostgisCellStore second = new PostgisCellStore(dataSource.getConnection(), 4, 1,
                    100000)) {
                assertThat(second.dist(3)).isEqualTo(Double.POSITIVE_INFINITY);
                assertThat(second.parent(3)).isEqualTo(-1);
            }
        } finally {
            dataSource.close();
        }
    }
}
