package com.cretania.client;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
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

        // 2. Limpiar el PlayerSkin cacheado en PlayerInfo para forzar re-descarga
        clearFieldsOfSimpleType(info, "PlayerSkin");

        // 3. Limpiar también cualquier caché en la entidad en el nivel
        if (mc.level != null) {
            for (AbstractClientPlayer player : mc.level.players()) {
                if (player.getUUID().equals(uuid)) {
                    clearFieldsOfSimpleType(player, "PlayerSkin");
                    break;
                }
            }
        }

        LOGGER.debug("[CretaniaClient] Skin aplicada para {}", uuid);
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
