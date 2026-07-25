package com.cretania.invsync.zone;

import com.cretania.invsync.CretaniaSync;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.file.FileConfig;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * RTP por portal del End: un OP construye un portal del End (bloques
 * minecraft:end_portal) en el spawn y lo registra con /rtp crear. Al pisarlo,
 * el jugador se teletransporta a un punto aleatorio del mismo mundo.
 *
 * El viaje vanilla a la dimensión del End queda anulado para los portales
 * registrados (igual que {@link PortalManager} hace con los del Nether).
 *
 * IMPORTANTE — diferencia con los portales del Nether: {@code Portal.getPortalTransitionTime}
 * devuelve 0 para EndPortalBlock (el Nether usa 80 ticks), así que el viaje vanilla se
 * dispara en el MISMO tick en que el jugador toca el bloque. Un handler reactivo llega
 * tarde, por eso el cooldown de portal se refresca de forma preventiva a todo el que
 * entra en {@link #PROXIMITY_RADIUS} de la zona, y {@link #onDimensionTravel} queda como
 * red de seguridad por si alguien llega al bloque sin pasar por la vecindad (p. ej. un TP).
 *
 * Orden de un RTP (esto es lo que evita que el jugador caiga antes de ver el terreno):
 *   1. Se congela al jugador (sin gravedad + invulnerable) y se elige un punto al azar.
 *   2. Se fuerza la carga del terreno destino con un chunk ticket y se ESPERA — sin
 *      bloquear el hilo del servidor — a que esté cargado.
 *   3. Recién con el terreno listo se mueve al jugador, ya parado sobre el suelo.
 *   4. Sigue sin gravedad {@code settle_ticks} más mientras el cliente recibe y renderiza
 *      los chunks; después se le devuelve la gravedad.
 *
 * Config generada: config/invsync-rtp.toml
 */
public class RtpManager {

    private static final int MAX_PORTAL_BLOCKS = 512; // tope del flood-fill
    private static final int SEARCH_RADIUS     = 4;   // radio de búsqueda del portal al crear
    private static final int HITBOX_PADDING    = 1;   // tolerancia de la caja (ver PortalManager)
    private static final int PROXIMITY_RADIUS  = 6;   // radio para congelar el portal vanilla
    private static final int VANILLA_COOLDOWN_TICKS = 40; // > 1 tick, se refresca cada tick

    /**
     * Ticket propio para mantener cargado el terreno destino. El lifespan (300 ticks = 15 s)
     * es de sobra para que el jugador llegue y sus propios tickets tomen el relevo; expira
     * solo, así que no hace falta limpiarlo ni siquiera si el jugador se desconecta.
     */
    private static final TicketType<ChunkPos> RTP_TICKET =
            TicketType.create("cretania_rtp", Comparator.comparingLong(ChunkPos::toLong), 300);

    // -------------------------------------------------------------------------
    // Config
    // -------------------------------------------------------------------------

    private static String dimension        = "minecraft:overworld";
    private static int    centerX          = 0;
    private static int    centerZ          = 0;
    private static int    minRadius        = 500;
    private static int    maxRadius        = 5000;
    private static int    cooldownSeconds  = 3;
    private static int    maxAttempts      = 12;
    private static int    settleTicks      = 30;  // 1.5 s flotando mientras carga el cliente
    private static int    preloadRadius    = 3;   // en chunks → 7x7 precargados
    private static int    chunkTimeoutTicks = 100; // 5 s esperando la carga antes de reintentar

    private static Path configPath;

    public record RtpZone(String name, String dimension,
                          int minX, int minY, int minZ,
                          int maxX, int maxY, int maxZ) {

        public boolean contains(String dim, BlockPos pos) {
            return within(dim, pos, HITBOX_PADDING);
        }

        public boolean isNear(String dim, BlockPos pos, int radius) {
            return within(dim, pos, radius);
        }

        private boolean within(String dim, BlockPos pos, int pad) {
            return dimension.equals(dim)
                    && pos.getX() >= minX - pad && pos.getX() <= maxX + pad
                    && pos.getY() >= minY - pad && pos.getY() <= maxY + pad
                    && pos.getZ() >= minZ - pad && pos.getZ() <= maxZ + pad;
        }
    }

    /** Solicitud esperando a que cargue el terreno destino. */
    private record Pending(int x, int z, ChunkPos chunk, int attempt, long deadlineTick) {}

    private static final Map<String, RtpZone> ZONES     = new ConcurrentHashMap<>();
    private static final Map<UUID, Pending>   PENDING   = new ConcurrentHashMap<>();
    /** uuid → tick en el que se le devuelve la gravedad. */
    private static final Map<UUID, Long>      FROZEN    = new ConcurrentHashMap<>();
    /** uuid → tick en el que puede volver a usar el portal. */
    private static final Map<UUID, Long>      COOLDOWNS = new ConcurrentHashMap<>();
    /** uuid → valor de invulnerable previo al congelado, para restaurarlo tal cual. */
    private static final Map<UUID, Boolean>   PREV_INVULNERABLE = new ConcurrentHashMap<>();

    private static long serverTick = 0;

    // -------------------------------------------------------------------------
    // Inicialización / persistencia
    // -------------------------------------------------------------------------

    public static void init(MinecraftServer server) {
        configPath = server.getServerDirectory()
                .resolve("config")
                .resolve("invsync-rtp.toml");
        load();
    }

    public static synchronized void load() {
        ZONES.clear();

        if (configPath == null || !Files.exists(configPath)) {
            CretaniaSync.LOGGER.info("[Cretania-RTP] No existe invsync-rtp.toml — sin portales RTP registrados.");
            return;
        }

        try (FileConfig config = FileConfig.of(configPath)) {
            config.load();

            dimension         = config.getOrElse("dimension", "minecraft:overworld");
            centerX           = ((Number) config.getOrElse("center_x", 0)).intValue();
            centerZ           = ((Number) config.getOrElse("center_z", 0)).intValue();
            minRadius         = ((Number) config.getOrElse("min_radius", 500)).intValue();
            maxRadius         = ((Number) config.getOrElse("max_radius", 5000)).intValue();
            cooldownSeconds   = ((Number) config.getOrElse("cooldown_seconds", 3)).intValue();
            maxAttempts       = ((Number) config.getOrElse("max_attempts", 12)).intValue();
            settleTicks       = ((Number) config.getOrElse("settle_ticks", 30)).intValue();
            preloadRadius     = ((Number) config.getOrElse("preload_chunk_radius", 3)).intValue();
            chunkTimeoutTicks = ((Number) config.getOrElse("chunk_timeout_ticks", 100)).intValue();
            if (maxRadius < minRadius) maxRadius = minRadius;

            List<Config> zoneList = config.getOrElse("zone", java.util.Collections.emptyList());
            for (Config zc : zoneList) {
                String name = zc.getOrElse("name", "");
                if (name.isBlank()) continue;
                String dim = zc.getOrElse("dimension", "minecraft:overworld");
                int x1 = ((Number) zc.getOrElse("x1", 0)).intValue();
                int y1 = ((Number) zc.getOrElse("y1", 0)).intValue();
                int z1 = ((Number) zc.getOrElse("z1", 0)).intValue();
                int x2 = ((Number) zc.getOrElse("x2", 0)).intValue();
                int y2 = ((Number) zc.getOrElse("y2", 0)).intValue();
                int z2 = ((Number) zc.getOrElse("z2", 0)).intValue();

                RtpZone zone = new RtpZone(name, dim,
                        Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                        Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
                ZONES.put(key(name), zone);
                CretaniaSync.LOGGER.info("[Cretania-RTP] PORTAL '{}' {} X[{},{}] Y[{},{}] Z[{},{}]",
                        name, dim, zone.minX(), zone.maxX(), zone.minY(), zone.maxY(), zone.minZ(), zone.maxZ());
            }
            CretaniaSync.LOGGER.info("[Cretania-RTP] {} portal(es) RTP cargado(s). Destino: {} centro ({},{}) radio {}-{}",
                    ZONES.size(), dimension, centerX, centerZ, minRadius, maxRadius);
        } catch (Exception e) {
            CretaniaSync.LOGGER.error("[Cretania-RTP] Error cargando invsync-rtp.toml: {}", e.getMessage());
        }
    }

    public static synchronized void save() {
        if (configPath == null) return;
        try {
            Files.createDirectories(configPath.getParent());
            try (FileConfig config = FileConfig.of(configPath)) {
                config.set("dimension", dimension);
                config.set("center_x", centerX);
                config.set("center_z", centerZ);
                config.set("min_radius", minRadius);
                config.set("max_radius", maxRadius);
                config.set("cooldown_seconds", cooldownSeconds);
                config.set("max_attempts", maxAttempts);
                config.set("settle_ticks", settleTicks);
                config.set("preload_chunk_radius", preloadRadius);
                config.set("chunk_timeout_ticks", chunkTimeoutTicks);

                List<Config> list = new ArrayList<>();
                for (RtpZone z : zonesSorted()) {
                    Config c = config.createSubConfig();
                    c.set("name", z.name());
                    c.set("dimension", z.dimension());
                    c.set("x1", z.minX());
                    c.set("y1", z.minY());
                    c.set("z1", z.minZ());
                    c.set("x2", z.maxX());
                    c.set("y2", z.maxY());
                    c.set("z2", z.maxZ());
                    list.add(c);
                }
                config.set("zone", list);
                config.save();
            }
        } catch (Exception e) {
            CretaniaSync.LOGGER.error("[Cretania-RTP] Error guardando invsync-rtp.toml: {}", e.getMessage());
        }
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public static List<RtpZone> zonesSorted() {
        List<RtpZone> list = new ArrayList<>(ZONES.values());
        list.sort(Comparator.comparing(RtpZone::name, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    public static boolean hasZones() {
        return !ZONES.isEmpty();
    }

    // -------------------------------------------------------------------------
    // Registro del portal (flood-fill sobre bloques end_portal)
    // -------------------------------------------------------------------------

    public static RtpZone createFromNearbyPortal(ServerPlayer player, String name) {
        if (ZONES.containsKey(key(name))) return null;

        ServerLevel level = player.serverLevel();
        BlockPos feet = player.blockPosition();

        BlockPos start = null;
        outer:
        for (int r = 0; r <= SEARCH_RADIUS; r++) {
            for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-r, -r, -r), feet.offset(r, r, r))) {
                if (level.getBlockState(pos).is(Blocks.END_PORTAL)) {
                    start = pos.immutable();
                    break outer;
                }
            }
        }
        if (start == null) return null;

        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);
        int minX = start.getX(), minY = start.getY(), minZ = start.getZ();
        int maxX = minX, maxY = minY, maxZ = minZ;
        while (!queue.isEmpty() && visited.size() < MAX_PORTAL_BLOCKS) {
            BlockPos pos = queue.poll();
            minX = Math.min(minX, pos.getX()); maxX = Math.max(maxX, pos.getX());
            minY = Math.min(minY, pos.getY()); maxY = Math.max(maxY, pos.getY());
            minZ = Math.min(minZ, pos.getZ()); maxZ = Math.max(maxZ, pos.getZ());
            for (Direction dir : Direction.values()) {
                BlockPos next = pos.relative(dir);
                if (!visited.contains(next) && level.getBlockState(next).is(Blocks.END_PORTAL)) {
                    visited.add(next);
                    queue.add(next);
                }
            }
        }

        RtpZone zone = new RtpZone(name, level.dimension().location().toString(),
                minX, minY, minZ, maxX, maxY, maxZ);
        ZONES.put(key(name), zone);
        save();
        CretaniaSync.LOGGER.info("[Cretania-RTP] Portal RTP '{}' creado por {} ({} bloques)",
                name, player.getGameProfile().getName(), visited.size());
        return zone;
    }

    public static boolean delete(String name) {
        boolean removed = ZONES.remove(key(name)) != null;
        if (removed) save();
        return removed;
    }

    // -------------------------------------------------------------------------
    // Tick principal
    // -------------------------------------------------------------------------

    public static void onServerTick(ServerTickEvent.Post event) {
        serverTick++;
        MinecraftServer server = event.getServer();

        processFrozen(server);
        processPending(server);

        if (ZONES.isEmpty()) return;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isSpectator()) continue; // atraviesan bloques, no deben disparar el RTP

            BlockPos feet = player.blockPosition();
            String dim = player.level().dimension().location().toString();

            RtpZone inside = null;
            boolean near = false;
            for (RtpZone z : ZONES.values()) {
                if (!z.isNear(dim, feet, PROXIMITY_RADIUS)) continue;
                near = true;
                if (z.contains(dim, feet)) { inside = z; break; }
            }
            if (!near) continue;

            // Congela el contador de portal vanilla ANTES de que llegue al bloque: el End
            // teletransporta con 0 ticks de delay, así que reaccionar al contacto es tarde.
            player.setPortalCooldown(VANILLA_COOLDOWN_TICKS);

            if (inside != null
                    && !PENDING.containsKey(player.getUUID())
                    && player.level().getBlockState(feet).is(Blocks.END_PORTAL)) {
                startRtp(player);
            }
        }
    }

    /**
     * Red de seguridad: si alguien llega al bloque sin pasar por la vecindad (un /tp, por
     * ejemplo) el cooldown preventivo no alcanzó a aplicarse — aquí se cancela el viaje.
     */
    public static void onDimensionTravel(EntityTravelToDimensionEvent event) {
        if (ZONES.isEmpty()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        BlockPos feet = player.blockPosition();
        String dim = player.level().dimension().location().toString();
        for (RtpZone z : ZONES.values()) {
            if (z.contains(dim, feet)) {
                event.setCanceled(true);
                return;
            }
        }
    }

    /**
     * CRÍTICO: noGravity se persiste en el NBT del jugador, y ese NBT lo serializa
     * InventorySync al desconectar para sincronizarlo entre servidores. Si alguien se va
     * a mitad de un RTP hay que devolverle la gravedad ANTES de ese guardado, o quedaría
     * flotando para siempre. Por eso se registra con prioridad HIGHEST.
     */
    public static void onPlayerLogOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        UUID uuid = player.getUUID();
        boolean wasInRtp = PENDING.remove(uuid) != null | FROZEN.remove(uuid) != null;
        if (wasInRtp) {
            unfreeze(player);
            CretaniaSync.LOGGER.info("[Cretania-RTP] {} se desconectó a mitad de un RTP — estado restaurado.",
                    player.getGameProfile().getName());
        }
        PREV_INVULNERABLE.remove(uuid);
        COOLDOWNS.remove(uuid);
    }

    // -------------------------------------------------------------------------
    // Flujo del RTP
    // -------------------------------------------------------------------------

    private static void startRtp(ServerPlayer player) {
        UUID uuid = player.getUUID();

        Long until = COOLDOWNS.get(uuid);
        if (until != null && serverTick < until) {
            long secs = (until - serverTick) / 20 + 1;
            player.displayClientMessage(Component.literal("Espera " + secs + "s para volver a usar el portal.")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }

        freeze(player);
        player.displayClientMessage(Component.literal("Buscando un lugar seguro...")
                .withStyle(ChatFormatting.LIGHT_PURPLE), true);
        requestSpot(player, 1);
    }

    /** Elige un punto al azar y pide la carga del terreno. NO mueve al jugador todavía. */
    private static void requestSpot(ServerPlayer player, int attempt) {
        if (attempt > maxAttempts) {
            fail(player, "No se encontró un lugar seguro. Intenta de nuevo.");
            return;
        }

        ServerLevel level = resolveLevel(player.getServer());
        if (level == null) {
            fail(player, "El mundo de destino del RTP no está disponible.");
            return;
        }

        // Punto uniforme dentro del anillo [minRadius, maxRadius].
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double angle = rnd.nextDouble() * Math.PI * 2;
        double dist = Math.sqrt(rnd.nextDouble()
                * ((double) maxRadius * maxRadius - (double) minRadius * minRadius)
                + (double) minRadius * minRadius);
        int x = centerX + (int) Math.round(Math.cos(angle) * dist);
        int z = centerZ + (int) Math.round(Math.sin(angle) * dist);

        ChunkPos chunk = new ChunkPos(x >> 4, z >> 4);
        // Fuerza la carga en segundo plano. El hilo del servidor NO se bloquea: se consulta
        // cada tick en processPending() hasta que el terreno esté listo.
        level.getChunkSource().addRegionTicket(RTP_TICKET, chunk, preloadRadius, chunk);
        PENDING.put(player.getUUID(), new Pending(x, z, chunk, attempt, serverTick + chunkTimeoutTicks));
    }

    private static void processPending(MinecraftServer server) {
        if (PENDING.isEmpty()) return;

        for (Map.Entry<UUID, Pending> entry : PENDING.entrySet()) {
            UUID uuid = entry.getKey();
            Pending p = entry.getValue();

            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) { // se desconectó: onPlayerLogOut ya restauró su estado
                PENDING.remove(uuid);
                continue;
            }

            ServerLevel level = resolveLevel(server);
            if (level == null) {
                PENDING.remove(uuid);
                fail(player, "El mundo de destino del RTP no está disponible.");
                continue;
            }

            if (!chunksReady(level, p.chunk())) {
                if (serverTick > p.deadlineTick()) {
                    PENDING.remove(uuid);
                    CretaniaSync.LOGGER.warn("[Cretania-RTP] Timeout cargando ({},{}) para {} — probando otro punto.",
                            p.x(), p.z(), player.getGameProfile().getName());
                    requestSpot(player, p.attempt() + 1);
                }
                continue; // sigue esperando
            }

            PENDING.remove(uuid);
            Integer y = findLanding(level, p.x(), p.z());
            if (y == null) {
                requestSpot(player, p.attempt() + 1); // agua, lava o sin espacio → otro punto
                continue;
            }
            land(player, p.x(), y, p.z(), p.attempt());
        }
    }

    /** Terreno listo cuando el chunk destino y sus vecinos inmediatos están en FULL. */
    private static boolean chunksReady(ServerLevel level, ChunkPos center) {
        ServerChunkCache cache = level.getChunkSource();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!cache.hasChunk(center.x + dx, center.z + dz)) return false;
            }
        }
        return true;
    }

    /** @return Y donde el jugador queda parado sobre suelo firme, o null si el punto no sirve. */
    private static Integer findLanding(ServerLevel level, int x, int z) {
        int topY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (topY <= level.getMinBuildHeight() + 1 || topY >= level.getMaxBuildHeight() - 2) return null;

        BlockPos ground = new BlockPos(x, topY - 1, z);
        BlockState groundState = level.getBlockState(ground);
        if (groundState.isAir()) return null;
        // MOTION_BLOCKING_NO_LEAVES incluye el agua, así que esto descarta océanos y lava.
        if (!groundState.getFluidState().isEmpty()) return null;
        if (groundState.is(Blocks.MAGMA_BLOCK) || groundState.is(Blocks.CACTUS)
                || groundState.is(Blocks.FIRE) || groundState.is(Blocks.CAMPFIRE)) return null;

        // Dos bloques libres encima para que quepa el jugador.
        BlockPos feet = ground.above();
        BlockPos head = ground.above(2);
        if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()) return null;
        if (!level.getBlockState(head).getCollisionShape(level, head).isEmpty()) return null;

        return topY;
    }

    private static void land(ServerPlayer player, int x, int y, int z, int attempt) {
        UUID uuid = player.getUUID();

        player.stopRiding(); // connection.teleport no desmonta; el vehículo se queda atrás
        player.setDeltaMovement(Vec3.ZERO);
        player.connection.teleport(x + 0.5, y, z + 0.5, player.getYRot(), player.getXRot());
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();

        // Sigue sin gravedad un momento más: el terreno ya está en el servidor, pero el
        // cliente todavía tiene que recibirlo y renderizarlo. Sin esta ventana el jugador
        // ve vacío y cae hasta que le llegan los chunks.
        FROZEN.put(uuid, serverTick + settleTicks);
        COOLDOWNS.put(uuid, serverTick + cooldownSeconds * 20L);

        CretaniaSync.LOGGER.info("[Cretania-RTP] {} → ({}, {}, {}) en {} (intento {})",
                player.getGameProfile().getName(), x, y, z, dimension, attempt);
    }

    private static void processFrozen(MinecraftServer server) {
        if (FROZEN.isEmpty()) return;

        for (Map.Entry<UUID, Long> entry : FROZEN.entrySet()) {
            if (serverTick < entry.getValue()) continue;
            UUID uuid = entry.getKey();
            FROZEN.remove(uuid);
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                unfreeze(player);
                player.displayClientMessage(Component.literal("¡Listo!").withStyle(ChatFormatting.GREEN), true);
            }
        }
    }

    private static void fail(ServerPlayer player, String reason) {
        UUID uuid = player.getUUID();
        PENDING.remove(uuid);
        FROZEN.remove(uuid);
        unfreeze(player);
        // Cooldown también al fallar: si el portal tiene suelo debajo el jugador se queda
        // parado encima y la detección volvería a dispararse cada tick, reintentando en bucle.
        COOLDOWNS.put(uuid, serverTick + cooldownSeconds * 20L);
        player.displayClientMessage(Component.literal(reason).withStyle(ChatFormatting.RED), true);
        CretaniaSync.LOGGER.warn("[Cretania-RTP] RTP fallido para {}: {}",
                player.getGameProfile().getName(), reason);
    }

    private static void freeze(ServerPlayer player) {
        PREV_INVULNERABLE.putIfAbsent(player.getUUID(), player.isInvulnerable());
        player.setNoGravity(true);   // se sincroniza al cliente: deja de aplicar gravedad
        player.setInvulnerable(true);
        player.setDeltaMovement(Vec3.ZERO);
    }

    private static void unfreeze(ServerPlayer player) {
        player.setNoGravity(false);
        Boolean prev = PREV_INVULNERABLE.remove(player.getUUID());
        player.setInvulnerable(prev != null && prev);
        player.resetFallDistance();
    }

    private static ServerLevel resolveLevel(MinecraftServer server) {
        if (server == null) return null;
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        if (id == null) return null;
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    // -------------------------------------------------------------------------
    // Comandos: /rtp
    // -------------------------------------------------------------------------

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        var zoneSuggestions = (com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack>)
                (ctx, builder) -> SharedSuggestionProvider.suggest(
                        zonesSorted().stream().map(RtpZone::name).toList(), builder);

        event.getDispatcher().register(Commands.literal("rtp")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("crear")
                        .then(Commands.argument("nombre", StringArgumentType.word())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    String name = StringArgumentType.getString(ctx, "nombre");
                                    if (ZONES.containsKey(key(name))) {
                                        ctx.getSource().sendFailure(Component.literal(
                                                "Ya existe un portal RTP llamado '" + name + "'."));
                                        return 0;
                                    }
                                    RtpZone zone = createFromNearbyPortal(player, name);
                                    if (zone == null) {
                                        ctx.getSource().sendFailure(Component.literal(
                                                "No hay bloques de portal del End cerca. Construye el portal primero."));
                                        return 0;
                                    }
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "Portal RTP '" + name + "' registrado. Destino: " + dimension
                                                    + " centro (" + centerX + "," + centerZ + ") radio "
                                                    + minRadius + "-" + maxRadius)
                                            .withStyle(ChatFormatting.GREEN), true);
                                    return 1;
                                })))
                .then(Commands.literal("eliminar")
                        .then(Commands.argument("nombre", StringArgumentType.word())
                                .suggests(zoneSuggestions)
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "nombre");
                                    if (!delete(name)) {
                                        ctx.getSource().sendFailure(Component.literal(
                                                "No existe el portal RTP '" + name + "'."));
                                        return 0;
                                    }
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "Portal RTP '" + name + "' eliminado.")
                                            .withStyle(ChatFormatting.YELLOW), true);
                                    return 1;
                                })))
                .then(Commands.literal("centro")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(ctx -> {
                                            centerX = IntegerArgumentType.getInteger(ctx, "x");
                                            centerZ = IntegerArgumentType.getInteger(ctx, "z");
                                            save();
                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                    "Centro del RTP: (" + centerX + ", " + centerZ + ")")
                                                    .withStyle(ChatFormatting.GREEN), true);
                                            return 1;
                                        }))))
                .then(Commands.literal("radio")
                        .then(Commands.argument("min", IntegerArgumentType.integer(0))
                                .then(Commands.argument("max", IntegerArgumentType.integer(1))
                                        .executes(ctx -> {
                                            int min = IntegerArgumentType.getInteger(ctx, "min");
                                            int max = IntegerArgumentType.getInteger(ctx, "max");
                                            if (max < min) {
                                                ctx.getSource().sendFailure(Component.literal(
                                                        "El radio máximo debe ser mayor o igual al mínimo."));
                                                return 0;
                                            }
                                            minRadius = min;
                                            maxRadius = max;
                                            save();
                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                    "Radio del RTP: " + minRadius + " - " + maxRadius + " bloques")
                                                    .withStyle(ChatFormatting.GREEN), true);
                                            return 1;
                                        }))))
                .then(Commands.literal("mundo")
                        .then(Commands.argument("dimension", StringArgumentType.string())
                                .executes(ctx -> {
                                    String dim = StringArgumentType.getString(ctx, "dimension");
                                    if (ResourceLocation.tryParse(dim) == null) {
                                        ctx.getSource().sendFailure(Component.literal(
                                                "Dimensión inválida. Ejemplo: minecraft:overworld"));
                                        return 0;
                                    }
                                    dimension = dim;
                                    save();
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "Mundo destino del RTP: " + dimension)
                                            .withStyle(ChatFormatting.GREEN), true);
                                    return 1;
                                })))
                .then(Commands.literal("ir")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            if (PENDING.containsKey(player.getUUID())) {
                                ctx.getSource().sendFailure(Component.literal("Ya tienes un RTP en curso."));
                                return 0;
                            }
                            COOLDOWNS.remove(player.getUUID());
                            freeze(player);
                            player.displayClientMessage(Component.literal("Buscando un lugar seguro...")
                                    .withStyle(ChatFormatting.LIGHT_PURPLE), true);
                            requestSpot(player, 1);
                            return 1;
                        }))
                .then(Commands.literal("lista")
                        .executes(ctx -> {
                            List<RtpZone> list = zonesSorted();
                            ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                                    "Destino: %s | centro (%d,%d) | radio %d-%d | espera %dt | precarga %d chunks",
                                    dimension, centerX, centerZ, minRadius, maxRadius,
                                    settleTicks, preloadRadius)), false);
                            if (list.isEmpty()) {
                                ctx.getSource().sendSuccess(() -> Component.literal(
                                        "No hay portales RTP registrados."), false);
                                return 0;
                            }
                            for (RtpZone z : list) {
                                ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                                        "%s [%s] (%d,%d,%d)-(%d,%d,%d)",
                                        z.name(), z.dimension(),
                                        z.minX(), z.minY(), z.minZ(),
                                        z.maxX(), z.maxY(), z.maxZ())), false);
                            }
                            return list.size();
                        }))
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            load();
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "RTP recargado: " + ZONES.size() + " portal(es).")
                                    .withStyle(ChatFormatting.GREEN), true);
                            return 1;
                        })));
    }
}
