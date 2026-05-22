package com.cretania.invsync.database;

import com.cretania.invsync.CretaniaSync;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DatabaseManager {

    private static final String CREATE_SCOPED_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS player_inventory_data (
                uuid VARCHAR(36) NOT NULL,
                inventory_scope VARCHAR(64) NOT NULL,
                player_name VARCHAR(16) NOT NULL,
                nbt_data LONGTEXT NOT NULL,
                last_server VARCHAR(64) NOT NULL,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (uuid, inventory_scope)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """;

    private static final String UPSERT_SCOPED_SQL = """
            INSERT INTO player_inventory_data (uuid, inventory_scope, player_name, nbt_data, last_server, updated_at)
            VALUES (?, ?, ?, ?, ?, NOW())
            ON DUPLICATE KEY UPDATE
                player_name = VALUES(player_name),
                nbt_data = VALUES(nbt_data),
                last_server = VALUES(last_server),
                updated_at = NOW();
            """;

    private static final String SELECT_SCOPED_SQL = "SELECT nbt_data FROM player_inventory_data WHERE uuid = ? AND inventory_scope = ?;";

    private HikariDataSource dataSource;
    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "Cretania-DB-Worker");
        t.setDaemon(true);
        return t;
    });

    public void initialize(String host, int port, String database, String username, String password,
                           int maxPoolSize, int minIdle, int connectionTimeout) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8");
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(minIdle);
        config.setConnectionTimeout(connectionTimeout);
        config.setPoolName("Cretania-MySQL-Pool");
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");

        dataSource = new HikariDataSource(config);
        createTablesIfNotExists();
        CretaniaSync.LOGGER.info("[Cretania] Pool de conexiones MySQL inicializado correctamente.");
    }

    private void createTablesIfNotExists() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_SCOPED_TABLE_SQL);
        } catch (SQLException e) {
            CretaniaSync.LOGGER.error("[Cretania] Error creando tabla player_inventory_data: {}", e.getMessage());
        }
    }

    public CompletableFuture<Void> savePlayerData(UUID uuid, String playerName, String base64Nbt, String serverName, String inventoryScope) {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(UPSERT_SCOPED_SQL)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, inventoryScope);
                ps.setString(3, playerName);
                ps.setString(4, base64Nbt);
                ps.setString(5, serverName);
                ps.executeUpdate();
                CretaniaSync.LOGGER.debug("[Cretania] Datos guardados para: {} ({}) scope={}", playerName, uuid, inventoryScope);
            } catch (SQLException e) {
                CretaniaSync.LOGGER.error("[Cretania] Error guardando datos de {} scope={}: {}", playerName, inventoryScope, e.getMessage());
                throw new RuntimeException("Fallo al guardar datos del jugador", e);
            }
        }, executor);
    }

    public CompletableFuture<Optional<String>> loadPlayerData(UUID uuid, String inventoryScope) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(SELECT_SCOPED_SQL)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, inventoryScope);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(rs.getString("nbt_data"));
                    }
                }
            } catch (SQLException e) {
                CretaniaSync.LOGGER.error("[Cretania] Error cargando datos de {} scope={}: {}", uuid, inventoryScope, e.getMessage());
                throw new RuntimeException("Fallo al cargar datos del jugador", e);
            }
            return Optional.empty();
        }, executor);
    }

    public Connection getConnection() throws SQLException {
        if (dataSource == null) {
            throw new SQLException("[Cretania] DataSource no inicializado.");
        }
        return dataSource.getConnection();
    }

    public void shutdown() {
        executor.shutdown();
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
        CretaniaSync.LOGGER.info("[Cretania] Pool de conexiones MySQL cerrado.");
    }

    public boolean isConnected() {
        return dataSource != null && !dataSource.isClosed();
    }
}
