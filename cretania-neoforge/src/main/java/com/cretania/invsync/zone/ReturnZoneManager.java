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
 * La transferencia se realiza via socket TCP a 127.0.0.1:25899 (Velocity).
 */
public class ReturnZoneManager {

    private static final int CHECK_INTERVAL      = 20; // 1 segundo (zonas remotas / outside)
    private static final int FAST_CHECK_INTERVAL = 5;  // 0.25 s (zonas local_rtp tipo portal)
    private static final int SOCKET_PORT         = 25899;

    private static int tickCounter     = 0;
    private static int fastTickCounter = 0;

    /** Zona "outside": si el jugador SALE de este rectángulo, regresa a returnServer. */
    private static double minX, maxX, minZ, maxZ;
    private static String returnServer = "lobby";
    private static boolean enabled     = false;

    /**
     * Zonas "trigger" (entrar = TP). Coords destino opcionales: si están configuradas,
     * se envían en el TRANSFER para que el server destino TP al jugador a una coord
     * específica (ej. dentro del lobby cerca del portal de retorno).
     */
    public record TriggerZone(double minX, double maxX, double minZ, double maxZ, double minY, double maxY,
                              String targetServer,
                              Double targetX, Double targetY, Double targetZ,
                              Float targetYaw, Float targetPitch,
                              Integer rtpRadius,
                              boolean localRtp) {
        boolean contains(double x, double y, double z) {
            return x >= minX && x <= maxX
                    && z >= minZ && z <= maxZ
                    && (minY == maxY || (y >= minY && y <= maxY));
        }
        boolean hasTargetCoords() {
            return targetX != null && targetY != null && targetZ != null;
        }
        boolean isRtp() {
            return rtpRadius != null && rtpRadius > 0;
        }
    }
    private static java.util.List<TriggerZone> triggerZones = java.util.Collections.emptyList();

    private static final Set<UUID> TRANSFERRING = ConcurrentHashMap.newKeySet();

    // -------------------------------------------------------------------------
    // Inicialización
    // -------------------------------------------------------------------------

    public static void init(MinecraftServer server) {
        Path configPath = server.getServerDirectory()
                .resolve("config")
                .resolve("invsync-return-zone.toml");

        // Reset estado (por si init se llama múltiples veces)
        enabled = false;
        triggerZones = java.util.Collections.emptyList();

        if (!Files.exists(configPath)) {
            CretaniaSync.LOGGER.info("[Cretania-Zones] No existe invsync-return-zone.toml — zonas desactivadas.");
            return;
        }

        try (FileConfig config = FileConfig.of(configPath)) {
            config.load();

            // [zone] singular: modo "outside" (compat retro)
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

            // [[trigger_zone]]: lista de zonas modo "inside" — entrar al rectángulo = TP
            java.util.List<Config> triggerList = config.getOrElse("trigger_zone", java.util.Collections.emptyList());
            java.util.List<TriggerZone> loaded = new java.util.ArrayList<>();
            for (Config tz : triggerList) {
                double x1 = ((Number) tz.getOrElse("x1", 0.0)).doubleValue();
                double z1 = ((Number) tz.getOrElse("z1", 0.0)).doubleValue();
                double x2 = ((Number) tz.getOrElse("x2", 0.0)).doubleValue();
                double z2 = ((Number) tz.getOrElse("z2", 0.0)).doubleValue();
                double y1 = ((Number) tz.getOrElse("y1", 0.0)).doubleValue();
                double y2 = ((Number) tz.getOrElse("y2", 0.0)).doubleValue();
                String target = tz.getOrElse("targetServer", "lobby");
                Double tx = tz.contains("target_x") ? ((Number) tz.get("target_x")).doubleValue() : null;
                Double ty = tz.contains("target_y") ? ((Number) tz.get("target_y")).doubleValue() : null;
                Double tz_ = tz.contains("target_z") ? ((Number) tz.get("target_z")).doubleValue() : null;
                Float tyaw = tz.contains("target_yaw") ? ((Number) tz.get("target_yaw")).floatValue() : null;
                Float tpitch = tz.contains("target_pitch") ? ((Number) tz.get("target_pitch")).floatValue() : null;
                Integer rtpRadius = tz.contains("rtp_radius") ? ((Number) tz.get("rtp_radius")).intValue() : null;
                // local_rtp: si true → RTP dentro del mismo server (sin pasar por Velocity).
                // Útil para portales como end_gateway. Requiere rtp_radius > 0.
                boolean localRtp = tz.getOrElse("local_rtp", false);
                TriggerZone trigger = new TriggerZone(
                        Math.min(x1, x2), Math.max(x1, x2),
                        Math.min(z1, z2), Math.max(z1, z2),
                        Math.min(y1, y2), Math.max(y1, y2),
                        target, tx, ty, tz_, tyaw, tpitch, rtpRadius, localRtp);
                loaded.add(trigger);
                String mode;
                if (trigger.localRtp())           mode = "LOCAL_RTP radius=" + rtpRadius;
                else if (trigger.isRtp())         mode = "RTP radius=" + rtpRadius;
                else if (trigger.hasTargetCoords()) mode = "@ (" + tx + "," + ty + "," + tz_ + ")";
                else                              mode = "(spawn default)";
                CretaniaSync.LOGGER.info("[Cretania-Zones] TRIGGER: X[{},{}] Y[{},{}] Z[{},{}] → {} {}",
                        trigger.minX(), trigger.maxX(),
                        trigger.minY(), trigger.maxY(),
                        trigger.minZ(), trigger.maxZ(),
                        target, mode);
            }
            triggerZones = java.util.Collections.unmodifiableList(loaded);

            if (!enabled && triggerZones.isEmpty()) {
                CretaniaSync.LOGGER.warn("[Cretania-Zones] invsync-return-zone.toml sin [zone] ni [[trigger_zone]] — desactivado.");
            }
        } catch (Exception e) {
            CretaniaSync.LOGGER.error("[Cretania-Zones] Error cargando invsync-return-zone.toml: {}", e.getMessage());
            enabled = false;
            triggerZones = java.util.Collections.emptyList();
        }
    }

