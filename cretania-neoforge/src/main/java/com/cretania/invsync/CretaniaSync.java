package com.cretania.invsync;

import com.cretania.invsync.config.SyncConfig;
import com.cretania.invsync.database.DatabaseManager;
import com.cretania.invsync.logic.SyncStateManager;
import com.cretania.invsync.network.SyncChannelHandler;
import com.cretania.invsync.network.SyncPayload;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Clase principal del mod Cretania Sync (NeoForge).
 * Se encarga de inicializar la conexión a MongoDB, registrar los listeners de eventos
 * y el canal de red "cretania:sync".
 */
@Mod(CretaniaSync.MOD_ID)
public class CretaniaSync {

    public static final String MOD_ID = "cretaniasync";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static CretaniaSync instance;
    private final DatabaseManager databaseManager;
    private final InventorySync inventorySync;
    private final SyncStateManager syncStateManager;
    private boolean dedicatedServer = false;

    public CretaniaSync(IEventBus modEventBus, ModContainer modContainer) {
        instance = this;
        LOGGER.info("[Cretania] Inicializando sistema de sincronización Server-Side...");

        // Registrar configuración del servidor
        modContainer.registerConfig(ModConfig.Type.SERVER, SyncConfig.SPEC);

        // Instanciar componentes
        this.databaseManager = new DatabaseManager();
        this.inventorySync = new InventorySync(databaseManager);
        this.syncStateManager = new SyncStateManager();

        // Registrar listeners de eventos del servidor
        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.register(inventorySync);
        NeoForge.EVENT_BUS.register(syncStateManager);

        // Registrar payload de red en el mod event bus
        modEventBus.addListener(this::onRegisterPayloads);
    }

    /**
     * Registra el payload personalizado para el canal cretania:sync.
     */
    private void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").optional();
        registrar.playToServer(
                SyncPayload.TYPE,
                SyncPayload.STREAM_CODEC,
                (payload, context) -> SyncChannelHandler.handleIncomingMessage(payload.data())
        );
    }

    /**
     * Al iniciar el servidor: conectar a MongoDB.
     */
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        dedicatedServer = event.getServer().isDedicatedServer();
        if (!dedicatedServer) {
            LOGGER.info("[Cretania] Servidor integrado (singleplayer) detectado. Sincronización desactivada.");
            return;
        }

        SyncConfig config = SyncConfig.INSTANCE;
        try {
            databaseManager.initialize(
                    config.mongoUri.get(),
                    config.mongoDatabase.get()
            );
            LOGGER.info("[Cretania] Conexión a MongoDB establecida correctamente.");
        } catch (Exception e) {
            LOGGER.error("[Cretania] FALLO CRÍTICO: No se pudo conectar a MongoDB: {}", e.getMessage());
            LOGGER.error("[Cretania] El servidor NO sincronizará datos. Revise la configuración.");
        }
    }

    /**
     * Al detener el servidor: cerrar conexión MongoDB.
     */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        databaseManager.shutdown();
        LOGGER.info("[Cretania] Sistema de sincronización detenido.");
    }

    public static CretaniaSync getInstance() {
        return instance;
    }

    public boolean isDedicatedServer() {
        return dedicatedServer;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public InventorySync getInventorySync() {
        return inventorySync;
    }

    public SyncStateManager getSyncStateManager() {
        return syncStateManager;
    }
}
