package com.cretania.invsync;

import com.cretania.invsync.config.SyncConfig;
import com.cretania.invsync.database.DatabaseManager;
import com.cretania.invsync.logic.SyncStateManager;
import com.cretania.invsync.network.SkinClientPayload;
import com.cretania.invsync.network.SyncChannelHandler;
import com.cretania.invsync.network.SyncPayload;
import com.cretania.invsync.zone.PortalManager;
import com.cretania.invsync.zone.ReturnZoneManager;
import com.cretania.invsync.zone.RtpManager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.minecraft.server.MinecraftServer;
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
    private MinecraftServer server;

    public CretaniaSync(IEventBus modEventBus, ModContainer modContainer) {
        instance = this;
        LOGGER.info("[Cretania] Inicializando sistema de sincronización Server-Side...");

        // Registrar configuración del servidor (mismo archivo de siempre).
        modContainer.registerConfig(ModConfig.Type.SERVER, SyncConfig.SPEC);

        // Instanciar componentes
        this.databaseManager = new DatabaseManager();
        this.inventorySync = new InventorySync(databaseManager);
        this.syncStateManager = new SyncStateManager();

        // Registrar listeners de eventos del servidor
        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.register(inventorySync);
        NeoForge.EVENT_BUS.register(syncStateManager);
        // Zona de retorno: detecta cuando un jugador sale de la zona de este servidor
        NeoForge.EVENT_BUS.addListener(ReturnZoneManager::onServerTick);
        // Anti-bucle: al login, si cayó dentro de zona trigger → TP a spawn safe
        NeoForge.EVENT_BUS.addListener(ReturnZoneManager::onPlayerLogin);
        // Portales físicos (bloques nether_portal) → transferencia entre servers
        NeoForge.EVENT_BUS.addListener(PortalManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(PortalManager::onDimensionTravel);
        NeoForge.EVENT_BUS.addListener(PortalManager::onRegisterCommands);
        // Portales RTP (bloques end_portal) → teletransporte aleatorio en el mismo mundo
        NeoForge.EVENT_BUS.addListener(RtpManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(RtpManager::onDimensionTravel);
        NeoForge.EVENT_BUS.addListener(RtpManager::onRegisterCommands);
        // HIGHEST: debe devolver la gravedad ANTES de que InventorySync serialice el NBT.
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, RtpManager::onPlayerLogOut);

        // Registrar payload de red en el mod event bus
        modEventBus.addListener(this::onRegisterPayloads);
    }

    /**
     * Registra el payload personalizado para el canal cretania:sync.
     * En servidor dedicado también registra cretania:client_skin (S2C)
     * para que el mod cliente pueda aplicar skins premium sin entity-respawn.
     */
    private void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").optional();
        registrar.playToServer(
                SyncPayload.TYPE,
                SyncPayload.STREAM_CODEC,
                (payload, context) -> {
                    String msg = new String(payload.data(), java.nio.charset.StandardCharsets.UTF_8);
                    if (msg.startsWith("C2S_SKIN:")) {
                        // Skin enviada por el cliente directamente — aplicar sin esperar Velocity
                        context.enqueueWork(() ->
                                SyncChannelHandler.handleClientSkinMessage(
                                        payload.data(),
                                        (net.minecraft.server.level.ServerPlayer) context.player()
                                )
                        );
                    } else {
                        SyncChannelHandler.handleIncomingMessage(payload.data());
                    }
                }
        );
        // S2C: solo en servidor dedicado para evitar conflicto con cretania-client en singleplayer
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
            registrar.playToClient(
                    SkinClientPayload.TYPE,
                    SkinClientPayload.STREAM_CODEC,
                    (payload, context) -> {} // el servidor solo envía, nunca recibe este payload
            );
        }
    }

    /**
     * Al iniciar el servidor: conectar a MongoDB.
     */
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        dedicatedServer = event.getServer().isDedicatedServer();
        this.server = event.getServer();
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

        // Inicializar la zona de retorno (si hay config)
        ReturnZoneManager.init(event.getServer());
        // Inicializar portales físicos (config/invsync-portals.toml)
        PortalManager.init(event.getServer());
        // Inicializar portales RTP del End (config/invsync-rtp.toml)
        RtpManager.init(event.getServer());
    }

    /**
     * Al detener el servidor: guardar a TODOS los jugadores conectados, bloqueando hasta
     * que las escrituras terminen.
     *
     * CRÍTICO — aquí NO se puede cerrar Mongo. Este evento se dispara ANTES de
     * MinecraftServer#stopServer(), que es donde se expulsa a los jugadores y se disparan
     * los PlayerLoggedOutEvent que guardan el inventario. Cerrar la base aquí (como se
     * hacía antes) dejaba el executor apagado, los guardados del logout fallaban en
     * silencio y CADA reinicio limpio perdía el progreso de todos los conectados.
     * El cierre real ocurre en onServerStopped(), ya sin nada pendiente.
     */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (!dedicatedServer) return;
        inventorySync.saveAllOnline("apagado del servidor", true);
    }

    /**
     * Ya expulsados y guardados todos los jugadores: recién aquí se cierra la conexión.
     */
    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        databaseManager.shutdown();
        LOGGER.info("[Cretania] Sistema de sincronización detenido.");
    }

    public static CretaniaSync getInstance() {
        return instance;
    }

    public MinecraftServer getServer() {
        return server;
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
