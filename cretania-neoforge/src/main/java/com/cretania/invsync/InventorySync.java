package com.cretania.invsync;

import com.cretania.invsync.config.SyncConfig;
import com.cretania.invsync.database.DatabaseManager;
import com.cretania.invsync.database.NbtSerializer;
import com.cretania.invsync.logic.SyncStateManager;
import com.cretania.invsync.network.SyncChannelHandler;
import com.cretania.invsync.visual.SyncVisuals;
import com.cretania.invsync.network.SkinClientPayload;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

public class InventorySync {

    private final DatabaseManager databaseManager;
    private final Map<UUID, String> pendingProxyDecision = new ConcurrentHashMap<>();
    /**
     * Jugadores cuya carga desde Mongo falló. Su inventario en memoria NO es el bueno,
     * así que guardarlo sobrescribiría los datos correctos que siguen en la base.
     */
    private final java.util.Set<UUID> loadFailed = ConcurrentHashMap.newKeySet();
    private MinecraftServer server;
    private int autosaveTicker = 0;

    public InventorySync(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    // -------------------------------------------------------------------------
    // Guardado: autosave periódico + guardado total al apagar
    // -------------------------------------------------------------------------

    /**
     * Autoguardado periódico. Es la única protección real contra un crash duro
     * (kill -9, OOM, corte de luz): en esos casos no se dispara ni el logout ni el
     * apagado, así que sin esto se perdería todo lo hecho desde el último login.
     */
    @SubscribeEvent
    public void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        int seconds = SyncConfig.INSTANCE.autosaveSeconds.get();
        if (seconds <= 0) return;
        if (++autosaveTicker < seconds * 20) return;
        autosaveTicker = 0;
        saveAllOnline("autosave", false);
    }

    /**
     * Guarda a todos los jugadores conectados. Con {@code blocking=true} espera a que las
     * escrituras terminen — obligatorio al apagar, porque justo después se cierra Mongo.
     */
    public void saveAllOnline(String reason, boolean blocking) {
        MinecraftServer srv = CretaniaSync.getInstance().getServer();
        if (srv == null || !databaseManager.isConnected()) return;
        String scope = configuredScope();
        if (scope.isBlank()) return; // este server no comparte inventario

        String serverName = configuredServerName();
        List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
        for (ServerPlayer player : srv.getPlayerList().getPlayers()) {
            UUID uuid = player.getUUID();
            // Carga en curso: su inventario todavía no es el suyo definitivo.
            if (SyncStateManager.isLocked(uuid)) continue;
            if (loadFailed.contains(uuid)) continue;
            try {
                futures.add(databaseManager.savePlayerData(uuid, player.getGameProfile().getName(),
                        snapshot(player), serverName, scope));
            } catch (Exception e) {
                CretaniaSync.LOGGER.error("[Cretania] Error serializando a {} durante {}: {}",
                        player.getGameProfile().getName(), reason, e.toString());
            }
        }
        if (futures.isEmpty()) return;

        if (!blocking) {
            CretaniaSync.LOGGER.debug("[Cretania] {} ({} jugadores).", reason, futures.size());
            return;
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(SyncConfig.INSTANCE.syncTimeoutSeconds.get(), TimeUnit.SECONDS);
            CretaniaSync.LOGGER.info("[Cretania] {} inventario(s) guardado(s) ({}).", futures.size(), reason);
        } catch (Exception e) {
            CretaniaSync.LOGGER.error("[Cretania] FALLO guardando inventarios ({}): {}", reason, e.toString());
        }
    }

    /** Serializa el estado actual del jugador. Debe llamarse en el server thread. */
    private String snapshot(ServerPlayer player) {
        CompoundTag tag = new CompoundTag();
        player.saveWithoutId(tag);
        return NbtSerializer.toBase64(tag);
    }

