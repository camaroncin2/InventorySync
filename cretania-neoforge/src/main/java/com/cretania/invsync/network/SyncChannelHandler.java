package com.cretania.invsync.network;

import com.cretania.invsync.CretaniaSync;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles the "cretania:sync" plugin messaging channel between NeoForge and Velocity.
 *
 * Protocol:
 * - SAVE_COMPLETE:<uuid>:<playerName>:<scope>
 * - LOAD_READY:<uuid>:<scope>
 * - LOAD_SKIP:<uuid>:<reason>
 */
public class SyncChannelHandler {

    public static final ResourceLocation SYNC_CHANNEL = ResourceLocation.fromNamespaceAndPath("cretania", "sync");

    public static final String MSG_SAVE_COMPLETE = "SAVE_COMPLETE";
    public static final String MSG_LOAD_READY = "LOAD_READY";
    public static final String MSG_LOAD_SKIP = "LOAD_SKIP";
    public static final String MSG_SEPARATOR = ":";

    /**
     * Posiciones pendientes de aplicar cuando el jugador haga login en este servidor.
     * Llenado por SET_POSITION desde Velocity; consumido en InventorySync.onPlayerLogIn.
     * Formato del double[]: {x, y, z, yaw, pitch}
     */
    public static final ConcurrentHashMap<UUID, double[]> PENDING_TELEPORTS = new ConcurrentHashMap<>();

    /**
     * uuid → epoch ms hasta cuando se considera que el jugador llegó por un portal.
     * restoreLastPositionOnLogin lo consulta para NO pisar la posición del portal con la
     * última posición guardada. Ventana corta: solo cubre el momento del login.
     */
    private static final ConcurrentHashMap<UUID, Long> PORTAL_ARRIVAL = new ConcurrentHashMap<>();

    public static void markPortalArrival(UUID uuid) {
        PORTAL_ARRIVAL.put(uuid, System.currentTimeMillis() + 5_000L);
    }

    public static boolean cameFromPortal(UUID uuid) {
        Long expiry = PORTAL_ARRIVAL.get(uuid);
        if (expiry == null) return false;
        if (System.currentTimeMillis() > expiry) {
            PORTAL_ARRIVAL.remove(uuid);
            return false;
        }
        return true;
    }

    public static void sendSaveComplete(UUID uuid, String playerName, String scope, String serverName) {
        String message = MSG_SAVE_COMPLETE + MSG_SEPARATOR
                + uuid + MSG_SEPARATOR
                + playerName + MSG_SEPARATOR
                + scope + MSG_SEPARATOR
                + serverName;

        sendToVelocitySocket(message);

        CretaniaSync.LOGGER.debug("[Cretania] SAVE_COMPLETE enviado para {} scope={}", playerName, scope);
    }

    public static void handleIncomingMessage(byte[] data) {
        String message = new String(data, StandardCharsets.UTF_8);
        // Límite 7 para soportar SET_POSITION:<uuid>:<x>:<y>:<z>:<yaw>:<pitch>
        String[] parts = message.split(MSG_SEPARATOR, 7);

        if (parts.length < 2) {
            CretaniaSync.LOGGER.warn("[Cretania] Mensaje malformado recibido: {}", message);
            return;
        }

        String type    = parts[0];
        String uuidStr = parts[1];

        try {
            UUID uuid = UUID.fromString(uuidStr);

            switch (type) {
                case MSG_LOAD_READY -> {
                    String scope = parts.length >= 3 ? parts[2] : "";
                    CretaniaSync.LOGGER.debug("[Cretania] LOAD_READY recibido para {} scope={}", uuid, scope);
                    CretaniaSync.getInstance().getInventorySync().triggerLoad(uuid, scope);
                }
                case MSG_LOAD_SKIP -> {
                    String reason = parts.length >= 3 ? parts[2] : "unspecified";
                    CretaniaSync.LOGGER.debug("[Cretania] LOAD_SKIP recibido para {} reason={}", uuid, reason);
                    CretaniaSync.getInstance().getInventorySync().triggerSkip(uuid, reason);
                }
                case "SKIN" -> {
                    String value = parts.length >= 3 ? parts[2] : null;
                    String sig   = parts.length >= 4 ? parts[3] : null;
                    if (value != null) {
                        CretaniaSync.LOGGER.info("[Cretania] SKIN recibida para {} (parts={})", uuid, parts.length);
                        CretaniaSync.getInstance().getInventorySync().applyPlayerSkin(uuid, value, sig);
                    } else {
                        CretaniaSync.LOGGER.warn("[Cretania] SKIN recibida para {} pero value es null", uuid);
                    }
                }
                case "SET_POSITION" -> {
                    // SET_POSITION:<uuid>:<x>:<y>:<z>:<yaw>:<pitch>
                    if (parts.length >= 7) {
                        try {
                            double x     = Double.parseDouble(parts[2]);
                            double y     = Double.parseDouble(parts[3]);
                            double z     = Double.parseDouble(parts[4]);
                            float  yaw   = Float.parseFloat(parts[5]);
                            float  pitch = Float.parseFloat(parts[6]);
                            PENDING_TELEPORTS.put(uuid, new double[]{x, y, z, yaw, pitch});
                            markPortalArrival(uuid); // llegó por portal → no restaurar última posición encima
                            CretaniaSync.LOGGER.info("[Cretania-Zone] SET_POSITION recibido para {} → ({},{},{})", uuid, x, y, z);
                            // Si el jugador ya está en el servidor, teleportarlo de inmediato
                            MinecraftServer server = CretaniaSync.getInstance().getServer();
                            if (server != null) {
                                server.execute(() -> {
                                    ServerPlayer online = server.getPlayerList().getPlayer(uuid);
                                    if (online != null) {
                                        applyTeleport(online, x, y, z, yaw, pitch);
                                        PENDING_TELEPORTS.remove(uuid);
                                    }
                                });
                            }
                        } catch (NumberFormatException e) {
                            CretaniaSync.LOGGER.warn("[Cretania-Zone] SET_POSITION con números inválidos: {}", message);
                        }
                    } else {
                        CretaniaSync.LOGGER.warn("[Cretania-Zone] SET_POSITION malformado: {}", message);
                    }
                }
                default -> CretaniaSync.LOGGER.warn("[Cretania] Tipo de mensaje desconocido: {}", type);
            }
        } catch (IllegalArgumentException e) {
            CretaniaSync.LOGGER.warn("[Cretania] UUID invalido en mensaje '{}': {}", message, e.getMessage());
        }
    }

