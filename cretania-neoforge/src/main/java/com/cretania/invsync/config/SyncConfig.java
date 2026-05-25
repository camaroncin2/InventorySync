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

    // Forced spawn: si está habilitado, TODO jugador que entra al server (login,
    // /server tiendas, transfer, comando) aparece en estas coordenadas. Sobrescribe
    // cualquier SET_POSITION pendiente y las coords guardadas en su perfil.
    public final ModConfigSpec.BooleanValue forcedSpawnEnabled;
    public final ModConfigSpec.DoubleValue forcedSpawnX;
    public final ModConfigSpec.DoubleValue forcedSpawnY;
    public final ModConfigSpec.DoubleValue forcedSpawnZ;
    public final ModConfigSpec.DoubleValue forcedSpawnYaw;
    public final ModConfigSpec.DoubleValue forcedSpawnPitch;

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
                .comment("Deprecated: loading is now always done directly from MongoDB. This option is ignored.")
                .define("waitForProxyCoordinator", false);

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

        builder.comment("""
                Forced spawn
                - Si enabled=true, todo jugador al entrar al servidor se TP a estas coords,
                  sin importar si viene por /server, comando o transferencia desde otro server.
                - Útil para tiendas / lobbies con punto de entrada fijo.
                """);
        builder.push("forced_spawn");

        forcedSpawnEnabled = builder
                .comment("Forzar spawn en coordenadas fijas para todos los jugadores al entrar.")
                .define("enabled", false);

        forcedSpawnX = builder
                .comment("Coordenada X del spawn forzado.")
                .defineInRange("x", 0.0, -30_000_000.0, 30_000_000.0);

        forcedSpawnY = builder
                .comment("Coordenada Y del spawn forzado.")
                .defineInRange("y", 64.0, -2048.0, 2048.0);

        forcedSpawnZ = builder
                .comment("Coordenada Z del spawn forzado.")
                .defineInRange("z", 0.0, -30_000_000.0, 30_000_000.0);

        forcedSpawnYaw = builder
                .comment("Yaw (rotación horizontal) del spawn forzado.")
                .defineInRange("yaw", 0.0, -360.0, 360.0);

        forcedSpawnPitch = builder
                .comment("Pitch (rotación vertical) del spawn forzado.")
                .defineInRange("pitch", 0.0, -90.0, 90.0);

        builder.pop();
    }
}
