package com.cretania.invsync.zone;

import com.cretania.invsync.CretaniaSync;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.file.FileConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detecta cuando un jugador abandona la zona asignada a este servidor
 * y lo transfiere de vuelta al servidor configurado (normalmente "lobby").
 *
 * Configuración: config/invsync-return-zone.toml
 *
 * Ejemplo:
 * [zone]
 * x1 = -600.0
 * z1 = -385.0
 * x2 = -411.0
 * z2 =  400.0
 * returnServer = "lobby"
 *
 * NOTA: los antiguos portales por coordenadas ([[trigger_zone]]) fueron
 * ELIMINADOS — ya no funcionan. Usar portales físicos de bloques del Nether:
 * /portales (ver {@link PortalManager}).
 *
 * La transferencia se realiza via socket TCP a 127.0.0.1:25899 (Velocity).
 */
public class ReturnZoneManager {

    private static final int CHECK_INTERVAL = 20; // 1 segundo
    private static final int SOCKET_PORT    = 25899;

    private static int tickCounter = 0;

    /** Zona "outside": si el jugador SALE de este rectángulo, regresa a returnServer. */
    private static double minX, maxX, minZ, maxZ;
    private static String returnServer = "lobby";
    private static boolean enabled     = false;

    private static final Set<UUID> TRANSFERRING = ConcurrentHashMap.newKeySet();

    /** Compartido con PortalManager: true si el jugador está en transferencia/gracia. */
    static boolean isTransferring(UUID uuid) {
        return TRANSFERRING.contains(uuid);
    }

    /** Marca al jugador en transferencia/gracia y limpia el flag tras {@code millis} ms. */
    static void markTransferring(UUID uuid, long millis) {
        TRANSFERRING.add(uuid);
        CompletableFuture.runAsync(() -> {
            try { Thread.sleep(millis); } catch (InterruptedException ignored) {}
            TRANSFERRING.remove(uuid);
        });
    }

    // -------------------------------------------------------------------------
    // Inicialización
    // -------------------------------------------------------------------------

    public static void init(MinecraftServer server) {
        Path configPath = server.getServerDirectory()
                .resolve("config")
                .resolve("invsync-return-zone.toml");

        // Reset estado (por si init se llama múltiples veces)
        enabled = false;

        if (!Files.exists(configPath)) {
            CretaniaSync.LOGGER.info("[Cretania-Zones] No existe invsync-return-zone.toml — zona de retorno desactivada.");
            return;
        }

        try (FileConfig config = FileConfig.of(configPath)) {
            config.load();

            // [zone] singular: modo "outside"
            Config zone = config.get("zone");
            if (zone != null) {
                double x1 = ((Number) zone.getOrElse("x1", 0.0)).doubleValue();
                double z1 = ((Number) zone.getOrElse("z1", 0.0)).doubleValue();
                double x2 = ((Number) zone.getOrElse("x2", 0.0)).doubleValue();
                double z2 = ((Number) zone.getOrElse("z2", 0.0)).doubleValue();
                returnServer = zone.getOrElse("returnServer", "lobby");
                minX = Math.min(x1, x2);
                maxX = Math.max(x1, x2);
                minZ = Math.min(z1, z2);
                maxZ = Math.max(z1, z2);
                enabled = true;
                CretaniaSync.LOGGER.info("[Cretania-Zones] OUTSIDE: X[{},{}] Z[{},{}] → {}",
                        minX, maxX, minZ, maxZ, returnServer);
            }

            // [[trigger_zone]]: ELIMINADO. Avisar si la config vieja todavía los tiene.
            java.util.List<Config> triggerList = config.getOrElse("trigger_zone", java.util.Collections.emptyList());
            if (!triggerList.isEmpty()) {
                CretaniaSync.LOGGER.warn("[Cretania-Zones] Se encontraron {} [[trigger_zone]] en invsync-return-zone.toml "
                        + "— los portales por coordenadas YA NO FUNCIONAN. Usa portales físicos: /portales crear <nombre>.",
                        triggerList.size());
            }

            if (!enabled) {
                CretaniaSync.LOGGER.info("[Cretania-Zones] invsync-return-zone.toml sin [zone] — zona de retorno desactivada.");
            }
        } catch (Exception e) {
            CretaniaSync.LOGGER.error("[Cretania-Zones] Error cargando invsync-return-zone.toml: {}", e.getMessage());
            enabled = false;
        }
    }

