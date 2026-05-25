package com.cretania.client;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maneja los paquetes SkinClientPayload enviados por el servidor.
 *
 * Funciones clave:
 * 1. Recibe el payload → actualiza PlayerInfo.profile + limpia caché de skin
 * 2. Persiste la skin en SKIN_CACHE (sobrevive cambios de servidor)
 * 3. EntityJoinLevelEvent: aplica skin cacheada en cuanto aparece la entidad
 *    (garantiza skin correcta ANTES de que llegue el mensaje del nuevo servidor)
 */
@EventBusSubscriber(modid = CretaniaClientMod.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public class ClientSkinHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CretaniaClientMod.MOD_ID);

    /** Caché persistente UUID → SkinData (sobrevive cambios de servidor dentro de la sesión) */
    private static final Map<UUID, SkinData> SKIN_CACHE = new ConcurrentHashMap<>();

    // ─────────────────────── Handler S2C payload ───────────────────────────

    public static void handleSkinPayload(SkinClientPayload payload, IPayloadContext context) {
        UUID uuid = payload.uuid();
        String value = payload.value();
        String signature = payload.signature();

        // Guardar en caché para persistencia cross-server
        SKIN_CACHE.put(uuid, new SkinData(value, signature));

        // Aplicar en el hilo principal
        context.enqueueWork(() -> applyToConnection(uuid, value, signature));
    }

    // ─────────────────────── EntityJoinLevelEvent ──────────────────────────

    /**
     * Cuando aparece una entidad jugador en el nivel del cliente,
     * aplicamos inmediatamente la skin cacheada sin esperar al SKIN message del nuevo servidor.
     * Esto elimina el parpadeo de skin al cambiar de servidor (lobby → survival1, etc.).
     */
    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof AbstractClientPlayer player)) return;

        UUID uuid = player.getUUID();
        SkinData cached = SKIN_CACHE.get(uuid);
        if (cached == null) return;

        // Aplicar en el mismo tick (ya estamos en hilo principal)
        applyToConnection(uuid, cached.value(), cached.signature());
    }

    // ──────────────────── Envío skin propio al servidor ────────────────────

    /**
     * Al conectar a cualquier servidor, el cliente envía su skin local directamente.
     * El servidor la aplica al instante y la cachea en Velocity — sin esperar a Mojang API.
     * Funciona para jugadores premium (skin firmada por Mojang) y cracked (skin custom).
     */
    @SubscribeEvent
    public static void onPlayerLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        try {
            UUID ownUuid = mc.player.getUUID();

            // Primero: verificar si hay skin personalizada en caché (ej. de /skin Notch).
            // Esto garantiza que la skin custom persista al cambiar de servidor.
            SkinData cachedSkin = SKIN_CACHE.get(ownUuid);
            if (cachedSkin != null) {
                String message = "C2S_SKIN:" + cachedSkin.value() + ":" + cachedSkin.signature();
                PacketDistributor.sendToServer(
                        new ClientSyncPayload(message.getBytes(StandardCharsets.UTF_8))
                );
                PacketDistributor.sendToServer(
                        new ClientAuthChannelPayload(message.getBytes(StandardCharsets.UTF_8))
                );
                LOGGER.info("[CretaniaClient] C2S_SKIN (desde caché) enviada al servidor ({} chars)", cachedSkin.value().length());
                return;
            }

            // Fallback: leer skin del GameProfile local (jugadores premium).
            Collection<Property> textures = mc.player.getGameProfile().getProperties().get("textures");
            if (textures == null || textures.isEmpty()) {
                LOGGER.debug("[CretaniaClient] Sin skin en GameProfile local — se omite C2S_SKIN");
                return;
            }
            Property prop = textures.iterator().next();
            String value = prop.value();
            String signature = prop.hasSignature() ? prop.signature() : "";
            if (value.isBlank()) return;

            String message = "C2S_SKIN:" + value + ":" + signature;
            PacketDistributor.sendToServer(
                    new ClientSyncPayload(message.getBytes(StandardCharsets.UTF_8))
            );
            // También enviar vía authmod:check para que AuthMod la cachee antes del /login.
            // Esto permite asignar skin al instante sin esperar Mojang API.
            PacketDistributor.sendToServer(
                    new ClientAuthChannelPayload(message.getBytes(StandardCharsets.UTF_8))
            );
            LOGGER.info("[CretaniaClient] C2S_SKIN enviada al servidor ({} chars valor)", value.length());
        } catch (Exception e) {
            LOGGER.warn("[CretaniaClient] Error enviando C2S_SKIN: {}", e.getMessage());
        }
    }

    /**
     * Procesa el mensaje CRACKED_SKIN reenviado por Velocity al cliente.
     * Formato: CRACKED_SKIN:<uuid>:<value>:<signature>
     *
     * Almacena la skin en SKIN_CACHE para:
     * 1. Aplicarla al jugador inmediatamente (sin respawn/recarga de mundo).
     * 2. Persistir la skin al cambiar de servidor (onPlayerLogin la leerá del caché).
     */
    public static void handleCrackedSkin(String message) {
        // Formato: CRACKED_SKIN:<uuid>:<value>:<signature>
        // La firma puede contener '=' así que limitamos split a 4 partes
        String[] parts = message.split(":", 4);
        if (parts.length < 4) {
            LOGGER.warn("[CretaniaClient] CRACKED_SKIN mal formado: {}", message);
            return;
        }
        try {
            UUID uuid = UUID.fromString(parts[1]);
            String value = parts[2];
            String signature = parts[3];

            SKIN_CACHE.put(uuid, new SkinData(value, signature));
            LOGGER.info("[CretaniaClient] CRACKED_SKIN recibida para {}, aplicando...", uuid);
            applyToConnection(uuid, value, signature);
        } catch (Exception e) {
            LOGGER.warn("[CretaniaClient] Error procesando CRACKED_SKIN: {}", e.getMessage());
        }
    }

    // ─────────────────────── Lógica de aplicación ──────────────────────────

    /**
     * Actualiza el GameProfile en PlayerInfo y limpia la caché de PlayerSkin
     * para que el renderer descargue la skin premium en el próximo frame.
     */
    private static void applyToConnection(UUID uuid, String value, String signature) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;

        PlayerInfo info = mc.getConnection().getPlayerInfo(uuid);
        if (info == null) {
            // PlayerInfo aún no existe (join en progreso); el EntityJoinLevelEvent lo reintentará
            LOGGER.debug("[CretaniaClient] PlayerInfo no disponible aún para {}", uuid);
            return;
        }

        // 1. Inyectar textura premium en el GameProfile del PlayerInfo
        GameProfile profile = info.getProfile();
        profile.getProperties().removeAll("textures");
        if (signature != null && !signature.isEmpty()) {
            profile.getProperties().put("textures", new Property("textures", value, signature));
        } else {
            profile.getProperties().put("textures", new Property("textures", value));
        }

        // 2. Invalidar el caché del SkinManager: está keyed por UUID y devolvería la skin
        //    anterior (o la default) si no lo limpiamos antes de la re-descarga.
        invalidateSkinManagerCache(mc, uuid);

        // 3. Limpiar el PlayerSkin cacheado en PlayerInfo para forzar re-descarga
        clearFieldsOfSimpleType(info, "PlayerSkin");

        // 4. Limpiar también cualquier caché en la entidad en el nivel
        if (mc.level != null) {
            for (AbstractClientPlayer player : mc.level.players()) {
                if (player.getUUID().equals(uuid)) {
                    clearFieldsOfSimpleType(player, "PlayerSkin");
                    // 5. Para el jugador propio: limpiar también el PlayerInfo cacheado
                    //    en AbstractClientPlayer. Sin esto, LocalPlayer.getPlayerInfo()
                    //    devuelve el objeto cacheado y, aunque sus campos internos cambien,
                    //    el SkinManager puede haber cacheado la PlayerSkin por identidad
                    //    de PlayerInfo. Nulearlo fuerza un re-lookup en la connection map.
                    if (mc.player != null && uuid.equals(mc.player.getUUID())) {
                        clearFieldsOfSimpleType(player, "PlayerInfo");
                    }
                    break;
                }
            }
        }

        LOGGER.debug("[CretaniaClient] Skin aplicada para {}", uuid);
    }

    /**
     * Invalida el caché interno del SkinManager de Minecraft para el UUID dado.
     *
     * El SkinManager guarda un Future<PlayerSkin> keyed por UUID (o GameProfile).
     * Si no lo eliminamos, la próxima llamada a getSkin() devuelve la skin cacheada
     * (Steve/Alex por defecto) aunque el GameProfile ya tenga la textura correcta.
     *
     * Estrategia doble:
     *   1. Elimina de cualquier Map<?> que esté keyed por UUID.
     *   2. Elimina de caches Guava (expuestas via asMap()) keyed por UUID o GameProfile.
     */
    private static void invalidateSkinManagerCache(Minecraft mc, UUID uuid) {
        try {
            Object skinMgr = mc.getSkinManager();
            for (Class<?> c = skinMgr.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    f.setAccessible(true);
                    try {
                        Object val = f.get(skinMgr);
                        if (val == null) continue;

                        // Caso 1: Map<UUID, ?> directo (ej. ConcurrentHashMap)
                        if (val instanceof Map<?, ?> map) {
                            map.remove(uuid);
                        }

                        // Caso 2: Guava LoadingCache — exponer como Map vía asMap()
                        try {
                            Method asMapMethod = val.getClass().getMethod("asMap");
                            Map<?, ?> view = (Map<?, ?>) asMapMethod.invoke(val);
                            view.entrySet().removeIf(e -> {
                                Object key = e.getKey();
                                return uuid.equals(key)
                                        || (key instanceof GameProfile gp && uuid.equals(gp.getId()));
                            });
                        } catch (NoSuchMethodException ignored) {
                            // No es un Guava Cache
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * Limpia todos los campos cuyo tipo tenga el nombre simple indicado,
     * recorriendo la jerarquía de clases del objeto.
     * Usado para borrar campos de tipo PlayerSkin (cuyo nombre no cambia entre builds)
     * sin depender de nombres de campo (que sí cambian con obfuscation).
     */
    private static void clearFieldsOfSimpleType(Object obj, String simpleTypeName) {
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (field.getType().getSimpleName().equals(simpleTypeName)) {
                    try {
                        field.setAccessible(true);
                        field.set(obj, null);
                    } catch (Exception ignored) {
                        // InaccessibleObjectException en ciertos JVMs: no fatal
                    }
                }
            }
        }
    }

    // ─────────────────────── SkinData record ───────────────────────────────

    private record SkinData(String value, String signature) {}
}