    // -------------------------------------------------------------------------
    // Tick event
    // -------------------------------------------------------------------------

    /**
     * Anti-bucle al entrar al server: si el jugador apareció dentro de alguna
     * trigger_zone (porque su última posición guardada estaba ahí), TP al spawn
     * del overworld para que NO se dispare un TRANSFER inmediato.
     */
    public static void onPlayerLogin(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (triggerZones.isEmpty() && !enabled) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        UUID uuid = player.getUUID();
        MinecraftServer srv = player.getServer();

        // Período de gracia: el tick check no debe disparar TRANSFER mientras carga
        TRANSFERRING.add(uuid);

        java.util.concurrent.CompletableFuture.runAsync(() -> {
            // Esperar a que SET_POSITION / load de inventario / chunks se apliquen
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}

            srv.execute(() -> {
                if (srv.getPlayerList().getPlayer(uuid) == null) return;
                double px = player.getX();
                double py = player.getY();
                double pz = player.getZ();

                boolean inTrigger = false;
                for (TriggerZone tz : triggerZones) {
                    if (tz.contains(px, py, pz)) {
                        inTrigger = true;
                        break;
                    }
                }
                boolean outsideMain = enabled && (px < minX || px > maxX || pz < minZ || pz > maxZ);

                if (inTrigger || outsideMain) {
                    // Cayó en zona trigger o fuera de zona principal → TP al spawn safe
                    net.minecraft.server.level.ServerLevel overworld = srv.overworld();
                    if (overworld == null) return;
                    net.minecraft.core.BlockPos spawn = overworld.getSharedSpawnPos();
                    try {
                        player.teleportTo(overworld,
                                spawn.getX() + 0.5, spawn.getY() + 1.0, spawn.getZ() + 0.5,
                                java.util.Set.of(), 0f, 0f);
                    } catch (Throwable t) {
                        player.teleportTo(spawn.getX() + 0.5, spawn.getY() + 1.0, spawn.getZ() + 0.5);
                    }
                    CretaniaSync.LOGGER.info("[Cretania-Zones] {} apareció en zona ({},{},{}) → TP a spawn overworld ({},{},{}) para evitar bucle",
                            player.getGameProfile().getName(), px, py, pz,
                            spawn.getX(), spawn.getY(), spawn.getZ());
                }
            });

            // Completar 10 s de gracia
            try { Thread.sleep(9_500); } catch (InterruptedException ignored) {}
            TRANSFERRING.remove(uuid);
        });
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        if (!enabled && triggerZones.isEmpty()) return;
        MinecraftServer server = event.getServer();

