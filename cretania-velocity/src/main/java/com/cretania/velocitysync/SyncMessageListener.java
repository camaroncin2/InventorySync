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
        if (!event.getIdentifier().equals(CretaniaVelocityPlugin.SYNC_CHANNEL)) return;

        if (!(event.getSource() instanceof ServerConnection source)) {
            logger.warn("[Cretania] Mensaje rechazado: origen no es una conexion de servidor.");
            return;
        }

        event.setResult(PluginMessageEvent.ForwardResult.handled());
        String message = decodeMessage(event.getData());
        handleMessage(message, source);
    }

    private void handleMessage(String message, ServerConnection source) {
        String[] parts = message.split(MSG_SEPARATOR, 4);
        if (parts.length < 2) {
            logger.warn("[Cretania] Mensaje malformado: {}", message);
            return;
        }

        if (MSG_SAVE_COMPLETE.equals(parts[0])) {
            handleSaveComplete(parts[1], parts.length > 2 ? parts[2] : "unknown", parts.length > 3 ? parts[3] : "", source);
        } else {
            logger.warn("[Cretania] Tipo de mensaje desconocido: {}", parts[0]);
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
