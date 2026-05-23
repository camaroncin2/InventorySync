package com.cretania.velocitysync;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.util.GameProfile;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Escucha el evento de login y:
 *  1. Llama a Mojang para saber si el jugador es premium.
 *  2. Si es premium, inyecta la skin firmada por Mojang en el GameProfile.
 *
 * Esto sucede mientras el cliente ve la pantalla "Logging in…",
 * así que la skin llega correcta desde el primer spawn — sin respawn, sin flash.
 * El resultado se guarda en caché para que PlayerConnectionListener lo use
 * al enviar la decisión a AuthMod sin repetir la llamada HTTP.
 */
public class AuthProfileListener {

    private final Logger logger;

    /** username.toLowerCase() → es premium */
    private final Map<String, Boolean>              premiumCache     = new ConcurrentHashMap<>();
    /** username.toLowerCase() → skin premium firmada por Mojang */
    private final Map<String, MojangAPI.SkinProperty> skinCache     = new ConcurrentHashMap<>();
    /** username.toLowerCase() → skin cracked enviada desde lobby (authmod:check CRACKED_SKIN) */
    private final Map<String, MojangAPI.SkinProperty> crackedSkinCache = new ConcurrentHashMap<>();

    public AuthProfileListener(Logger logger) {
        this.logger = logger;
    }

    // ── Evento de login ─────────────────────────────────────────────────────

    @Subscribe
    public EventTask onGameProfileRequest(GameProfileRequestEvent event) {
        return EventTask.async(() -> {
            String username = event.getUsername();
            MojangAPI.MojangProfile profile;
            try {
                profile = MojangAPI.fetchProfile(username);
            } catch (java.io.IOException e) {
                // Error de red / rate-limit (429) → NO cachear; el jugador reconectará
                logger.warn("[Cretania-Auth] Error API Mojang para {} ({}). No se cachea — se reintentará.",
                        username, e.getMessage());
                return;
            }

            boolean isPremium = profile != null;
            premiumCache.put(username.toLowerCase(), isPremium);
            skinCache.remove(username.toLowerCase());

            if (!isPremium) {
                logger.info("[Cretania-Auth] {} → CRACKED (Mojang no lo encontró)", username);
                return;
            }

            logger.info("[Cretania-Auth] {} → PREMIUM, inyectando skin en GameProfile", username);

            // Inyectar skin en el GameProfile que Velocity enviará al backend
            if (profile.skin() != null) {
                skinCache.put(username.toLowerCase(), profile.skin()); // guardar para otros servidores
                GameProfile original = event.getGameProfile();
                List<GameProfile.Property> props = new ArrayList<>(original.getProperties());
                props.removeIf(p -> p.getName().equals("textures"));
                props.add(new GameProfile.Property(
                        "textures",
                        profile.skin().value(),
                        profile.skin().signature()
                ));
                event.setGameProfile(new GameProfile(original.getId(), original.getName(), props));
                logger.info("[Cretania-Auth] Skin inyectada en GameProfile de {} (value len={})",
                        username, profile.skin().value().length());
            } else {
                logger.warn("[Cretania-Auth] {} es PREMIUM pero fetchSkin devolvió null — sin skin custom", username);
            }
        });
    }

    // ── Limpieza de caché ───────────────────────────────────────────────────

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        String key = event.getPlayer().getUsername().toLowerCase();
        premiumCache.remove(key);
        skinCache.remove(key);
        crackedSkinCache.remove(key);
    }

    // ── API para PlayerConnectionListener ───────────────────────────────────

    /** Devuelve si el jugador es premium según la caché del evento de login. */
    public boolean isPremium(String username) {
        return premiumCache.getOrDefault(username.toLowerCase(), false);
    }

    /** Devuelve true si ya hay un resultado cacheado (exitoso o negativo) para este username. */
    public boolean hasCachedResult(String username) {
        return premiumCache.containsKey(username.toLowerCase());
    }

    /** Permite al caller forzar un valor en caché (p.ej. tras reintento externo). */
    public void forceCache(String username, MojangAPI.MojangProfile profile) {
        boolean isPremium = profile != null;
        premiumCache.put(username.toLowerCase(), isPremium);
        if (profile != null && profile.skin() != null) {
            skinCache.put(username.toLowerCase(), profile.skin());
        }
    }

    /** Devuelve la skin cacheada (puede ser null si no premium o API falló). */
    public MojangAPI.SkinProperty getSkin(String username) {
        return skinCache.get(username.toLowerCase());
    }

    /** Almacena la skin de un jugador cracked enviada desde el lobby. */
    public void setCrackedSkin(String username, MojangAPI.SkinProperty skin) {
        crackedSkinCache.put(username.toLowerCase(), skin);
        logger.info("[Cretania-Skin] Skin cracked cacheada para {}", username);
    }

    /** Devuelve la skin cracked del jugador (null si no tiene o no la envió el lobby). */
    public MojangAPI.SkinProperty getCrackedSkin(String username) {
        return crackedSkinCache.get(username.toLowerCase());
    }
}
