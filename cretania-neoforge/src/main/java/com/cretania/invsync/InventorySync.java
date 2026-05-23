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
    private MinecraftServer server;

    public InventorySync(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    @SubscribeEvent
    public void onPlayerLogIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!CretaniaSync.getInstance().isDedicatedServer()) return;

        // Notificar a Velocity que el backend tiene al jugador registrado.
        // Velocity responde inmediatamente con skin + auth — sin delays fijos.
        SyncChannelHandler.sendPlayerReady(player);

        if (!databaseManager.isConnected()) {
            handleDatabaseUnavailable(player);
            return;
        }

        this.server = player.getServer();

        // Aplicar posición pendiente de zona (transferencia lobby→tiendas/minijuegos).
        // Se ejecuta aquí porque PlayerLoggedInEvent garantiza que el jugador está en el mundo.
        double[] pendingPos = SyncChannelHandler.PENDING_TELEPORTS.remove(player.getUUID());
        if (pendingPos != null) {
            SyncChannelHandler.applyTeleport(player,
                    pendingPos[0], pendingPos[1], pendingPos[2],
                    (float) pendingPos[3], (float) pendingPos[4]);
        }

        String scope = configuredScope();
        if (scope.isBlank()) {
            CretaniaSync.LOGGER.info("[Cretania] Servidor sin inventoryScope; no se carga inventario compartido para {}.",
                    player.getGameProfile().getName());
            clearInventoryIfConfigured(player, "server_not_synced");
            return;
        }

        UUID uuid = player.getUUID();
        SyncStateManager.lock(uuid);
        // Cargamos directamente desde MongoDB sin esperar al coordinador Velocity.
        // El logout usa .join() (bloquea) así que el save siempre termina antes
        // de que el jugador llegue al siguiente servidor — no hay race condition.
        loadPlayerFromDatabase(player, scope);
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

        CompoundTag playerData = new CompoundTag();
        player.saveWithoutId(playerData);
        String base64Data = NbtSerializer.toBase64(playerData);

        String serverName = configuredServerName();
        String playerName = player.getGameProfile().getName();

        try {
            databaseManager.savePlayerData(uuid, playerName, base64Data, serverName, scope)
                    .orTimeout(SyncConfig.INSTANCE.syncTimeoutSeconds.get(), TimeUnit.SECONDS)
                    .join();
            CretaniaSync.LOGGER.info("[Cretania] Datos guardados para {} scope={} al desconectar.", playerName, scope);
            SyncChannelHandler.sendSaveComplete(player, scope);
        } catch (CompletionException ex) {
            CretaniaSync.LOGGER.error("[Cretania] Error guardando datos de {} scope={}: {}",
                    playerName, scope, rootMessage(ex));
        }
    }

    public void triggerLoad(UUID uuid, String scope) {
        if (server == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        if (player == null) return;

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
                stripPositionData(loadedData);
                player.load(loadedData);
                position.restore(player);
                player.inventoryMenu.broadcastChanges();
                player.refreshDimensions();
                CretaniaSync.LOGGER.info("[Cretania] Datos cargados exitosamente para: {} scope={}", playerName, scope);
            } catch (Exception e) {
                CretaniaSync.LOGGER.error("[Cretania] Error aplicando NBT a {} scope={}: {}", playerName, scope, e.getMessage());
                handleLoadFailure(player);
                return;
            }
        } else {
            CretaniaSync.LOGGER.info("[Cretania] Sin datos previos para {} scope={}.", playerName, scope);
            clearInventoryIfConfigured(player, "empty_scope_data");
        }

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

        if (SyncConfig.INSTANCE.kickOnFailure.get()) {
            player.connection.disconnect(Component.literal("§c[Cretania] Error de sincronizacion. Por favor, reconectate."));
        }
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
     * 2. PlayerInfoRemove+Update + entity-respawn para OTROS → fallback para clientes
     *    sin el mod cliente, garantizando que todos vean la skin correcta.
     */
    public void applyPlayerSkin(UUID uuid, String value, String signature) {
        if (server == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        if (player == null) return;

        server.execute(() -> {
            if (player.hasDisconnected()) return;

            // 1. Inyectar en el GameProfile del backend (para jugadores que se unan después)
            GameProfile profile = player.getGameProfile();
            profile.getProperties().removeAll("textures");
            if (signature != null && !signature.isBlank()) {
                profile.getProperties().put("textures", new Property("textures", value, signature));
            } else {
                profile.getProperties().put("textures", new Property("textures", value));
            }

            // 2. Enviar SkinClientPayload a TODOS los clientes conectados.
            //    cretania-client lo procesa instantáneamente (incluida la vista propia).
            var skinClientPayload = new SkinClientPayload(uuid, value, signature);
            for (ServerPlayer other : server.getPlayerList().getPlayers()) {
                other.connection.send(new ClientboundCustomPayloadPacket(skinClientPayload));
            }

            // 3. Fallback para clientes sin cretania-client: PlayerInfo update + entity-respawn
            //    a los OTROS jugadores. El propio jugador NO recibe RemoveEntities/AddEntity
            //    porque eso congela el cliente (LocalPlayer queda fuera del level).
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

            for (ServerPlayer other : server.getPlayerList().getPlayers()) {
                other.connection.send(removePacket);
                other.connection.send(addPacket);
                if (!other.getUUID().equals(uuid)) {
                    other.connection.send(new ClientboundRemoveEntitiesPacket(player.getId()));
                    other.connection.send(new ClientboundAddEntityPacket(
                            player.getId(), uuid,
                            player.getX(), player.getY(), player.getZ(),
                            player.getXRot(), player.getYRot(),
                            player.getType(), 0,
                            player.getDeltaMovement(), player.getYHeadRot()
                    ));
                }
            }

            CretaniaSync.LOGGER.info("[Cretania] Skin aplicada para {} y difundida a {} clientes",
                    player.getGameProfile().getName(), server.getPlayerList().getPlayers().size());
        });
    }
}
