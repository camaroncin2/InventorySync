package com.cretania.velocitysync;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * Plugin principal de Velocity para Cretania.
 * Actúa como semáforo/coordinador de estados entre servidores backend.
 * Escucha el Plugin Messaging Channel "cretania:sync".
 */
@Plugin(
        id = "cretania-velocity-sync",
        name = "Cretania Velocity Sync",
        version = "1.0.0",
        description = "Coordinador de sincronización de datos entre servidores Cretania",
        authors = {"Cretania Team"}
)
public class CretaniaVelocityPlugin {

    public static final MinecraftChannelIdentifier SYNC_CHANNEL =
            MinecraftChannelIdentifier.from("cretania:sync");

    public static final MinecraftChannelIdentifier AUTH_CHANNEL =
            MinecraftChannelIdentifier.from("authmod:check");

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private static CretaniaVelocityPlugin instance;
    private TransferSocketServer transferSocketServer;

    @Inject
    public CretaniaVelocityPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        instance = this;
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        // Registrar los canales de mensajería
        server.getChannelRegistrar().register(SYNC_CHANNEL);
        server.getChannelRegistrar().register(AUTH_CHANNEL);
        logger.info("[Cretania] Canales registrados: cretania:sync, authmod:check");

        InventoryGroupConfig groupConfig = new InventoryGroupConfig(dataDirectory, logger);
        groupConfig.load();

        // AuthProfileListener: inyecta skin premium durante el login (GameProfileRequestEvent)
        AuthProfileListener authProfileListener = new AuthProfileListener(logger);

        // Registrar listeners
        server.getEventManager().register(this, authProfileListener);
        server.getEventManager().register(this, new SyncMessageListener(server, logger, groupConfig, authProfileListener));
        server.getEventManager().register(this, new PlayerConnectionListener(server, logger, groupConfig, authProfileListener));

        // Servidor TCP local para recibir TRANSFER directo desde los backends (sin pasar por el cliente)
        transferSocketServer = new TransferSocketServer(server, logger);
        transferSocketServer.start();

        logger.info("[Cretania] Plugin de Velocity inicializado correctamente.");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (transferSocketServer != null) {
            transferSocketServer.stop();
        }
    }

    public static CretaniaVelocityPlugin getInstance() {
        return instance;
    }
}
