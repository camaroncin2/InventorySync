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
    public final ModConfigSpec.IntValue autosaveSeconds;
    public final ModConfigSpec.BooleanValue restoreLastPositionOnLogin;

    // Forced spawn: si está habilitado, TODO jugador que entra al server (login,
    // /server tiendas, transfer, comando) aparece en estas coordenadas. Sobrescribe
    // cualquier SET_POSITION pendiente y las coords guardadas en su perfil.
    public final ModConfigSpec.BooleanValue forcedSpawnEnabled;
    public final ModConfigSpec.BooleanValue forcedSpawnUseWorldSpawn;
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

        autosaveSeconds = builder
                .comment("""
                        Segundos entre autoguardados del inventario de los jugadores conectados.
                        Es la ÚNICA red contra un crash duro (kill -9, OOM, corte de luz): sin esto
                        se pierde todo lo hecho desde el último login. 0 = desactivado (NO recomendado).""")
                .defineInRange("autosaveSeconds", 120, 0, 3600);

        restoreLastPositionOnLogin = builder
                .comment("""
                        Al reconectarse (login que NO viene por un portal), devolver al jugador a la
                        última posición guardada — la de su última desconexión — en vez de aplicar el
                        forced_spawn. Pensado para survival. Las llegadas por portal siempre usan las
                        coords del portal, sin importar esta opción. Tiene prioridad sobre forced_spawn.""")
                .define("restoreLastPositionOnLogin", false);

        builder.pop();

        builder.comment("""
                Forced spawn — SOLO se aplica a los logins normales.
                - Si enabled=true, el jugador que entra al servidor aparece en el spawn del
                  mundo (useWorldSpawn=true) o en las coords x/y/z (useWorldSpawn=false),
                  en vez de en la posición donde se desconectó.
                - NO afecta a quien llega por un portal: esas llegadas usan las coords
                  configuradas en el portal de origen y tienen prioridad.
                - Útil para tiendas / lobbies con punto de entrada fijo.
                """);
        builder.push("forced_spawn");

        forcedSpawnEnabled = builder
                .comment("Forzar el punto de aparición en los logins normales (no afecta a los portales).")
                .define("enabled", false);

        forcedSpawnUseWorldSpawn = builder
                .comment("""
                        true  = usar el spawn del mundo (el de /setworldspawn, dinámico).
                        false = usar las coordenadas x/y/z de abajo.""")
                .define("useWorldSpawn", false);

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
