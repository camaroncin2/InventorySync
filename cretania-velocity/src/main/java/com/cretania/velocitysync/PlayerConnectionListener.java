package com.cretania.velocitysync;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.TimeUnit;

public class PlayerConnectionListener {
    private static final String MSG_LOAD_READY = "LOAD_READY";
    private static final String MSG_LOAD_SKIP = "LOAD_SKIP";
    private static final String MSG_SEPARATOR = ":";

    private final Logger logger;
    private final InventoryGroupConfig groupConfig;
    private final ProxyServer proxy;

    public PlayerConnectionListener(ProxyServer proxy, Logger logger, InventoryGroupConfig groupConfig) {
        this.proxy = proxy;
        this.logger = logger;
        this.groupConfig = groupConfig;
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        String targetServerName = event.getServer().getServerInfo().getName();

        if (event.getPreviousServer().isPresent()) {
            String previousServerName = event.getPreviousServer().get().getServerInfo().getName();
            if (groupConfig.shouldTransfer(previousServerName, targetServerName)) {
                String targetGroup = groupConfig.groupForServer(targetServerName);
                PlayerSyncState.markSaving(player.getUniqueId(), previousServerName, targetServerName, targetGroup);
                logger.info("[Cretania] {} cambia {} -> {} dentro del grupo '{}'. Esperando guardado...",
                        player.getUsername(), previousServerName, targetServerName, targetGroup);
            } else {
                PlayerSyncState.clear(player.getUniqueId());
                sendLoadDecision(event.getServer(), player, targetServerName);
                logger.info("[Cretania] {} cambia {} -> {} sin transferencia de inventario.",
                        player.getUsername(), previousServerName, targetServerName);
            }
        } else {
            PlayerSyncState.clear(player.getUniqueId());
            sendLoadDecision(event.getServer(), player, targetServerName);
            logger.info("[Cretania] {} entra a {} con grupo '{}'.",
                    player.getUsername(), targetServerName, groupConfig.groupForServer(targetServerName));
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        if (PlayerSyncState.isActive(player.getUniqueId())) {
            PlayerSyncState.clear(player.getUniqueId());
            logger.debug("[Cretania] Estado de sincronizacion limpiado para: {}", player.getUsername());
        }
    }

    private void sendLoadDecision(RegisteredServer targetServer, Player player, String targetServerName) {
        String targetGroup = groupConfig.groupForServer(targetServerName);
        String message = targetGroup.isBlank()
                ? MSG_LOAD_SKIP + MSG_SEPARATOR + player.getUniqueId() + MSG_SEPARATOR + "server_not_synced"
                : MSG_LOAD_READY + MSG_SEPARATOR + player.getUniqueId() + MSG_SEPARATOR + targetGroup;
        byte[] data = encodeNeoForgePayload(message);
        targetServer.sendPluginMessage(CretaniaVelocityPlugin.SYNC_CHANNEL, data);

        proxy.getScheduler()
                .buildTask(CretaniaVelocityPlugin.getInstance(), () -> player.getCurrentServer().ifPresentOrElse(
                        current -> sendToCurrentServerIfTarget(current, targetServerName, data, player),
                        () -> logger.warn("[Cretania] No se pudo enviar decision de carga a {}: no hay server actual.",
                                player.getUsername())
                ))
                .delay(500, TimeUnit.MILLISECONDS)
                .schedule();
    }

    private void sendToCurrentServerIfTarget(ServerConnection current, String targetServerName, byte[] data, Player player) {
        String currentName = current.getServerInfo().getName();
        if (!currentName.equals(targetServerName)) {
            logger.warn("[Cretania] Decision de carga no enviada a {}: destino esperado {}, actual {}.",
                    player.getUsername(), targetServerName, currentName);
            return;
        }

        current.sendPluginMessage(CretaniaVelocityPlugin.SYNC_CHANNEL, data);
    }

    private byte[] encodeNeoForgePayload(String message) {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream(bytes.length + 5);
        writeVarInt(output, bytes.length);
        output.writeBytes(bytes);
        return output.toByteArray();
    }

    private void writeVarInt(ByteArrayOutputStream output, int value) {
        while ((value & -128) != 0) {
            output.write(value & 127 | 128);
            value >>>= 7;
        }
        output.write(value);
    }
}
