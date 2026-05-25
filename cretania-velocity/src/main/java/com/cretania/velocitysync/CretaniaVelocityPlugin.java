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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    /** @deprecated Velocity ya no envía skins. Mantenido para no romper imports externos. */
    @Deprecated
    public static final Set<UUID> SKIN_SENT_VIA_READY = ConcurrentHashMap.newKeySet();

    /** @deprecated Velocity ya no envía auth. Mantenido para no romper imports externos. */
    @Deprecated
    public static final Set<UUID> AUTH_SENT_VIA_READY = ConcurrentHashMap.newKeySet();

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private static CretaniaVelocityPlugin instance;
    private TransferSocketServer transferSocketServer;
    private WhitelistManager whitelistManager;

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

        // Velocity ya NO inyecta skin ni verifica premium — esa lógica vive en los mods
        // backend (authmod en lobby para auth, cretaniasync en cada backend para skin).
        // Velocity solo hace plugin-message bridge + TCP TRANSFER socket (zonas) + LOAD/SKIP
        // de inventario por scope. AuthProfileListener/MojangAPI quedan como dead code.
        SyncMessageListener syncMessageListener = new SyncMessageListener(server, logger, groupConfig);
        server.getEventManager().register(this, syncMessageListener);
        server.getEventManager().register(this, new PlayerConnectionListener(server, logger, groupConfig));

        // Servidor TCP local para TRANSFER (zonas) + PLAYER_READY + SAVE_COMPLETE
        transferSocketServer = new TransferSocketServer(server, logger, syncMessageListener);
        transferSocketServer.start();

        // Whitelist por NOMBRE (case-insensitive) — pensada para cracked/mixto.
        // Bloquea en PreLoginEvent → kick antes de tocar backends. Comandos: /cwhitelist /cwl
        whitelistManager = new WhitelistManager(dataDirectory, logger);
        whitelistManager.load();
        server.getEventManager().register(this, new WhitelistLoginListener(whitelistManager, logger));
        var cmdManager = server.getCommandManager();
        var meta = cmdManager.metaBuilder("cwhitelist")
                .aliases("cwl")
                .plugin(this)
                .build();
        cmdManager.register(meta, new WhitelistCommand(whitelistManager));

        logger.info("[Cretania] Plugin de Velocity inicializado (modo bridge — sin auth/skin).");
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