    // -------------------------------------------------------------------------
    // Tick event
    // -------------------------------------------------------------------------

    /**
     * Anti-bucle al entrar al server: TRANSFERRING flag durante 10 s evita que
     * el tick check (zona outside o portales físicos) dispare TRANSFER inmediato
     * mientras el jugador carga. NO se TPea al jugador — respeta su posición.
     *
     * El forced_spawn de cretaniasync (si está configurado) ya garantiza que el
     * player aparezca en un lugar safe fuera de zonas.
     */
    public static void onPlayerLogin(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (!enabled && !PortalManager.hasPortals()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        markTransferring(player.getUUID(), 10_000);
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        if (!enabled) return;
        if (++tickCounter < CHECK_INTERVAL) return;
        tickCounter = 0;

        MinecraftServer server = event.getServer();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (TRANSFERRING.contains(player.getUUID())) continue;

            double px = player.getX();
            double pz = player.getZ();

            // Modo OUTSIDE: fuera del rectángulo principal → return (sin coords destino)
            if (px < minX || px > maxX || pz < minZ || pz > maxZ) {
                initiateReturn(player, returnServer, null, null, null, null, null);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Transferencia de retorno
    // -------------------------------------------------------------------------

    /** Package-private: también lo usa PortalManager para los portales físicos. */
    static void initiateReturn(ServerPlayer player, String targetServer,
                               Double tx, Double ty, Double tz, Float tyaw, Float tpitch) {
        TRANSFERRING.add(player.getUUID());
        CretaniaSync.LOGGER.info("[Cretania-Zones] {} → {} (pos actual: {},{},{}{})",
                player.getGameProfile().getName(), targetServer,
                player.getX(), player.getY(), player.getZ(),
                tx != null ? " → coords destino (" + tx + "," + ty + "," + tz + ")" : "");

        String msg;
        if (tx != null && ty != null && tz != null) {
            // Con coords destino: el server destino TP al jugador a la coord exacta
            float yawF = tyaw != null ? tyaw : player.getYRot();
            float pitchF = tpitch != null ? tpitch : player.getXRot();
            msg = String.format(Locale.US, "TRANSFER:%s:%s:%.4f:%.4f:%.4f:%.4f:%.4f",
                    player.getUUID(), targetServer,
                    tx, ty, tz, (double) yawF, (double) pitchF);
        } else {
            // Sin coords: server destino aplica spawn default
            msg = "TRANSFER:" + player.getUUID() + ":" + targetServer;
        }

        final UUID uuid = player.getUUID();

        // IMPORTANTE: CompletableFuture.runAsync() para que los sleeps ocurran en un hilo
        // del ForkJoinPool y NUNCA bloqueen el server thread.
        CompletableFuture.runAsync(() -> {
            // Delay de 2 s antes de conectar al otro servidor.
            // Esto da tiempo al jugador de alejarse de la frontera de la zona y evita
            // que en lobby su posición caiga dentro del trigger de zona y provoque el bucle.
            try { Thread.sleep(2_000); } catch (InterruptedException ignored) {}

            // Enviar via socket local a Velocity (IPv4 explícito para compatibilidad Java 21/25)
            try (Socket socket = new Socket("127.0.0.1", SOCKET_PORT)) {
                socket.setSoTimeout(3000);
                OutputStream out = socket.getOutputStream();
                out.write((msg + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception e) {
                CretaniaSync.LOGGER.warn("[Cretania-Zones] No se pudo enviar TRANSFER via socket: {}", e.getMessage());
            }

            // Limpiar flag después de 6 segundos adicionales por si algo falla
            try { Thread.sleep(6_000); } catch (InterruptedException ignored) {}
            TRANSFERRING.remove(uuid);
        });
    }
}