        // ── Fast check: zonas local_rtp (portales tipo end_gateway). ───────────
        // Se ejecuta cada 5 ticks (0.25 s) para detectar al jugador antes de que
        // la mecánica vanilla del end_gateway lo agarre.
        if (++fastTickCounter >= FAST_CHECK_INTERVAL) {
            fastTickCounter = 0;
            checkLocalRtpZones(server);
        }

        // ── Slow check: outside + zonas trigger remotas. ───────────────────────
        if (++tickCounter < CHECK_INTERVAL) return;
        tickCounter = 0;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID uuid = player.getUUID();
            if (TRANSFERRING.contains(uuid)) continue;

            double px = player.getX();
            double py = player.getY();
            double pz = player.getZ();

            // Modo OUTSIDE: fuera del rectángulo principal → return (sin coords destino)
            if (enabled && (px < minX || px > maxX || pz < minZ || pz > maxZ)) {
                initiateReturn(player, returnServer, null, null, null, null, null);
                continue;
            }

            // Modo TRIGGER (remoto): entrar a un rectángulo específico → TP a su targetServer
            // (con coords destino opcionales o RTP aleatorio). Las zonas localRtp se procesan
            // en el fast loop arriba — acá las saltamos.
            for (TriggerZone tz : triggerZones) {
                if (tz.localRtp()) continue;
                if (tz.contains(px, py, pz)) {
                    if (tz.isRtp()) {
                        // RTP: generar X/Z aleatorio en [-rtpRadius, +rtpRadius]. Y=320 (sky)
                        // — el server destino busca top non-air block + 1 en applyTeleport.
                        int r = tz.rtpRadius();
                        double rx = (Math.random() * 2 - 1) * r;
                        double rz = (Math.random() * 2 - 1) * r;
                        initiateReturn(player, tz.targetServer(), rx, 320.0, rz, 0f, 0f);
                    } else {
                        initiateReturn(player, tz.targetServer(),
                                tz.targetX(), tz.targetY(), tz.targetZ(),
                                tz.targetYaw(), tz.targetPitch());
                    }
                    break;
                }
            }
        }
    }

    /**
     * Procesa solo zonas con local_rtp=true. RTP dentro del mismo server, sin pasar por
     * Velocity. Usa SyncChannelHandler.applyTeleport (que con Y=320 busca top non-air).
     */
    private static void checkLocalRtpZones(MinecraftServer server) {
        boolean anyLocal = false;
        for (TriggerZone tz : triggerZones) {
            if (tz.localRtp()) { anyLocal = true; break; }
        }
        if (!anyLocal) return;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID uuid = player.getUUID();
            if (TRANSFERRING.contains(uuid)) continue;

            double px = player.getX();
            double py = player.getY();
            double pz = player.getZ();

            for (TriggerZone tz : triggerZones) {
                if (!tz.localRtp()) continue;
                if (!tz.contains(px, py, pz)) continue;

                int r = tz.isRtp() ? tz.rtpRadius() : 1000;
                double rx = (Math.random() * 2 - 1) * r;
                double rz = (Math.random() * 2 - 1) * r;

                // Bloquear re-trigger por 8 s (suficiente para que el TP se complete
                // y el jugador esté lejos de la zona original)
                TRANSFERRING.add(uuid);
                CretaniaSync.LOGGER.info("[Cretania-Zones] {} entró a LOCAL_RTP zone ({},{},{}) → TP a ({},sky,{})",
                        player.getGameProfile().getName(), px, py, pz, rx, rz);

                // applyTeleport con Y=320 busca top non-air block + 1 (sky marker)
                com.cretania.invsync.network.SyncChannelHandler.applyTeleport(
                        player, rx, 320.0, rz, 0f, 0f);

                CompletableFuture.runAsync(() -> {
                    try { Thread.sleep(8_000); } catch (InterruptedException ignored) {}
                    TRANSFERRING.remove(uuid);
                });
                break;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Transferencia de retorno
    // -------------------------------------------------------------------------

    private static void initiateReturn(ServerPlayer player, String targetServer,
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
