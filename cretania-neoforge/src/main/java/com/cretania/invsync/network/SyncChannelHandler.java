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

    public static void sendSaveComplete(ServerPlayer player, String scope) {
        String message = MSG_SAVE_COMPLETE + MSG_SEPARATOR
                + player.getUUID() + MSG_SEPARATOR
                + player.getGameProfile().getName() + MSG_SEPARATOR
                + scope;

        player.connection.send(
                new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                        new SyncPayload(message.getBytes(StandardCharsets.UTF_8))
                )
        );

        CretaniaSync.LOGGER.debug("[Cretania] SAVE_COMPLETE enviado para {} scope={}",
                player.getGameProfile().getName(), scope);
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
     * Velocity responde inmediatamente con skin + auth (sin delays fijos).
     */
    public static void sendPlayerReady(ServerPlayer player) {
        String message = "PLAYER_READY:" + player.getUUID();
        player.connection.send(
                new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                        new SyncPayload(message.getBytes(StandardCharsets.UTF_8))
                )
        );
        CretaniaSync.LOGGER.info("[Cretania-Fast] PLAYER_READY enviado para {}",
                player.getGameProfile().getName());
    }

    /**
     * Teletransporta al jugador a la posición preservada desde el servidor origen.
     * Usar en el hilo principal del servidor (game thread).
     */
    public static void applyTeleport(ServerPlayer player, double x, double y, double z, float yaw, float pitch) {
        player.connection.teleport(x, y, z, yaw, pitch);
        CretaniaSync.LOGGER.info("[Cretania-Zone] {} teletransportado a ({},{},{}) en {}",
                player.getGameProfile().getName(), x, y, z,
                player.serverLevel().dimension().location());
    }
}
