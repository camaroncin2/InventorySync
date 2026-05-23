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
    private final AuthProfileListener authProfileListener;

    public SyncMessageListener(ProxyServer proxy, Logger logger, InventoryGroupConfig groupConfig,
                               AuthProfileListener authProfileListener) {
        this.proxy = proxy;
        this.logger = logger;
        this.groupConfig = groupConfig;
        this.authProfileListener = authProfileListener;
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
            // Canal authmod:check: el lobby notifica skins cracked (CRACKED_SKIN)
            event.setResult(PluginMessageEvent.ForwardResult.handled());
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
        } else {
            logger.warn("[Cretania] Tipo de mensaje desconocido: {}", parts[0]);
        }
    }

    /**
     * Maneja mensajes del canal authmod:check provenientes del backend (lobby).
     * Protocolos soportados:
     *   CRACKED_SKIN:<uuid>:<value>:<signature>
     *   TRANSFER:<uuid>:<serverName>
     */
    private void handleAuthChannelMessage(String message) {
        if (message.startsWith("CRACKED_SKIN:")) {
            // value/sig pueden contener ':' internamente → split límite 4
            String[] parts = message.split(":", 4);
            if (parts.length == 4) {
                String uuidStr   = parts[1];
                String value     = parts[2];
                String signature = parts[3];
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    proxy.getPlayer(uuid).ifPresent(player -> {
                        authProfileListener.setCrackedSkin(
                                player.getUsername(),
                                new MojangAPI.SkinProperty(value, signature)
                        );
                        logger.info("[Cretania-Skin] CRACKED_SKIN recibida y cacheada para {}", player.getUsername());
                    });
                } catch (IllegalArgumentException e) {
                    logger.warn("[Cretania-Skin] UUID invalido en CRACKED_SKIN: {}", uuidStr);
                }
            }
        } else if (message.startsWith("TRANSFER:")) {
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
            logger.warn("[Cretania] Mensaje authmod:check desconocido desde backend: {}", message);
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
     * Responde inmediatamente al PLAYER_READY del backend enviando skin + auth sin delays.
     * El backend dispara PLAYER_READY en PlayerLoggedInEvent — jugador ya registrado.
     */
    private void handlePlayerReady(String uuidStr, String serverName, ServerConnection source) {
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            logger.warn("[Cretania-Fast] UUID inválido en PLAYER_READY: {}", uuidStr);
            return;
        }
        proxy.getPlayer(uuid).ifPresent(player -> {
            // 1. Enviar skin inmediatamente (cualquier servidor)
            MojangAPI.SkinProperty skin = authProfileListener.getSkin(player.getUsername());
            if (skin == null) skin = authProfileListener.getCrackedSkin(player.getUsername());
            if (skin != null) {
                String skinMsg = "SKIN:" + uuid + ":" + skin.value() + ":" + skin.signature();
                sendMessage(source, skinMsg);
                logger.info("[Cretania-Fast] SKIN enviada inmediatamente a {} para {}", serverName, player.getUsername());
            } else {
                logger.warn("[Cretania-Fast] PLAYER_READY de {} en {} sin skin en caché", player.getUsername(), serverName);
            }
            // 2. Enviar auth al lobby inmediatamente
            if ("lobby".equals(serverName) && authProfileListener.hasCachedResult(player.getUsername())) {
                boolean premium = authProfileListener.isPremium(player.getUsername());
                String authMsg = (premium ? "PREMIUM:" : "CRACKED:") + uuid;
                source.sendPluginMessage(CretaniaVelocityPlugin.AUTH_CHANNEL, encodeNeoForgePayload(authMsg));
                logger.info("[Cretania-Fast] Auth ({}) enviada al lobby para {}",
                        premium ? "PREMIUM" : "CRACKED", player.getUsername());
            }
        });
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