    /**
     * Notifica a Velocity que el jugador está completamente registrado en este backend.
     * Usa socket TCP directo para evitar las restricciones de dirección del canal NeoForge.
     */
    public static void sendPlayerReady(ServerPlayer player) {
        String message = "PLAYER_READY:" + player.getUUID();
        sendToVelocitySocket(message);
        CretaniaSync.LOGGER.info("[Cretania-Fast] PLAYER_READY enviado para {}",
                player.getGameProfile().getName());
    }

    private static void sendToVelocitySocket(String message) {
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try (java.net.Socket sock = new java.net.Socket()) {
                sock.connect(new java.net.InetSocketAddress("127.0.0.1", 25899), 3000);
                sock.setSoTimeout(3000);
                java.io.PrintWriter out = new java.io.PrintWriter(sock.getOutputStream(), true);
                out.println(message);
            } catch (Exception e) {
                CretaniaSync.LOGGER.warn("[Cretania-Fast] Error enviando a Velocity socket: {}", e.getMessage());
            }
        });
    }

    /**
     * Procesa la skin enviada por el cliente directamente al conectar (C2S_SKIN).
     * 1. Aplica la skin en este servidor inmediatamente (sin esperar Velocity).
     * 2. Reenvía a Velocity como UPLOAD_SKIN para que la cachee para futuros servidores.
     * Formato del mensaje: C2S_SKIN:<value>:<signature>
     */
    public static void handleClientSkinMessage(byte[] data, ServerPlayer player) {
        String message = new String(data, StandardCharsets.UTF_8);
        // C2S_SKIN:<value>:<signature>  — base64 no contiene ':'
        String[] parts = message.split(":", 3);
        if (parts.length < 2 || parts[1].isBlank()) {
            CretaniaSync.LOGGER.warn("[Cretania-Fast] C2S_SKIN malformado de {}",
                    player.getGameProfile().getName());
            return;
        }
        String value = parts[1];
        String sig   = parts.length >= 3 ? parts[2] : "";

        UUID uuid = player.getUUID();

        // 1. Aplicar skin en este servidor inmediatamente
        CretaniaSync.getInstance().getInventorySync().applyPlayerSkin(uuid, value,
                sig.isBlank() ? null : sig);

        // 2. Reenviar a Velocity como UPLOAD_SKIN para cachear en AuthProfileListener
        String forward = "UPLOAD_SKIN:" + uuid + ":" + value + ":" + sig;
        sendToVelocitySocket(forward);
        CretaniaSync.LOGGER.info("[Cretania-Fast] C2S_SKIN de {} → skin aplicada + UPLOAD_SKIN a Velocity",
                player.getGameProfile().getName());
    }

    /**
     * Teletransporta al jugador a la posición preservada desde el servidor origen.
     * Usar en el hilo principal del servidor (game thread).
     *
     * Si Y >= 256, se interpreta como "sky" (RTP) → ajusta Y al top non-air block + 1.
     * Si Y resultante cae en void o en bloque no caminable, también ajusta para evitar
     * que el jugador muera al spawnear.
     */
    public static void applyTeleport(ServerPlayer player, double x, double y, double z, float yaw, float pitch) {
        // Sky marker: si Y >= 256 venimos de un RTP — buscar superficie segura.
        if (y >= 256) {
            int safeY = findSafeY(player.serverLevel(), (int) Math.floor(x), (int) Math.floor(z));
            y = safeY + 0.1; // +0.1 para que no quede dentro del bloque
            CretaniaSync.LOGGER.info("[Cretania-Zone] RTP: Y ajustado a {} (top block + 1) para ({},{})",
                    safeY, (int) x, (int) z);
        }
        player.connection.teleport(x, y, z, yaw, pitch);
        CretaniaSync.LOGGER.info("[Cretania-Zone] {} teletransportado a ({},{},{}) en {}",
                player.getGameProfile().getName(), x, y, z,
                player.serverLevel().dimension().location());
    }

    /**
     * Busca el primer bloque no-aire desde arriba (Y=320) y devuelve Y+1 (sobre el bloque).
     * Usa Heightmap del chunk si está cargado, sino carga el chunk forzosamente.
     */
    private static int findSafeY(net.minecraft.server.level.ServerLevel level, int x, int z) {
        // Forzar carga del chunk en (x>>4, z>>4) para que la heightmap esté disponible
        level.getChunkSource().getChunk(x >> 4, z >> 4, true);
        int topY = level.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                x, z);
        // Si el chunk está en void / vacío, fallback a Y=100
        if (topY <= level.getMinBuildHeight()) return 100;
        return topY;
    }
}