    /**
     * Punto de aparición de un login normal (el jugador NO viene por un portal).
     * Según config: el spawn del mundo (/setworldspawn) o unas coords fijas.
     */
    private void applyLoginSpawn(ServerPlayer player) {
        String name = player.getGameProfile().getName();

        if (SyncConfig.INSTANCE.forcedSpawnUseWorldSpawn.get()) {
            ServerLevel level = player.serverLevel();
            var spawn = level.getSharedSpawnPos();
            SyncChannelHandler.applyTeleport(player,
                    spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                    level.getSharedSpawnAngle(), 0f);
            CretaniaSync.LOGGER.info("[Cretania] Login spawn (spawn del mundo) aplicado a {} → ({},{},{})",
                    name, spawn.getX(), spawn.getY(), spawn.getZ());
            return;
        }

        double fx = SyncConfig.INSTANCE.forcedSpawnX.get();
        double fy = SyncConfig.INSTANCE.forcedSpawnY.get();
        double fz = SyncConfig.INSTANCE.forcedSpawnZ.get();
        float fyaw = SyncConfig.INSTANCE.forcedSpawnYaw.get().floatValue();
        float fpitch = SyncConfig.INSTANCE.forcedSpawnPitch.get().floatValue();
        SyncChannelHandler.applyTeleport(player, fx, fy, fz, fyaw, fpitch);
        CretaniaSync.LOGGER.info("[Cretania] Login spawn (coords fijas) aplicado a {} → ({},{},{}) yaw={} pitch={}",
                name, fx, fy, fz, fyaw, fpitch);
    }

