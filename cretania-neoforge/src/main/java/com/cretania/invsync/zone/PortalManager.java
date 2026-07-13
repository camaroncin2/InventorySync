package com.cretania.invsync.zone;

import com.cretania.invsync.CretaniaSync;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.file.FileConfig;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Portales físicos estilo Nether: un OP construye y enciende un portal de
 * obsidiana normal (bloques minecraft:nether_portal) y lo registra con
 * /portales crear. Cuando un jugador pisa los bloques del portal se le
 * transfiere al servidor asignado (via el mismo socket TRANSFER de
 * ReturnZoneManager).
 *
 * El destino de cada portal se administra en tiempo real desde el menú OP
 * (/portales) o por comandos — los cambios aplican al instante y se
 * persisten en config/invsync-portals.toml.
 *
 * El teletransporte es siempre instantáneo y literal: si el portal tiene
 * coords destino configuradas se usa esa posición exacta; si no, se usa el
 * propio bloque del portal (centro X/Z, Y del piso). No hay RTP ni búsqueda
 * de terreno — así se evita aterrizar lejos del área esperada.
 *
 * Ejemplo de config generada:
 * servers = ["lobby", "survival"]
 *
 * [[portal]]
 * name = "portal_1"
 * dimension = "minecraft:overworld"
 * x1 = 100  y1 = 64  z1 = 200
 * x2 = 100  y2 = 66  z2 = 202
 * targetServer = "survival"
 * # opcionales: target_x/y/z/yaw/pitch
 */
public class PortalManager {

    private static final int CHECK_INTERVAL    = 5;   // 0.25 s — throttle de la transferencia
    private static final int MAX_PORTAL_BLOCKS = 512; // tope del flood-fill
    private static final int SEARCH_RADIUS     = 4;   // radio de búsqueda del portal al crear

    private static int tickCounter = 0;
    private static Path configPath;

    public record PortalDef(String name, String dimension,
                            int minX, int minY, int minZ,
                            int maxX, int maxY, int maxZ,
                            String targetServer,
                            Double targetX, Double targetY, Double targetZ,
                            Float targetYaw, Float targetPitch) {

        /**
         * Margen de tolerancia (bloques) alrededor de la caja exacta del portal.
         * Sin esto, un jugador parado justo en el borde (p. ej. Y mínimo) puede
         * fluctuar sub-bloque por física y caer fuera de la caja la mayoría de
         * los ticks — con la caja "a ras" eso deja tanto la detección del portal
         * como la protección anti-Nether sin activarse casi nunca.
         */
        private static final int HITBOX_PADDING = 1;

        public boolean contains(String dim, BlockPos pos) {
            return dimension.equals(dim)
                    && pos.getX() >= minX - HITBOX_PADDING && pos.getX() <= maxX + HITBOX_PADDING
                    && pos.getY() >= minY - HITBOX_PADDING && pos.getY() <= maxY + HITBOX_PADDING
                    && pos.getZ() >= minZ - HITBOX_PADDING && pos.getZ() <= maxZ + HITBOX_PADDING;
        }

        public boolean hasTargetCoords() {
            return targetX != null && targetY != null && targetZ != null;
        }

        /** Centro del bloque de portal en X, para aterrizar literalmente en el portal. */
        public double landingX() {
            return (minX + maxX) / 2.0 + 0.5;
        }

        /** Y del bloque inferior del portal (nivel de piso al pisarlo). */
        public double landingY() {
            return minY;
        }

        /** Centro del bloque de portal en Z, para aterrizar literalmente en el portal. */
        public double landingZ() {
            return (minZ + maxZ) / 2.0 + 0.5;
        }

        public String modeDescription() {
            if (hasTargetCoords()) return String.format(Locale.US, "Coords (%.1f, %.1f, %.1f)", targetX, targetY, targetZ);
            return "Portal de origen (mismo punto, instantáneo)";
        }
    }

    /** Portales registrados, key = nombre en minúsculas. */
    private static final Map<String, PortalDef> PORTALS = new ConcurrentHashMap<>();
    /** Servidores conocidos para el menú de asignación. */
    private static final List<String> SERVERS = new CopyOnWriteArrayList<>();

