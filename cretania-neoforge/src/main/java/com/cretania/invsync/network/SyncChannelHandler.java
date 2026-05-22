package com.cretania.invsync.network;

import com.cretania.invsync.CretaniaSync;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

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
        String[] parts = message.split(MSG_SEPARATOR, 3);

        if (parts.length < 2) {
            CretaniaSync.LOGGER.warn("[Cretania] Mensaje malformado recibido: {}", message);
            return;
        }

        String type = parts[0];
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
                default -> CretaniaSync.LOGGER.warn("[Cretania] Tipo de mensaje desconocido: {}", type);
            }
        } catch (IllegalArgumentException e) {
            CretaniaSync.LOGGER.warn("[Cretania] UUID invalido en mensaje '{}': {}", message, e.getMessage());
        }
    }
}
