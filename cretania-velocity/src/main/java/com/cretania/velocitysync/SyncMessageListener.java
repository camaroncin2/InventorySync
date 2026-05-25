package com.cretania.velocitysync;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

public class SyncMessageListener {

    private static final String MSG_SAVE_COMPLETE = "SAVE_COMPLETE";
    private static final String MSG_LOAD_READY = "LOAD_READY";
    private static final String MSG_LOAD_SKIP = "LOAD_SKIP";
    private static final String MSG_SEPARATOR = ":";

    private final ProxyServer proxy;
    private final Logger logger;
    private final InventoryGroupConfig groupConfig;

    public SyncMessageListener(ProxyServer proxy, Logger logger, InventoryGroupConfig groupConfig) {
        this.proxy = proxy;
        this.logger = logger;
        this.groupConfig = groupConfig;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!(event.getSource() instanceof ServerConnection source)) {
            return; // sólo mensajes provenientes de backends
        }

        if (event.getIdentifier().equals(CretaniaVelocityPlugin.SYNC_CHANNEL)) {
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            String message = decodeMessage(event.getData());
            handleMessage(message, source);
        } else if (event.getIdentifier().equals(CretaniaVelocityPlugin.AUTH_CHANNEL)) {
            // authmod:check: se deja pasar al cliente (forward por defecto).
            // Velocity solo procesa CRACKED_SKIN y TRANSFER internamente.
            String message = decodeMessage(event.getData());
            handleAuthChannelMessage(message);
        }
    }

    private void handleMessage(String message, ServerConnection source) {
        String[] parts = message.split(MSG_SEPARATOR, 4);
        if (parts.length < 2) {
            logger.warn("[Cretania] Mensaje malformado: {}", message);
            return;
        }

        if (MSG_SAVE_COMPLETE.equals(parts[0])) {
            handleSaveComplete(parts[1], parts.length > 2 ? parts[2] : "unknown", parts.length > 3 ? parts[3] : "", source);
        } else if ("PLAYER_READY".equals(parts[0])) {
            handlePlayerReady(parts[1], source.getServerInfo().getName(), source);
        } else if ("UPLOAD_SKIN".equals(parts[0])) {
            // Velocity ya no cachea skins — se ignora, se mantiene como no-op para compat.
            logger.debug("[Cretania] UPLOAD_SKIN recibido pero ignorado (Velocity ya no gestiona skins).");
        } else {
            logger.warn("[Cretania] Tipo de mensaje desconocido: {}", parts[0]);
        }
    }

    /**
     * Mensajes del canal authmod:check provenientes del backend.
     * Velocity solo procesa TRANSFER (necesario para zonas). CHALLENGE, CRACKED_SKIN
     * y demás pasan transparentes al cliente; cretaniasync/authmod los gestionan
     * en backend o cliente. Logueamos en debug para no spammear el log.
     */
    private void handleAuthChannelMessage(String message) {
        if (message.startsWith("CHALLENGE:") || message.startsWith("CRACKED_SKIN:")) {
            return; // pasan transparente al cliente, no nos interesa procesar
        }
        if (message.startsWith("TRANSFER:")) {
            // TRANSFER:<uuid>:<serverName>[:<x>:<y>:<z>:<yaw>:<pitch>]
            String[] parts = message.split(":", 8);
            if (parts.length >= 3) {
                String uuidStr    = parts[1];
                String serverName = parts[2];
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    if (parts.length == 8) {
                        double x = Double.parseDouble(parts[3]);
                        double y = Double.parseDouble(parts[4]);
                        double z = Double.parseDouble(parts[5]);
                        float yaw = Float.parseFloat(parts[6]);
                        float pitch = Float.parseFloat(parts[7]);
                        TransferSocketServer.PENDING_POSITIONS.put(uuid,
                                new TransferSocketServer.PositionData(serverName, x, y, z, yaw, pitch));
                    }
                    proxy.getPlayer(uuid).ifPresent(player ->
                            proxy.getServer(serverName).ifPresentOrElse(
                                    server -> {
                                        player.createConnectionRequest(server).connect()
                                                .whenComplete((result, ex) -> {
                                                    if (ex != null) {
                                                        logger.warn("[Cretania-Zone] Error al transferir {} → {}: {}",
                                                                player.getUsername(), serverName, ex.getMessage());
                                                    } else {
                                                        logger.info("[Cretania-Zone] {} transferido a {}",
                                                                player.getUsername(), serverName);
                                                    }
                                                });
                                    },
                                    () -> logger.warn("[Cretania-Zone] Servidor '{}' no encontrado en Velocity (TRANSFER ignorado)", serverName)
                            )
                    );
                } catch (IllegalArgumentException e) {
                    logger.warn("[Cretania-Zone] UUID invalido en TRANSFER: {}", uuidStr);
                }
            }
        } else {
            logger.debug("[Cretania] authmod:check no procesado por Velocity (pasa al cliente): {}",
                    message.length() > 80 ? message.substring(0, 80) + "..." : message);
        }
    }

    private void handleSaveComplete(String uuidStr, String playerName, String savedScope, ServerConnection source) {
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            logger.warn("[Cretania] UUID invalido en SAVE_COMPLETE: {}", uuidStr);
            return;
        }

        String sourceServerName = source.getServerInfo().getName();
        String expectedOrigin = PlayerSyncState.getOriginServer(uuid);
        if (expectedOrigin != null && !expectedOrigin.equals(sourceServerName)) {
            logger.warn("[Cretania] SAVE_COMPLETE de {} ignorado: origen esperado {}, recibido {}.",
                    playerName, expectedOrigin, sourceServerName);
            return;
        }

        PlayerSyncState.markReady(uuid);
        logger.info("[Cretania] Guardado completado para {} ({}) scope='{}'.", playerName, uuid, savedScope);

        Optional<Player> optionalPlayer = proxy.getPlayer(uuid);
        if (optionalPlayer.isEmpty()) {
            PlayerSyncState.clear(uuid);
            return;
        }

        Player player = optionalPlayer.get();
        Optional<ServerConnection> currentServer = player.getCurrentServer();
        if (currentServer.isEmpty()) {
            PlayerSyncState.clear(uuid);
            return;
        }

        String currentServerName = currentServer.get().getServerInfo().getName();
        String expectedTarget = PlayerSyncState.getTargetServer(uuid);
        String expectedGroup = PlayerSyncState.getTargetGroup(uuid);
        String currentGroup = groupConfig.groupForServer(currentServerName);

        if (expectedTarget != null && !expectedTarget.equals(currentServerName)) {
            logger.warn("[Cretania] SAVE_COMPLETE de {} ignorado: destino esperado {}, actual {}.",
                    playerName, expectedTarget, currentServerName);
            PlayerSyncState.clear(uuid);
            return;
        }

        if (!currentGroup.isBlank() && currentGroup.equals(savedScope) && currentGroup.equals(expectedGroup)) {
            sendMessage(currentServer.get(), MSG_LOAD_READY + MSG_SEPARATOR + uuid + MSG_SEPARATOR + currentGroup);
            logger.info("[Cretania] LOAD_READY enviado a {} para scope '{}'.", playerName, currentGroup);
        } else {
            sendMessage(currentServer.get(), MSG_LOAD_SKIP + MSG_SEPARATOR + uuid + MSG_SEPARATOR + "scope_mismatch");
            logger.info("[Cretania] LOAD_SKIP enviado a {}. currentGroup='{}', savedScope='{}', expectedGroup='{}'.",
                    playerName, currentGroup, savedScope, expectedGroup);
        }

        PlayerSyncState.clear(uuid);
    }

    /**
     * Punto de entrada desde TCP socket (TransferSocketServer) para PLAYER_READY.
     * Busca la conexión actual del jugador en el proxy y delega a handlePlayerReady.
     */
    void handlePlayerReadyFromSocket(String uuidStr) {
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            logger.warn("[Cretania-Fast] UUID inválido en PLAYER_READY (socket): {}", uuidStr);
            return;
        }
        proxy.getPlayer(uuid).ifPresentOrElse(
                player -> player.getCurrentServer().ifPresentOrElse(
                        source -> handlePlayerReady(uuidStr, source.getServerInfo().getName(), source),
                        () -> logger.warn("[Cretania-Fast] PLAYER_READY socket: {} sin servidor actual", uuidStr)
                ),
                () -> logger.warn("[Cretania-Fast] PLAYER_READY socket: jugador {} no encontrado en proxy", uuidStr)
        );
    }

    /**
     * @deprecated Velocity ya no cachea skins. No-op para compat con TransferSocketServer.
     * El backend ahora hace el broadcast directamente al cliente vía cretaniasync.
     */
    @Deprecated
    void handleUploadSkinFromSocket(String uuidStr, String value, String sig) {
        logger.debug("[Cretania] UPLOAD_SKIN socket ignorado (Velocity no gestiona skins).");
    }

    /**
     * Punto de entrada desde TCP socket para SAVE_COMPLETE.
     * El nombre del servidor origen viene incluido en el mensaje de socket.
     */
    void handleSaveCompleteFromSocket(String uuidStr, String playerName, String savedScope, String sourceServerName) {
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            logger.warn("[Cretania] UUID inválido en SAVE_COMPLETE (socket): {}", uuidStr);
            return;
        }

        String expectedOrigin = PlayerSyncState.getOriginServer(uuid);
        if (expectedOrigin != null && !expectedOrigin.equals(sourceServerName)) {
            logger.warn("[Cretania] SAVE_COMPLETE socket de {} ignorado: origen esperado {}, recibido {}.",
                    playerName, expectedOrigin, sourceServerName);
            return;
        }

        PlayerSyncState.markReady(uuid);
        logger.info("[Cretania] Guardado completado (socket) para {} ({}) scope='{}'.", playerName, uuid, savedScope);

        Optional<Player> optionalPlayer = proxy.getPlayer(uuid);
        if (optionalPlayer.isEmpty()) {
            PlayerSyncState.clear(uuid);
            return;
        }

        Player player = optionalPlayer.get();
        Optional<ServerConnection> currentServer = player.getCurrentServer();
        if (currentServer.isEmpty()) {
            PlayerSyncState.clear(uuid);
            return;
        }

        String currentServerName = currentServer.get().getServerInfo().getName();
        String expectedTarget = PlayerSyncState.getTargetServer(uuid);
        String expectedGroup = PlayerSyncState.getTargetGroup(uuid);
        String currentGroup = groupConfig.groupForServer(currentServerName);

        if (expectedTarget != null && !expectedTarget.equals(currentServerName)) {
            logger.warn("[Cretania] SAVE_COMPLETE socket de {} ignorado: destino esperado {}, actual {}.",
                    playerName, expectedTarget, currentServerName);
            PlayerSyncState.clear(uuid);
            return;
        }

        if (!currentGroup.isBlank() && currentGroup.equals(savedScope) && currentGroup.equals(expectedGroup)) {
            sendMessage(currentServer.get(), MSG_LOAD_READY + MSG_SEPARATOR + uuid + MSG_SEPARATOR + currentGroup);
            logger.info("[Cretania] LOAD_READY (socket) enviado a {} para scope '{}'.", playerName, currentGroup);
        } else {
            sendMessage(currentServer.get(), MSG_LOAD_SKIP + MSG_SEPARATOR + uuid + MSG_SEPARATOR + "scope_mismatch");
            logger.info("[Cretania] LOAD_SKIP (socket) enviado a {}. currentGroup='{}', savedScope='{}', expectedGroup='{}'.",
                    playerName, currentGroup, savedScope, expectedGroup);
        }

        PlayerSyncState.clear(uuid);
    }

    /**
     * PLAYER_READY desde el backend: cretaniasync notifica que el jugador ya está
     * cargado en el mundo. Velocity ya no envía skin en respuesta — el cliente
     * envía su skin via C2S_SKIN al backend, y cretaniasync hace el broadcast.
     */
    private void handlePlayerReady(String uuidStr, String serverName, ServerConnection source) {
        try {
            UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            logger.warn("[Cretania-Fast] UUID inválido en PLAYER_READY: {}", uuidStr);
            return;
        }
        logger.debug("[Cretania-Fast] PLAYER_READY recibido de {} para {}", serverName, uuidStr);
    }

    private void sendMessage(ServerConnection connection, String message) {
        connection.sendPluginMessage(CretaniaVelocityPlugin.SYNC_CHANNEL, encodeNeoForgePayload(message));
    }

    private String decodeMessage(byte[] data) {
        DecodedVarInt decoded = readVarInt(data);
        if (decoded != null && decoded.value >= 0 && decoded.value <= data.length - decoded.bytesRead) {
            byte[] payload = Arrays.copyOfRange(data, decoded.bytesRead, decoded.bytesRead + decoded.value);
            return new String(payload, StandardCharsets.UTF_8);
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    private byte[] encodeNeoForgePayload(String message) {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        byte[] output = new byte[bytes.length + varIntSize(bytes.length)];
        int index = writeVarInt(output, 0, bytes.length);
        System.arraycopy(bytes, 0, output, index, bytes.length);
        return output;
    }

    private int writeVarInt(byte[] output, int index, int value) {
        while ((value & -128) != 0) {
            output[index++] = (byte) (value & 127 | 128);
            value >>>= 7;
        }
        output[index++] = (byte) value;
        return index;
    }

    private int varIntSize(int value) {
        int size = 1;
        while ((value & -128) != 0) {
            size++;
            value >>>= 7;
        }
        return size;
    }

    private DecodedVarInt readVarInt(byte[] data) {
        int value = 0;
        int position = 0;
        for (int i = 0; i < Math.min(5, data.length); i++) {
            int current = data[i] & 0xFF;
            value |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) {
                return new DecodedVarInt(value, i + 1);
            }
            position += 7;
        }
        return null;
    }

    private record DecodedVarInt(int value, int bytesRead) {
    }
}
