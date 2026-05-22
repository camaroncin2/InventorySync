package com.cretania.invsync.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public class SyncConfig {

    public static final ModConfigSpec SPEC;
    public static final SyncConfig INSTANCE;

    public final ModConfigSpec.ConfigValue<String> mongoUri;
    public final ModConfigSpec.ConfigValue<String> mongoDatabase;

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
        builder.push("mongodb");

        mongoUri = builder
                .comment("URI de conexión a MongoDB. Ejemplo: mongodb://usuario:contraseña@127.0.0.1:27017/cretania")
                .define("uri", "mongodb://127.0.0.1:27017");

        mongoDatabase = builder
                .comment("Nombre de la base de datos MongoDB.")
                .define("database", "cretania");

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
