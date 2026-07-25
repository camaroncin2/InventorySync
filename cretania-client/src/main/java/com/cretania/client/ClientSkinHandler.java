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

        LOGGER.debug("[CretaniaClient] SkinClientPayload recibido para {} ({} chars)", uuid, value.length());

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
        if (mc.hasSingleplayerServer()) return; // en mundos locales no hay nada que probar
        try {
            UUID ownUuid = mc.player.getUUID();

            // 1. PRIORIDAD: la textura FIRMADA por Mojang del GameProfile local (sesión
            //    premium real). Antes se enviaba primero la caché — pero AuthMod mete su
            //    CRACKED_SKIN en esa caché incluso para jugadores premium, así que al
            //    reconectar se re-enviaba una skin sin firma que el servidor persistía
            //    como "cracked", PISANDO la skin premium en la base. Ese era el motivo de
            //    que las skins premium se perdieran tras reinicios/reconexiones.
            Collection<Property> textures = mc.player.getGameProfile().getProperties().get("textures");
            if (textures != null && !textures.isEmpty()) {
                Property prop = textures.iterator().next();
                if (prop.hasSignature() && !prop.value().isBlank()) {
                    sendOwnSkin(prop.value(), prop.signature(), "GameProfile firmado");
                    return;
                }
            }

            // Sin firma local. Minecraft descarga el perfil propio de Mojang UNA sola vez
            // al arrancar el juego: si esa descarga falló (red inestable, rate-limit), la
            // sesión queda SIN textura firmada para siempre aunque la cuenta Microsoft
            // esté iniciada — y el jugador premium nunca puede demostrarlo. Recuperación:
            // pedirla nosotros mismos a sessionserver con el UUID real de la sesión.
            startOwnProfileRecovery(mc);

            // 2. Caché de sesión (skin custom / CRACKED_SKIN) — clientes sin firma propia.
            SkinData cachedSkin = SKIN_CACHE.get(ownUuid);
            if (cachedSkin != null) {
                sendOwnSkin(cachedSkin.value(), cachedSkin.signature(), "caché");
                return;
            }

            // 3. Último recurso: textura sin firmar del GameProfile (algunos launchers la inyectan).
            if (textures != null && !textures.isEmpty()) {
                Property prop = textures.iterator().next();
                if (!prop.value().isBlank()) {
                    sendOwnSkin(prop.value(), "", "GameProfile sin firma");
                    return;
                }
            }
            LOGGER.debug("[CretaniaClient] Sin skin local — se omite C2S_SKIN");
        } catch (Exception e) {
            LOGGER.warn("[CretaniaClient] Error enviando C2S_SKIN: {}", e.getMessage());
        }
    }

    /** Evita lanzar dos recuperaciones en paralelo. */
    private static final java.util.concurrent.atomic.AtomicBoolean RECOVERY_IN_FLIGHT =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Recupera la textura firmada de la PROPIA cuenta desde sessionserver.mojang.com,
     * usando el UUID real de la sesión iniciada (no el offline del servidor). Si la
     * cuenta es premium, la textura llega firmada y se envía como prueba (authmod:check
     * + cretania:sync). Para cuentas no premium el perfil no existe (404) y no pasa nada.
     */
    private static void startOwnProfileRecovery(Minecraft mc) {
        if (!RECOVERY_IN_FLIGHT.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try {
                UUID sessionId = mc.getUser().getProfileId();
                LOGGER.info("[CretaniaClient] Perfil local sin textura firmada — consultando sessionserver para {}...", sessionId);
                var result = mc.getMinecraftSessionService().fetchProfile(sessionId, true);
                if (result == null) {
                    LOGGER.info("[CretaniaClient] sessionserver no conoce {} — cuenta no premium, sin prueba que enviar.", sessionId);
                    return;
                }
                Collection<Property> texs = result.profile().getProperties().get("textures");
                if (texs == null || texs.isEmpty()) {
                    LOGGER.warn("[CretaniaClient] Perfil de {} recuperado pero sin texturas.", sessionId);
                    return;
                }
                Property prop = texs.iterator().next();
                if (!prop.hasSignature() || prop.value().isBlank()) {
                    LOGGER.warn("[CretaniaClient] Perfil de {} recuperado pero la textura no viene firmada.", sessionId);
                    return;
                }
                mc.execute(() -> {
                    if (mc.getConnection() == null) return; // ya se desconectó
                    sendOwnSkin(prop.value(), prop.signature(), "sessionserver (recuperada)");
                });
            } catch (Exception e) {
                LOGGER.warn("[CretaniaClient] Recuperación de perfil propio falló: {}", e.toString());
            } finally {
                RECOVERY_IN_FLIGHT.set(false);
            }
        }, "cretania-own-profile");
        t.setDaemon(true);
        t.start();
    }

    /** Envía la skin propia por ambos canales (cretania:sync + authmod:check). */
    private static void sendOwnSkin(String value, String signature, String source) {
        String message = "C2S_SKIN:" + value + ":" + (signature == null ? "" : signature);
        PacketDistributor.sendToServer(new ClientSyncPayload(message.getBytes(StandardCharsets.UTF_8)));
        // También vía authmod:check para que AuthMod la cachee antes del /login.
        PacketDistributor.sendToServer(new ClientAuthChannelPayload(message.getBytes(StandardCharsets.UTF_8)));
        LOGGER.info("[CretaniaClient] C2S_SKIN enviada al servidor ({} chars, origen: {})", value.length(), source);

        // Programar reenvíos de la prueba por authmod:check: el envío único original
        // corría una carrera con el chequeo de AuthMod en el servidor — si AuthMod
        // evaluaba antes de que llegara este paquete, caía a su vía lenta (API de
        // Mojang, 30-60 s). Reenviando en los primeros segundos, AuthMod recibe la
        // skin firmada aunque el primer paquete haya perdido la carrera.
        authResendMessage = message;
        authResendTicks = 0;
    }

    // ─────────────── Reenvío de la prueba premium (authmod:check) ───────────────

    private static volatile String authResendMessage = null;
    private static int authResendTicks = 0;
    /** Ticks (tras el login) en los que se reenvía: 1 s, 3 s y 7 s. */
    private static final int[] AUTH_RESEND_AT = {20, 60, 140};

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        if (authResendMessage == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null || mc.player == null) {
            authResendMessage = null;
            return;
        }

        authResendTicks++;
        for (int at : AUTH_RESEND_AT) {
            if (authResendTicks == at) {
                try {
                    PacketDistributor.sendToServer(new ClientAuthChannelPayload(
                            authResendMessage.getBytes(StandardCharsets.UTF_8)));
                    LOGGER.debug("[CretaniaClient] Prueba authmod:check reenviada (tick {})", at);
                } catch (Exception e) {
                    LOGGER.debug("[CretaniaClient] Reenvío authmod:check falló: {}", e.toString());
                    authResendMessage = null;
                }
                break;
            }
        }
        if (authResendTicks > AUTH_RESEND_AT[AUTH_RESEND_AT.length - 1]) {
            authResendMessage = null;
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        authResendMessage = null;
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

            // Una skin SIN firma nunca reemplaza una skin FIRMADA ya conocida para ese
            // jugador: AuthMod manda CRACKED_SKIN también a cuentas premium, y aceptarla
            // degradaba la skin premium (visualmente y, vía re-envío, en la base).
            SkinData existing = SKIN_CACHE.get(uuid);
            boolean incomingSigned = !signature.isBlank();
            boolean existingSigned = existing != null && existing.signature() != null && !existing.signature().isBlank();
            if (existingSigned && !incomingSigned) {
                LOGGER.info("[CretaniaClient] CRACKED_SKIN sin firma para {} ignorada — ya hay skin firmada.", uuid);
                return;
            }

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
            LOGGER.debug("[CretaniaClient] applyToConnection: PlayerInfo no disponible aún para {} — se reintentará en EntityJoinLevelEvent", uuid);
            return;
        }

        // 1. Inyectar textura premium en el GameProfile del PlayerInfo
        GameProfile profile = info.getProfile();
        if (!uuid.equals(profile.getId())) {
            LOGGER.warn("[CretaniaClient] applyToConnection: MISMATCH — pedí PlayerInfo de {} pero su GameProfile tiene id={} name={}",
                    uuid, profile.getId(), profile.getName());
        }
        profile.getProperties().removeAll("textures");
        if (signature != null && !signature.isEmpty()) {
            profile.getProperties().put("textures", new Property("textures", value, signature));
        } else {
            profile.getProperties().put("textures", new Property("textures", value));
        }

        // 2. Reemplazar PlayerInfo.skinLookup por uno nuevo.
        //    CAUSA RAÍZ real (confirmada por log): skinLookup es un Supplier<PlayerSkin>
        //    memoizado UNA SOLA VEZ en el constructor de PlayerInfo (Suppliers.memoize).
        //    Una vez que algo llama getSkin() la primera vez, el resultado queda fijo para
        //    siempre — mutar el GameProfile después de eso no tiene ningún efecto, porque
        //    el supplier memoizado ni siquiera vuelve a leer el profile. Limpiar cachés del
        //    SkinManager (lo que se intentaba antes) no ayuda: el CompletableFuture ya
        //    resuelto queda capturado directamente en el closure del supplier viejo.
        replaceSkinLookup(mc, info, profile, uuid);

        LOGGER.debug("[CretaniaClient] Skin aplicada para {}", uuid);
    }

    /**
     * Reemplaza PlayerInfo.skinLookup (privado y memoizado para siempre por Mojang) con un
     * supplier fresco equivalente al que arma internamente PlayerInfo, pero construido con
     * el GameProfile YA actualizado. AbstractClientPlayer.getSkin() delega en
     * PlayerInfo.getSkin(), así que esto corrige tanto la vista de otros jugadores como la
     * del jugador propio (mismo objeto PlayerInfo en ambos casos) sin tocar nada más.
     */
    private static void replaceSkinLookup(Minecraft mc, PlayerInfo info, GameProfile profile, UUID uuid) {
        try {
            java.util.function.Supplier<net.minecraft.client.resources.PlayerSkin> fresh = () -> {
                java.util.concurrent.CompletableFuture<net.minecraft.client.resources.PlayerSkin> future =
                        mc.getSkinManager().getOrLoad(profile);
                net.minecraft.client.resources.PlayerSkin fallback =
                        net.minecraft.client.resources.DefaultPlayerSkin.get(profile);
                net.minecraft.client.resources.PlayerSkin resolved = future.getNow(fallback);
                boolean remote = !mc.isLocalPlayer(profile.getId());
                return remote && !resolved.secure() ? fallback : resolved;
            };
            Field field = PlayerInfo.class.getDeclaredField("skinLookup");
            field.setAccessible(true);
            field.set(info, fresh);
        } catch (Exception e) {
            LOGGER.warn("[CretaniaClient] No se pudo reemplazar skinLookup para {}: {}", uuid, e);
        }
    }

    // ─────────────────────── SkinData record ───────────────────────────────

    private record SkinData(String value, String signature) {}
}
