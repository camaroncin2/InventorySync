package com.cretania.invsync.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public class SyncConfig {

    public static final ModConfigSpec SPEC;
    public static final SyncConfig INSTANCE;

    public final ModConfigSpec.ConfigValue<String> mysqlHost;
    public final ModConfigSpec.IntValue mysqlPort;
    public final ModConfigSpec.ConfigValue<String> mysqlDatabase;
    public final ModConfigSpec.ConfigValue<String> mysqlUsername;
    public final ModConfigSpec.ConfigValue<String> mysqlPassword;

    public final ModConfigSpec.IntValue poolMaxSize;
    public final ModConfigSpec.IntValue poolMinIdle;
    public final ModConfigSpec.IntValue connectionTimeout;

    public final ModConfigSpec.ConfigValue<String> inventoryScope;
    public final ModConfigSpec.ConfigValue<String> serverName;
    public final ModConfigSpec.BooleanValue waitForProxyCoordinator;
    public final ModConfigSpec.IntValue syncTimeoutSeconds;
    public final ModConfigSpec.BooleanValue kickOnFailure;
    public final ModConfigSpec.BooleanValue clearInventoryWhenNotSynced;

    static {
        Pair<SyncConfig, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(SyncConfig::new);
        INSTANCE = pair.getLeft();
        SPEC = pair.getRight();
    }

    private SyncConfig(ModConfigSpec.Builder builder) {
        builder.comment("Database");
        builder.push("mysql");

        mysqlHost = builder.define("host", "127.0.0.1");
        mysqlPort = builder.defineInRange("port", 3306, 1, 65535);
        mysqlDatabase = builder.define("database", "cretania");
        mysqlUsername = builder.define("username", "cretania_user");
        mysqlPassword = builder.define("password", "change_me");

        builder.pop();

        builder.comment("Connection pool");
        builder.push("pool");

        poolMaxSize = builder.defineInRange("maxSize", 10, 2, 50);
        poolMinIdle = builder.defineInRange("minIdle", 2, 1, 10);
        connectionTimeout = builder.defineInRange("connectionTimeout", 5000, 1000, 30000);

        builder.pop();

        builder.comment("""
                Inventory sync
                - Same inventoryScope = servers share inventory.
                - Empty inventoryScope = this server never loads or saves shared inventory.
                """);
        builder.push("sync");

        inventoryScope = builder
                .comment("Example: survival, minigames. Leave empty for lobby/vanilla/no shared inventory.")
                .define("inventoryScope", "");

        serverName = builder
                .comment("Use the same backend name configured in Velocity.")
                .define("serverName", "unknown");

        waitForProxyCoordinator = builder
                .comment("Keep enabled when this server is behind Velocity.")
                .define("waitForProxyCoordinator", true);

        clearInventoryWhenNotSynced = builder
                .comment("Clear inventory when this server must not receive a shared inventory.")
                .define("clearInventoryWhenNotSynced", true);

        syncTimeoutSeconds = builder
                .comment("Maximum seconds to wait for database/proxy sync.")
                .defineInRange("timeoutSeconds", 10, 3, 60);

        kickOnFailure = builder
                .comment("Disconnect the player if sync fails.")
                .define("kickOnFailure", true);

        builder.pop();
    }
}
