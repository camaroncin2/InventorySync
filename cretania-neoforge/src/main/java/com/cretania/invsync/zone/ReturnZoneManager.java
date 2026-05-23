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
 * La transferencia se realiza via socket TCP a 127.0.0.1:25899 (Velocity).
 */
public class ReturnZoneManager {

    private static final int CHECK_INTERVAL = 20; // 1 segundo
    private static final int SOCKET_PORT     = 25899;

    private static int    tickCounter  = 0;
    private static double minX, maxX, minZ, maxZ;
    private static String returnServer = "lobby";
    private static boolean enabled     = false;

    private static final Set<UUID> TRANSFERRING = ConcurrentHashMap.newKeySet();

    // -------------------------------------------------------------------------
    // Inicialización
    // -------------------------------------------------------------------------

    public static void init(MinecraftServer server) {
        Path configPath = server.getServerDirectory()
                .resolve("config")
                .resolve("invsync-return-zone.toml");

        if (!Files.exists(configPath)) {
            CretaniaSync.LOGGER.info("[Cretania-Zones] No existe invsync-return-zone.toml — zona de retorno desactivada.");
            enabled = false;
            return;
        }

        try (FileConfig config = FileConfig.of(configPath)) {
            config.load();
            Config zone = config.get("zone");
            if (zone == null) {
                CretaniaSync.LOGGER.warn("[Cretania-Zones] invsync-return-zone.toml sin sección [zone] — desactivado.");
                enabled = false;
                return;
            }

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

            CretaniaSync.LOGGER.info("[Cretania-Zones] Zona de retorno: X[{},{}] Z[{},{}] → {}",
                    minX, maxX, minZ, maxZ, returnServer);
        } catch (Exception e) {
            CretaniaSync.LOGGER.error("[Cretania-Zones] Error cargando invsync-return-zone.toml: {}", e.getMessage());
            enabled = false;
        }
    }

    // -------------------------------------------------------------------------
    // Tick event
    // -------------------------------------------------------------------------

    public static void onServerTick(ServerTickEvent.Post event) {
        if (!enabled) return;
        if (++tickCounter < CHECK_INTERVAL) return;
        tickCounter = 0;

        MinecraftServer server = event.getServer();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID uuid = player.getUUID();
            if (TRANSFERRING.contains(uuid)) continue;

            double px = player.getX();
            double pz = player.getZ();

            // Si el jugador está FUERA de la zona asignada → devolver al servidor anterior
            if (px < minX || px > maxX || pz < minZ || pz > maxZ) {
                initiateReturn(player);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Transferencia de retorno
    // -------------------------------------------------------------------------

    private static void initiateReturn(ServerPlayer player) {
        TRANSFERRING.add(player.getUUID());
        CretaniaSync.LOGGER.info("[Cretania-Zones] {} fuera de zona → {} (pos: {},{},{})",
                player.getGameProfile().getName(), returnServer,
                player.getX(), player.getY(), player.getZ());

        // Incluir posición actual (fuera de la zona) para que el lobby teletransporte al jugador
        // a un punto FUERA de la zona en lugar de su última posición del lobby (dentro de la zona).
        // Esto evita el bucle lobby→tiendas→lobby al retornar.
        String msg = String.format(Locale.US, "TRANSFER:%s:%s:%.4f:%.4f:%.4f:%.4f:%.4f",
                player.getUUID(), returnServer,
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot());

        // Enviar via socket local a Velocity (IPv4 explícito para compatibilidad Java 21/25)
        player.getServer().submitAsync(() -> {
            try (Socket socket = new Socket("127.0.0.1", SOCKET_PORT)) {
                socket.setSoTimeout(3000);
                OutputStream out = socket.getOutputStream();
                out.write((msg + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception e) {
                CretaniaSync.LOGGER.warn("[Cretania-Zones] No se pudo enviar TRANSFER via socket: {}", e.getMessage());
                // No quitar TRANSFERRING aqui: el cleanup de 6 s en el hilo inferior lo maneja
            }
        });

        // Limpiar flag después de 6 segundos por si algo falla
        player.getServer().submitAsync(() -> {
            try { Thread.sleep(6_000); } catch (InterruptedException ignored) {}
            TRANSFERRING.remove(player.getUUID());
        });
    }
}