    // -------------------------------------------------------------------------
    // Inicialización / persistencia
    // -------------------------------------------------------------------------

    public static void init(MinecraftServer server) {
        configPath = server.getServerDirectory()
                .resolve("config")
                .resolve("invsync-portals.toml");
        load();
    }

    public static synchronized void load() {
        PORTALS.clear();
        SERVERS.clear();

        if (configPath == null || !Files.exists(configPath)) {
            SERVERS.add("lobby");
            CretaniaSync.LOGGER.info("[Cretania-Portales] No existe invsync-portals.toml — sin portales registrados.");
            return;
        }

        try (FileConfig config = FileConfig.of(configPath)) {
            config.load();

            List<String> servers = config.getOrElse("servers", new ArrayList<>());
            SERVERS.addAll(servers);
            if (SERVERS.isEmpty()) SERVERS.add("lobby");

            List<Config> portalList = config.getOrElse("portal", java.util.Collections.emptyList());
            for (Config pc : portalList) {
                String name = pc.getOrElse("name", "");
                if (name.isBlank()) continue;
                String dimension = pc.getOrElse("dimension", "minecraft:overworld");
                int x1 = ((Number) pc.getOrElse("x1", 0)).intValue();
                int y1 = ((Number) pc.getOrElse("y1", 0)).intValue();
                int z1 = ((Number) pc.getOrElse("z1", 0)).intValue();
                int x2 = ((Number) pc.getOrElse("x2", 0)).intValue();
                int y2 = ((Number) pc.getOrElse("y2", 0)).intValue();
                int z2 = ((Number) pc.getOrElse("z2", 0)).intValue();
                String target = pc.getOrElse("targetServer", "lobby");
                Double tx = pc.contains("target_x") ? ((Number) pc.get("target_x")).doubleValue() : null;
                Double ty = pc.contains("target_y") ? ((Number) pc.get("target_y")).doubleValue() : null;
                Double tz = pc.contains("target_z") ? ((Number) pc.get("target_z")).doubleValue() : null;
                Float tyaw = pc.contains("target_yaw") ? ((Number) pc.get("target_yaw")).floatValue() : null;
                Float tpitch = pc.contains("target_pitch") ? ((Number) pc.get("target_pitch")).floatValue() : null;

                PortalDef def = new PortalDef(name, dimension,
                        Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                        Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2),
                        target, tx, ty, tz, tyaw, tpitch);
                PORTALS.put(key(name), def);
                CretaniaSync.LOGGER.info("[Cretania-Portales] PORTAL '{}' {} X[{},{}] Y[{},{}] Z[{},{}] → {} ({})",
                        name, dimension, def.minX(), def.maxX(), def.minY(), def.maxY(),
                        def.minZ(), def.maxZ(), target, def.modeDescription());
            }
            CretaniaSync.LOGGER.info("[Cretania-Portales] {} portal(es) cargado(s), servers: {}", PORTALS.size(), SERVERS);
        } catch (Exception e) {
            CretaniaSync.LOGGER.error("[Cretania-Portales] Error cargando invsync-portals.toml: {}", e.getMessage());
        }
    }

    public static synchronized void save() {
        if (configPath == null) return;
        try {
            Files.createDirectories(configPath.getParent());
            try (FileConfig config = FileConfig.of(configPath)) {
                config.set("servers", new ArrayList<>(SERVERS));
                List<Config> list = new ArrayList<>();
                for (PortalDef p : portalsSorted()) {
                    Config c = config.createSubConfig();
                    c.set("name", p.name());
                    c.set("dimension", p.dimension());
                    c.set("x1", p.minX());
                    c.set("y1", p.minY());
                    c.set("z1", p.minZ());
                    c.set("x2", p.maxX());
                    c.set("y2", p.maxY());
                    c.set("z2", p.maxZ());
                    c.set("targetServer", p.targetServer());
                    if (p.targetX() != null) c.set("target_x", p.targetX());
                    if (p.targetY() != null) c.set("target_y", p.targetY());
                    if (p.targetZ() != null) c.set("target_z", p.targetZ());
                    if (p.targetYaw() != null) c.set("target_yaw", p.targetYaw().doubleValue());
                    if (p.targetPitch() != null) c.set("target_pitch", p.targetPitch().doubleValue());
                    list.add(c);
                }
                config.set("portal", list);
                config.save();
            }
        } catch (Exception e) {
            CretaniaSync.LOGGER.error("[Cretania-Portales] Error guardando invsync-portals.toml: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // API usada por el menú y los comandos (todo aplica en tiempo real)
    // -------------------------------------------------------------------------

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public static PortalDef get(String name) {
        return PORTALS.get(key(name));
    }

    public static boolean hasPortals() {
        return !PORTALS.isEmpty();
    }

    public static List<PortalDef> portalsSorted() {
        List<PortalDef> list = new ArrayList<>(PORTALS.values());
        list.sort(Comparator.comparing(PortalDef::name, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    public static List<String> portalNames() {
        return portalsSorted().stream().map(PortalDef::name).toList();
    }

    public static List<String> servers() {
        return new ArrayList<>(SERVERS);
    }

    public static boolean addServer(String server) {
        String s = server.toLowerCase(Locale.ROOT);
        if (SERVERS.contains(s)) return false;
        SERVERS.add(s);
        save();
        return true;
    }

    public static boolean removeServer(String server) {
        boolean removed = SERVERS.remove(server.toLowerCase(Locale.ROOT));
        if (removed) save();
        return removed;
    }

    /**
     * Registra un portal a partir de los bloques nether_portal cercanos al jugador.
     * Busca el bloque de portal más cercano (radio {@value SEARCH_RADIUS}) y hace
     * flood-fill sobre los bloques conectados para calcular la caja del portal.
     *
     * @return el portal creado, o null si no hay bloques de portal cerca o el nombre ya existe.
     */
    public static PortalDef createFromNearbyPortal(ServerPlayer player, String name) {
        if (PORTALS.containsKey(key(name))) return null;

        ServerLevel level = player.serverLevel();
        BlockPos feet = player.blockPosition();

        BlockPos start = null;
        outer:
        for (int r = 0; r <= SEARCH_RADIUS; r++) {
            for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-r, -r, -r), feet.offset(r, r, r))) {
                if (level.getBlockState(pos).is(Blocks.NETHER_PORTAL)) {
                    start = pos.immutable();
                    break outer;
                }
            }
        }
        if (start == null) return null;

        // Flood-fill sobre bloques de portal conectados
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
                if (!visited.contains(next) && level.getBlockState(next).is(Blocks.NETHER_PORTAL)) {
                    visited.add(next);
                    queue.add(next);
                }
            }
        }

        String defaultServer = SERVERS.isEmpty() ? "lobby" : SERVERS.get(0);
        PortalDef def = new PortalDef(name, level.dimension().location().toString(),
                minX, minY, minZ, maxX, maxY, maxZ,
                defaultServer, null, null, null, null, null);
        PORTALS.put(key(name), def);
        save();
        CretaniaSync.LOGGER.info("[Cretania-Portales] Portal '{}' creado por {} ({} bloques) → {}",
                name, player.getGameProfile().getName(), visited.size(), defaultServer);
        return def;
    }

    /** Crea un portal con nombre automático portal_N (primer número libre). */
    public static PortalDef createAutoNamed(ServerPlayer player) {
        int n = 1;
        while (PORTALS.containsKey(key("portal_" + n))) n++;
        return createFromNearbyPortal(player, "portal_" + n);
    }

    public static boolean delete(String name) {
        boolean removed = PORTALS.remove(key(name)) != null;
        if (removed) save();
        return removed;
    }

    /** Asigna el servidor destino en tiempo real (aplica al instante). */
    public static PortalDef setTargetServer(String name, String server) {
        PortalDef p = get(name);
        if (p == null) return null;
        String s = server.toLowerCase(Locale.ROOT);
        PortalDef updated = new PortalDef(p.name(), p.dimension(),
                p.minX(), p.minY(), p.minZ(), p.maxX(), p.maxY(), p.maxZ(),
                s, p.targetX(), p.targetY(), p.targetZ(), p.targetYaw(), p.targetPitch());
        PORTALS.put(key(name), updated);
        if (!SERVERS.contains(s)) SERVERS.add(s);
        save();
        CretaniaSync.LOGGER.info("[Cretania-Portales] Portal '{}' → server '{}' (en tiempo real)", p.name(), s);
        return updated;
    }

    public static PortalDef setTargetCoords(String name, Double x, Double y, Double z, Float yaw, Float pitch) {
        PortalDef p = get(name);
        if (p == null) return null;
        PortalDef updated = new PortalDef(p.name(), p.dimension(),
                p.minX(), p.minY(), p.minZ(), p.maxX(), p.maxY(), p.maxZ(),
                p.targetServer(), x, y, z, yaw, pitch);
        PORTALS.put(key(name), updated);
        save();
        return updated;
    }

    /** Renombra un portal. Falla (null) si no existe o el nombre nuevo ya está en uso. */
    public static PortalDef rename(String oldName, String newName) {
        PortalDef p = get(oldName);
        if (p == null || newName.isBlank() || get(newName) != null) return null;
        PORTALS.remove(key(oldName));
        PortalDef updated = new PortalDef(newName, p.dimension(),
                p.minX(), p.minY(), p.minZ(), p.maxX(), p.maxY(), p.maxZ(),
                p.targetServer(), p.targetX(), p.targetY(), p.targetZ(),
                p.targetYaw(), p.targetPitch());
        PORTALS.put(key(newName), updated);
        save();
        CretaniaSync.LOGGER.info("[Cretania-Portales] Portal '{}' renombrado a '{}'", oldName, newName);
        return updated;
    }

    // -------------------------------------------------------------------------
    // Detección: jugador parado dentro de bloques de portal registrados
    // -------------------------------------------------------------------------

    public static void onServerTick(ServerTickEvent.Post event) {
        if (PORTALS.isEmpty()) return;

        MinecraftServer server = event.getServer();

        // Cada tick (sin throttle): mientras el jugador esté parado sobre bloques
        // de un portal registrado, se le congela el contador de portal vanilla.
        // Esto evita la carrera que a veces lo teletransportaba al Nether: sin
        // esto, Entity#handlePortal() puede completar su cuenta e iniciar el
        // cambio de dimensión vanilla ANTES de que la transferencia custom
        // (que corre cada CHECK_INTERVAL ticks + delay async) llegue a moverlo.
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            BlockPos feet = player.blockPosition();
            if (!player.level().getBlockState(feet).is(Blocks.NETHER_PORTAL)) continue;

            String dim = player.level().dimension().location().toString();
            for (PortalDef p : PORTALS.values()) {
                if (p.contains(dim, feet)) {
                    player.setPortalCooldown();
                    break;
                }
            }
        }

        if (++tickCounter < CHECK_INTERVAL) return;
        tickCounter = 0;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (ReturnZoneManager.isTransferring(player.getUUID())) continue;

            BlockPos feet = player.blockPosition();
            if (!player.level().getBlockState(feet).is(Blocks.NETHER_PORTAL)) continue;

            String dim = player.level().dimension().location().toString();
            for (PortalDef p : PORTALS.values()) {
                if (!p.contains(dim, feet)) continue;

                CretaniaSync.LOGGER.info("[Cretania-Portales] {} entró al portal '{}' → {}",
                        player.getGameProfile().getName(), p.name(), p.targetServer());

                // Destino literal e instantáneo: coords configuradas si existen,
                // si no, el propio bloque del portal (sin RTP, sin búsqueda de terreno).
                double tx = p.hasTargetCoords() ? p.targetX() : p.landingX();
                double ty = p.hasTargetCoords() ? p.targetY() : p.landingY();
                double tz = p.hasTargetCoords() ? p.targetZ() : p.landingZ();
                float tyaw = p.targetYaw() != null ? p.targetYaw() : player.getYRot();
                float tpitch = p.targetPitch() != null ? p.targetPitch() : player.getXRot();

                // Pseudo-servidor "local": TP dentro de este mismo server, sin pasar
                // por Velocity (reemplaza a las antiguas zonas local_rtp).
                if ("local".equalsIgnoreCase(p.targetServer())) {
                    ReturnZoneManager.markTransferring(player.getUUID(), 8_000);
                    player.displayClientMessage(Component.literal("Teletransportando...")
                            .withStyle(ChatFormatting.LIGHT_PURPLE), true);
                    com.cretania.invsync.network.SyncChannelHandler.applyTeleport(
                            player, tx, ty, tz, tyaw, tpitch);
                    break;
                }

                player.displayClientMessage(Component.literal("Conectando a " + p.targetServer() + "...")
                        .withStyle(ChatFormatting.LIGHT_PURPLE), true);

                ReturnZoneManager.initiateReturnImmediate(player, p.targetServer(), tx, ty, tz, tyaw, tpitch);
                break;
            }
        }
    }

    /**
     * Evita que un portal registrado dispare el viaje vanilla al Nether
     * (por si el server tiene allow-nether=true).
     */
    public static void onDimensionTravel(EntityTravelToDimensionEvent event) {
        if (PORTALS.isEmpty()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        BlockPos feet = player.blockPosition();
        String dim = player.level().dimension().location().toString();
        for (PortalDef p : PORTALS.values()) {
            if (p.contains(dim, feet)) {
                event.setCanceled(true);
                return;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Comandos: /portales (menú OP) + subcomandos de administración
    // -------------------------------------------------------------------------

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        var portalSuggestions = (com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack>)
                (ctx, builder) -> SharedSuggestionProvider.suggest(portalNames(), builder);
        var serverSuggestions = (com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack>)
                (ctx, builder) -> SharedSuggestionProvider.suggest(servers(), builder);

        event.getDispatcher().register(Commands.literal("portales")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    PortalMenus.openMain(player);
                    return 1;
                })
                .then(Commands.literal("crear")
                        .then(Commands.argument("nombre", StringArgumentType.word())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    String name = StringArgumentType.getString(ctx, "nombre");
                                    if (get(name) != null) {
                                        ctx.getSource().sendFailure(Component.literal("Ya existe un portal llamado '" + name + "'."));
                                        return 0;
                                    }
                                    PortalDef def = createFromNearbyPortal(player, name);
                                    if (def == null) {
                                        ctx.getSource().sendFailure(Component.literal(
                                                "No hay bloques de portal del Nether cerca. Construye y enciende el portal primero."));
                                        return 0;
                                    }
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "Portal '" + name + "' registrado → " + def.targetServer()
                                                    + ". Usa /portales para asignar el servidor.")
                                            .withStyle(ChatFormatting.GREEN), true);
                                    return 1;
                                })))
                .then(Commands.literal("eliminar")
                        .then(Commands.argument("nombre", StringArgumentType.word())
                                .suggests(portalSuggestions)
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "nombre");
                                    if (!delete(name)) {
                                        ctx.getSource().sendFailure(Component.literal("No existe el portal '" + name + "'."));
                                        return 0;
                                    }
                                    ctx.getSource().sendSuccess(() -> Component.literal("Portal '" + name + "' eliminado.")
                                            .withStyle(ChatFormatting.YELLOW), true);
                                    return 1;
                                })))
                .then(Commands.literal("servidor")
                        .then(Commands.argument("nombre", StringArgumentType.word())
                                .suggests(portalSuggestions)
                                .then(Commands.argument("server", StringArgumentType.word())
                                        .suggests(serverSuggestions)
                                        .executes(ctx -> {
                                            String name = StringArgumentType.getString(ctx, "nombre");
                                            String server = StringArgumentType.getString(ctx, "server");
                                            PortalDef def = setTargetServer(name, server);
                                            if (def == null) {
                                                ctx.getSource().sendFailure(Component.literal("No existe el portal '" + name + "'."));
                                                return 0;
                                            }
                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                    "Portal '" + def.name() + "' → " + def.targetServer() + " (aplicado en tiempo real).")
                                                    .withStyle(ChatFormatting.GREEN), true);
                                            return 1;
                                        }))))
                .then(Commands.literal("coords")
                        .then(Commands.argument("nombre", StringArgumentType.word())
                                .suggests(portalSuggestions)
                                .then(Commands.literal("clear")
                                        .executes(ctx -> {
                                            String name = StringArgumentType.getString(ctx, "nombre");
                                            if (setTargetCoords(name, null, null, null, null, null) == null) {
                                                ctx.getSource().sendFailure(Component.literal("No existe el portal '" + name + "'."));
                                                return 0;
                                            }
                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                    "Portal '" + name + "': coords destino eliminadas (TP instantáneo al propio portal).")
                                                    .withStyle(ChatFormatting.YELLOW), true);
                                            return 1;
                                        }))
                                .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                                .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                        .executes(ctx -> setCoordsCommand(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "nombre"),
                                                                DoubleArgumentType.getDouble(ctx, "x"),
                                                                DoubleArgumentType.getDouble(ctx, "y"),
                                                                DoubleArgumentType.getDouble(ctx, "z"),
                                                                null, null))
                                                        .then(Commands.argument("yaw", FloatArgumentType.floatArg())
                                                                .then(Commands.argument("pitch", FloatArgumentType.floatArg())
                                                                        .executes(ctx -> setCoordsCommand(ctx.getSource(),
                                                                                StringArgumentType.getString(ctx, "nombre"),
                                                                                DoubleArgumentType.getDouble(ctx, "x"),
                                                                                DoubleArgumentType.getDouble(ctx, "y"),
                                                                                DoubleArgumentType.getDouble(ctx, "z"),
                                                                                FloatArgumentType.getFloat(ctx, "yaw"),
                                                                                FloatArgumentType.getFloat(ctx, "pitch"))))))))))
                .then(Commands.literal("servidores")
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "Servidores conocidos: " + String.join(", ", servers())), false);
                            return 1;
                        })
                        .then(Commands.literal("add")
                                .then(Commands.argument("server", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String server = StringArgumentType.getString(ctx, "server");
                                            if (!addServer(server)) {
                                                ctx.getSource().sendFailure(Component.literal("'" + server + "' ya está en la lista."));
                                                return 0;
                                            }
                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                    "Servidor '" + server + "' agregado.").withStyle(ChatFormatting.GREEN), true);
                                            return 1;
                                        })))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("server", StringArgumentType.word())
                                        .suggests(serverSuggestions)
                                        .executes(ctx -> {
                                            String server = StringArgumentType.getString(ctx, "server");
                                            if (!removeServer(server)) {
                                                ctx.getSource().sendFailure(Component.literal("'" + server + "' no está en la lista."));
                                                return 0;
                                            }
                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                    "Servidor '" + server + "' eliminado de la lista.").withStyle(ChatFormatting.YELLOW), true);
                                            return 1;
                                        }))))
                .then(Commands.literal("lista")
                        .executes(ctx -> {
                            List<PortalDef> list = portalsSorted();
                            if (list.isEmpty()) {
                                ctx.getSource().sendSuccess(() -> Component.literal("No hay portales registrados."), false);
                                return 0;
                            }
                            for (PortalDef p : list) {
                                ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                                        "%s [%s] (%d,%d,%d)-(%d,%d,%d) → %s [%s]",
                                        p.name(), p.dimension(),
                                        p.minX(), p.minY(), p.minZ(), p.maxX(), p.maxY(), p.maxZ(),
                                        p.targetServer(), p.modeDescription())), false);
                            }
                            return list.size();
                        }))
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            load();
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "Portales recargados: " + PORTALS.size() + " portal(es).")
                                    .withStyle(ChatFormatting.GREEN), true);
                            return 1;
                        })));
    }

    private static int setCoordsCommand(CommandSourceStack source, String name,
                                        double x, double y, double z, Float yaw, Float pitch) {
        PortalDef def = setTargetCoords(name, x, y, z, yaw, pitch);
        if (def == null) {
            source.sendFailure(Component.literal("No existe el portal '" + name + "'."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(String.format(Locale.US,
                "Portal '%s': destino fijado en (%.1f, %.1f, %.1f).", name, x, y, z))
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }
}