    @SubscribeEvent
    public void onPlayerLogIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!CretaniaSync.getInstance().isDedicatedServer()) return;

        // Notificar a Velocity que el backend tiene al jugador registrado.
        // Velocity responde inmediatamente con skin + auth — sin delays fijos.
        SyncChannelHandler.sendPlayerReady(player);

        // "Catch-up": applyPlayerSkin() solo avisa a quienes YA estaban online en el
        // momento del cambio. Un jugador que se conecta DESPUÉS de que otros ya tienen
        // su skin aplicada nunca recibe ese aviso — quedaba dependiendo únicamente del
        // paquete estándar de Minecraft (que puede no traer la firma a tiempo). Por eso
        // le mandamos ahora el SkinClientPayload de cada jugador ya conectado.
        sendExistingSkinsTo(player);

        if (!databaseManager.isConnected()) {
            handleDatabaseUnavailable(player);
            return;
        }

        this.server = player.getServer();

        // Cargar skin desde MongoDB si está cacheada — instantáneo, sin fetch a Mojang.
        // CRÍTICO: findSkinSync DEBE ejecutarse en hilo separado. Si lo llamamos en el
        // server thread y MongoDB tarda (red lenta, query lenta), el server queda
        // bloqueado, Velocity hace timeout (~30s) y kickea a TODOS los jugadores
        // intentando conectar. Aplicamos la skin en server thread vía server.execute().
        UUID skinUuid = player.getUUID();
        String skinPlayerName = player.getGameProfile().getName();
        CompletableFuture.runAsync(() -> {
            try {
                databaseManager.findSkinSync(skinUuid).ifPresent(skin ->
                        server.execute(() -> {
                            CretaniaSync.LOGGER.info("[Cretania-Skin] Skin de {} cargada desde MongoDB (type={}) — aplicando",
                                    skinPlayerName, skin.type());
                            // persistToMongo=false: ya viene de Mongo, no re-escribir
                            applyPlayerSkin(skinUuid, skin.value(), skin.signature(), false);
                        }));
            } catch (Exception e) {
                CretaniaSync.LOGGER.warn("[Cretania-Skin] Error consultando skin de Mongo para {}: {}",
                        skinPlayerName, e.getMessage());
            }
        });

        // Posición al entrar, por prioridad:
        //  1. Por portal: el server de origen manda las coords vía SET_POSITION. Velocity las
        //     envía ~100 ms después del login (normalmente aún no llegaron aquí); cuando llegan
        //     se aplican encima. El jugador sigue en la pantalla de carga, no se ve el salto.
        //  2. restoreLastPositionOnLogin (survival): la última posición guardada. Se maneja en
        //     applyPlayerData porque necesita el documento de Mongo, que carga async.
        //  3. forced_spawn (lobby): spawn del mundo o coords fijas.
        //  4. Nada → se queda donde vanilla lo puso (su playerdata local).
        //
        // ANTES forced_spawn borraba el SET_POSITION pendiente y se imponía siempre: por eso
        // las llegadas por portal terminaban en el punto de login en vez de en el del portal.
        double[] pendingPos = SyncChannelHandler.PENDING_TELEPORTS.remove(player.getUUID());
        if (pendingPos != null) {
            SyncChannelHandler.markPortalArrival(player.getUUID());
            SyncChannelHandler.applyTeleport(player,
                    pendingPos[0], pendingPos[1], pendingPos[2],
                    (float) pendingPos[3], (float) pendingPos[4]);
        } else if (!SyncConfig.INSTANCE.restoreLastPositionOnLogin.get()
                && SyncConfig.INSTANCE.forcedSpawnEnabled.get()) {
            applyLoginSpawn(player);
        }

        String scope = configuredScope();
        if (scope.isBlank()) {
            CretaniaSync.LOGGER.info("[Cretania] Servidor sin inventoryScope; no se carga inventario compartido para {}.",
                    player.getGameProfile().getName());
            clearInventoryIfConfigured(player, "server_not_synced");
            return;
        }

        UUID uuid = player.getUUID();
        // GUARD: si ya hay una carga en curso para este jugador, ignorar evento duplicado
        if (SyncStateManager.isLocked(uuid)) {
            CretaniaSync.LOGGER.warn("[Cretania] onPlayerLogIn duplicado para {} — ignorado", player.getName().getString());
            return;
        }
        SyncStateManager.lock(uuid);
        player.displayClientMessage(net.minecraft.network.chat.Component.literal("§eCargando tu inventario..."), true);
        // Cargamos directamente desde MongoDB sin esperar al coordinador Velocity.
        loadPlayerFromDatabase(player, scope);
    }

    /**
     * Manda al jugador recién conectado el SkinClientPayload de cada jugador que ya
     * estaba online, usando su GameProfile actual (ya tiene la textura correcta,
     * venga de Mojang o de MongoDB). Sin esto, el recién llegado depende únicamente
     * del paquete estándar de player-info, cuyo resultado queda memoizado para
     * siempre en el cliente si no llega la firma a tiempo — ver applyPlayerSkin().
     */
    private void sendExistingSkinsTo(ServerPlayer newPlayer) {
        MinecraftServer srv = newPlayer.getServer();
        if (srv == null) return;

        int sent = 0;
        for (ServerPlayer other : srv.getPlayerList().getPlayers()) {
            if (other.getUUID().equals(newPlayer.getUUID())) continue;

            var textures = other.getGameProfile().getProperties().get("textures");
            if (textures.isEmpty()) continue;
            Property prop = textures.iterator().next();

            try {
                newPlayer.connection.send(new ClientboundCustomPayloadPacket(
                        new SkinClientPayload(other.getUUID(), prop.value(),
                                prop.hasSignature() ? prop.signature() : "")));
                sent++;
            } catch (Exception e) {
                CretaniaSync.LOGGER.warn("[Cretania-Skin] Error mandando catch-up de skin de {} a {}: {}",
                        other.getGameProfile().getName(), newPlayer.getGameProfile().getName(), e.getMessage());
            }
        }
        if (sent > 0) {
            CretaniaSync.LOGGER.info("[Cretania-Skin] Catch-up: {} skin(s) existentes enviadas a {}",
                    sent, newPlayer.getGameProfile().getName());
        }
    }

    @SubscribeEvent
    public void onPlayerLogOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!CretaniaSync.getInstance().isDedicatedServer()) return;
        if (!databaseManager.isConnected()) return;

        UUID uuid = player.getUUID();
        pendingProxyDecision.remove(uuid);
        SyncStateManager.unlock(uuid);

        String scope = configuredScope();
        if (scope.isBlank()) {
            CretaniaSync.LOGGER.info("[Cretania] Servidor sin inventoryScope; no se guarda inventario compartido para {}.",
                    player.getGameProfile().getName());
            return;
        }

        String playerName = player.getGameProfile().getName();

        // Si su carga falló, lo que tiene en memoria no es su inventario real: guardarlo
        // pisaría los datos buenos que siguen en Mongo.
        if (loadFailed.remove(uuid)) {
            CretaniaSync.LOGGER.warn("[Cretania] NO se guarda el inventario de {}: su carga había fallado y "
                    + "sobrescribiría los datos correctos en la base.", playerName);
            return;
        }

        String base64Data = snapshot(player);
        String serverName = configuredServerName();

        databaseManager.savePlayerData(uuid, playerName, base64Data, serverName, scope)
                .orTimeout(SyncConfig.INSTANCE.syncTimeoutSeconds.get(), TimeUnit.SECONDS)
                .whenComplete((ignored, ex) -> {
                    if (ex != null) {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        CretaniaSync.LOGGER.error("[Cretania] Error guardando datos de {} scope={}: {}",
                                playerName, scope, cause.getMessage());
                    } else {
                        CretaniaSync.LOGGER.info("[Cretania] Datos guardados para {} scope={} al desconectar.", playerName, scope);
                        SyncChannelHandler.sendSaveComplete(uuid, playerName, scope, serverName);
                    }
                });
    }

    public void triggerLoad(UUID uuid, String scope) {
        if (server == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        if (player == null) return;

        // GUARD: si ya hay una carga directa en curso, ignorar LOAD_READY de Velocity
        if (SyncStateManager.isLocked(uuid)) {
            CretaniaSync.LOGGER.warn("[Cretania] LOAD_READY ignorado para {} — carga directa ya en curso",
                    player.getGameProfile().getName());
            return;
        }

        String expectedScope = pendingProxyDecision.remove(uuid);
        String localScope = configuredScope();
        if (!localScope.equals(scope) || (expectedScope != null && !expectedScope.equals(scope))) {
            CretaniaSync.LOGGER.warn("[Cretania] LOAD_READY ignorado para {}. localScope={}, expectedScope={}, receivedScope={}",
                    player.getGameProfile().getName(), localScope, expectedScope, scope);
            handleLoadFailure(player);
            return;
        }

        loadPlayerFromDatabase(player, scope);
    }

    public void triggerSkip(UUID uuid, String reason) {
        if (server == null) return;
        pendingProxyDecision.remove(uuid);
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        if (player == null) return;
        SyncStateManager.unlock(uuid);
        SyncVisuals.removeLoadingVisuals(player);
        clearInventoryIfConfigured(player, reason);
        CretaniaSync.LOGGER.info("[Cretania] Carga de inventario omitida para {}: {}",
                player.getGameProfile().getName(), reason);
    }

    private void loadPlayerFromDatabase(ServerPlayer player, String scope) {
        UUID uuid = player.getUUID();
        String playerName = player.getGameProfile().getName();
        int timeoutSeconds = SyncConfig.INSTANCE.syncTimeoutSeconds.get();

        databaseManager.loadPlayerData(uuid, scope)
                .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .thenAccept(optionalData -> server.execute(() -> applyPlayerData(player, scope, optionalData)))
                .exceptionally(ex -> {
                    CretaniaSync.LOGGER.error("[Cretania] Error cargando datos de {} scope={}: {}", playerName, scope, ex.getMessage());
                    server.execute(() -> handleLoadFailure(player));
                    return null;
                });
    }

    private void applyPlayerData(ServerPlayer player, String scope, Optional<String> optionalData) {
        UUID uuid = player.getUUID();
        String playerName = player.getGameProfile().getName();

        if (optionalData.isPresent()) {
            try {
                PlayerPositionSnapshot position = PlayerPositionSnapshot.capture(player);
                CompoundTag loadedData = NbtSerializer.fromBase64(optionalData.get());
                // Leer la posición guardada ANTES de removerla — puede ser la última
                // desconexión que restauraremos abajo (survival).
                SavedPosition savedPos = readSavedPosition(loadedData, player.getServer());
                stripPositionData(loadedData);
                player.load(loadedData);
                position.restore(player);
                player.inventoryMenu.broadcastChanges();
                player.refreshDimensions();
                loadFailed.remove(uuid);

                // Restaurar la última posición SOLO si este server lo pide y NO llegó por portal
                // (el portal ya fijó su posición y tiene prioridad). Usa el dato guardado en Mongo,
                // no el playerdata local de vanilla, así funciona aunque vanilla lo haya reseteado.
                if (SyncConfig.INSTANCE.restoreLastPositionOnLogin.get()
                        && !SyncChannelHandler.cameFromPortal(uuid)
                        && savedPos != null) {
                    savedPos.applyTo(player);
                    CretaniaSync.LOGGER.info("[Cretania] {} restaurado a su última posición ({}, {}, {}).",
                            playerName, (int) savedPos.x(), (int) savedPos.y(), (int) savedPos.z());
                }
                CretaniaSync.LOGGER.info("[Cretania] Datos cargados exitosamente para: {} scope={}", playerName, scope);
            } catch (Exception e) {
                CretaniaSync.LOGGER.error("[Cretania] Error aplicando NBT a {} scope={}: {}", playerName, scope, e.getMessage());
                handleLoadFailure(player);
                return;
            }
        } else {
            // NO borrar. Sin documento en Mongo, el inventario que el jugador ya tiene
            // cargado (su playerdata local) es su estado real — típicamente tras un crash
            // en el que el guardado no llegó a la base. Borrarlo aquí era una vía directa
            // de pérdida de datos; en su lugar se sube ese estado a Mongo para inicializarlo.
            loadFailed.remove(uuid);
            CretaniaSync.LOGGER.warn("[Cretania] Sin datos previos para {} scope={} — se CONSERVA su inventario local y se sube a Mongo.",
                    playerName, scope);
            databaseManager.savePlayerData(uuid, playerName, snapshot(player), configuredServerName(), scope);
        }

        player.displayClientMessage(net.minecraft.network.chat.Component.literal("§a¡Listo!"), true);
        SyncStateManager.unlock(uuid);
        SyncVisuals.removeLoadingVisuals(player);
    }

    private void scheduleCoordinatorTimeout(UUID uuid, String playerName) {
        int timeoutSeconds = SyncConfig.INSTANCE.syncTimeoutSeconds.get();
        CompletableFuture.delayedExecutor(timeoutSeconds, TimeUnit.SECONDS).execute(() -> {
            if (server == null || !pendingProxyDecision.containsKey(uuid)) {
                return;
            }
            server.execute(() -> {
                if (!pendingProxyDecision.containsKey(uuid)) {
                    return;
                }
                ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                pendingProxyDecision.remove(uuid);
                if (player != null) {
                    String scope = configuredScope();
                    CretaniaSync.LOGGER.warn("[Cretania] Timeout esperando LOAD_READY/LOAD_SKIP para {}. Cargando scope '{}' como fallback.",
                            playerName, scope);
                    if (scope.isBlank()) {
                        triggerSkip(uuid, "coordinator_timeout_no_scope");
                    } else {
                        loadPlayerFromDatabase(player, scope);
                    }
                }
            });
        });
    }

    private void handleLoadFailure(ServerPlayer player) {
        UUID uuid = player.getUUID();
        pendingProxyDecision.remove(uuid);
        SyncStateManager.unlock(uuid);
        SyncVisuals.removeLoadingVisuals(player);
        // Marcarlo para NO guardarlo después: su inventario en memoria no es el bueno y
        // guardarlo pisaría los datos correctos que siguen en Mongo.
        loadFailed.add(uuid);

        if (SyncConfig.INSTANCE.kickOnFailure.get()) {
            player.connection.disconnect(Component.literal("§c[Cretania] Error de sincronizacion. Por favor, reconectate."));
        }
    }

    /** Posición guardada en el NBT del jugador (última desconexión), ya resuelta a su nivel. */
    private record SavedPosition(ServerLevel level, double x, double y, double z, float yaw, float pitch) {
        void applyTo(ServerPlayer player) {
            player.teleportTo(level, x, y, z, yaw, pitch);
            player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            player.resetFallDistance();
        }
    }

    /**
     * Lee Pos/Rotation/Dimension del NBT guardado. Devuelve null si no hay posición válida
     * (p. ej. el primer login del jugador, cuando el documento aún no tenía posición).
     */
    private SavedPosition readSavedPosition(CompoundTag data, MinecraftServer srv) {
        if (srv == null) return null;
        var posTag = data.getList("Pos", net.minecraft.nbt.Tag.TAG_DOUBLE);
        if (posTag.size() < 3) return null;
        double x = posTag.getDouble(0), y = posTag.getDouble(1), z = posTag.getDouble(2);

        float yaw = 0f, pitch = 0f;
        var rotTag = data.getList("Rotation", net.minecraft.nbt.Tag.TAG_FLOAT);
        if (rotTag.size() >= 2) { yaw = rotTag.getFloat(0); pitch = rotTag.getFloat(1); }

        // Dimensión guardada; si no resuelve, usa el overworld del server.
        ServerLevel level = null;
        if (data.contains("Dimension", net.minecraft.nbt.Tag.TAG_STRING)) {
            var id = net.minecraft.resources.ResourceLocation.tryParse(data.getString("Dimension"));
            if (id != null) {
                level = srv.getLevel(net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION, id));
            }
        }
        if (level == null) level = srv.overworld();
        return new SavedPosition(level, x, y, z, yaw, pitch);
    }

    private void stripPositionData(CompoundTag data) {
        data.remove("Pos");
        data.remove("Rotation");
        data.remove("Dimension");
        data.remove("Motion");
        data.remove("SpawnX");
        data.remove("SpawnY");
        data.remove("SpawnZ");
        data.remove("SpawnForced");
        data.remove("SpawnAngle");
        data.remove("SpawnDimension");
        data.remove("WorldUUIDLeast");
        data.remove("WorldUUIDMost");
        data.remove("OnGround");
        data.remove("FallDistance");
        data.remove("FallFlying");
    }

    private void handleDatabaseUnavailable(ServerPlayer player) {
        CretaniaSync.LOGGER.warn("[Cretania] MySQL no disponible. Desconectando a {}.",
                player.getGameProfile().getName());
        player.connection.disconnect(Component.literal("§c[Cretania] Base de datos no disponible. Intentalo mas tarde."));
    }

    private void clearInventoryIfConfigured(ServerPlayer player, String reason) {
        if (!SyncConfig.INSTANCE.clearInventoryWhenNotSynced.get()) {
            return;
        }

        player.getInventory().clearContent();
        player.getEnderChestInventory().clearContent();
        player.containerMenu.setCarried(ItemStack.EMPTY);
        player.inventoryMenu.broadcastChanges();
        player.containerMenu.broadcastChanges();
        CretaniaSync.LOGGER.info("[Cretania] Inventario local limpiado para {} ({})",
                player.getGameProfile().getName(), reason);
    }

    private String configuredScope() {
        return SyncConfig.INSTANCE.inventoryScope.get().trim();
    }

    private String configuredServerName() {
        String name = SyncConfig.INSTANCE.serverName.get().trim();
        return name.isBlank() ? "unknown" : name;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
    }

    private record PlayerPositionSnapshot(ServerLevel level, double x, double y, double z, float yRot, float xRot, Vec3 deltaMovement) {
        static PlayerPositionSnapshot capture(ServerPlayer player) {
            return new PlayerPositionSnapshot(
                    player.serverLevel(),
                    player.getX(),
                    player.getY(),
                    player.getZ(),
                    player.getYRot(),
                    player.getXRot(),
                    player.getDeltaMovement()
            );
        }

        void restore(ServerPlayer player) {
            player.teleportTo(level, x, y, z, yRot, xRot);
            player.setDeltaMovement(deltaMovement);
            player.hurtMarked = true;
        }
    }

    /**
     * Llamado cuando Velocity envía un mensaje SKIN:<uuid>:<value>:<signature>.
     *
     * Estrategia en dos capas:
     * 1. S2C SkinClientPayload → clientes con cretania-client lo aplican instantáneamente
     *    sin entity-respawn (incluyendo la vista propia del jugador en F5).
     * 2. PlayerInfoRemove+Update + entity-respawn para OTROS jugadores → fallback para
     *    clientes sin cretania-client. NO se envía al jugador propio porque
     *    PlayerInfoRemove(self_uuid) deja momentáneamente sin PlayerInfo al LocalPlayer
     *    y rompe el render de su propia skin (bug observado: solo se ven skins ajenas).
     *
     * Cada send va en try/catch para que un cliente sin el canal opcional no aborte el
     * broadcast a los demás (bug observado: el servidor parecía morir al conectarse
     * un jugador sin el mod, porque el for lanzaba IllegalStateException).
     */
    public void applyPlayerSkin(UUID uuid, String value, String signature) {
        applyPlayerSkin(uuid, value, signature, true);
    }

    /**
     * @param persistToMongo true para guardar en MongoDB (skin "estable" del jugador).
     *                       false cuando aplicamos una skin temporal o ya viene de Mongo.
     */
    public void applyPlayerSkin(UUID uuid, String value, String signature, boolean persistToMongo) {
        if (server == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        if (player == null) return;

        // Persistir en MongoDB ASYNC — la próxima vez que el jugador entre, la skin se carga
        // instantáneo sin fetch a Mojang ni esperar al cliente.
        if (persistToMongo && databaseManager.isConnected()) {
            String type = (signature != null && !signature.isBlank()) ? "premium" : "cracked";
            databaseManager.upsertSkin(uuid, player.getGameProfile().getName(), value, signature, type);
        }

        server.execute(() -> {
            if (player.hasDisconnected()) return;

            GameProfile profile = player.getGameProfile();
            profile.getProperties().removeAll("textures");
            if (signature != null && !signature.isBlank()) {
                profile.getProperties().put("textures", new Property("textures", value, signature));
            } else {
                profile.getProperties().put("textures", new Property("textures", value));
            }

            var skinClientPayload = new SkinClientPayload(uuid, value, signature);
            List<ServerPlayer> needsFallback = new java.util.ArrayList<>();
            int payloadSent = 0;
            for (ServerPlayer other : server.getPlayerList().getPlayers()) {
                try {
                    other.connection.send(new ClientboundCustomPayloadPacket(skinClientPayload));
                    payloadSent++;
                } catch (Exception ignored) {
                    // Cliente sin cretania-client → usará el fallback legacy de abajo
                    if (!other.getUUID().equals(uuid)) needsFallback.add(other);
                }
            }

            if (!needsFallback.isEmpty()) {
                var removePacket = new ClientboundPlayerInfoRemovePacket(List.of(uuid));
                var addPacket = new ClientboundPlayerInfoUpdatePacket(
                        java.util.EnumSet.of(
                                ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
                                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
                                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY
                        ),
                        java.util.List.of(player)
                );

                // Fallback SOLO para clientes que no recibieron el payload limpio (sin cretania-client).
                // Recrear la entidad sin re-sincronizar su SynchedEntityData puede dejarla sin
                // renderizar (jugador invisible) — por eso antes NO se aplicaba a todos.
                for (ServerPlayer other : needsFallback) {
                    try {
                        other.connection.send(removePacket);
                        other.connection.send(addPacket);
                        other.connection.send(new ClientboundRemoveEntitiesPacket(player.getId()));
                        other.connection.send(new ClientboundAddEntityPacket(
                                player.getId(), uuid,
                                player.getX(), player.getY(), player.getZ(),
                                player.getXRot(), player.getYRot(),
                                player.getType(), 0,
                                player.getDeltaMovement(), player.getYHeadRot()
                        ));
                        var nonDefaultData = player.getEntityData().getNonDefaultValues();
                        if (nonDefaultData != null) {
                            other.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket(
                                    player.getId(), nonDefaultData));
                        }
                    } catch (Exception e) {
                        CretaniaSync.LOGGER.warn("[Cretania] Error refrescando skin para {}: {}",
                                other.getGameProfile().getName(), e.getMessage());
                    }
                }
            }

            CretaniaSync.LOGGER.info("[Cretania] Skin aplicada para {} ({} clientes vía SkinClientPayload, fallback a {} otros)",
                    player.getGameProfile().getName(), payloadSent, needsFallback.size());
        });
    }
}
