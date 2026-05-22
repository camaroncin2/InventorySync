package com.cretania.velocitysync;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Administra los estados de sincronización de los jugadores en el proxy.
 * Actúa como semáforo entre el servidor de origen (que guarda) y el servidor
 * de destino (que carga).
 * 
 * Estados posibles:
 *   - SAVING: El servidor de origen está guardando los datos en MySQL
 *   - READY: El guardado se completó, el servidor de destino puede cargar
 *   - NONE: Sin estado activo (estado por defecto)
 */
public class PlayerSyncState {

    public enum State {
        NONE,
        SAVING,
        READY
    }

    private static final ConcurrentHashMap<UUID, State> PLAYER_STATES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, String> PLAYER_ORIGIN_SERVER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, String> PLAYER_TARGET_SERVER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, String> PLAYER_TARGET_GROUP = new ConcurrentHashMap<>();

    /**
     * Marca que un jugador está en proceso de guardado.
     * @param uuid UUID del jugador
     * @param originServer Nombre del servidor de origen
     */
    public static void markSaving(UUID uuid, String originServer, String targetServer, String targetGroup) {
        PLAYER_STATES.put(uuid, State.SAVING);
        PLAYER_ORIGIN_SERVER.put(uuid, originServer);
        PLAYER_TARGET_SERVER.put(uuid, targetServer);
        PLAYER_TARGET_GROUP.put(uuid, targetGroup);
    }

    /**
     * Marca que el guardado se completó y los datos están listos para cargarse.
     * @param uuid UUID del jugador
     */
    public static void markReady(UUID uuid) {
        PLAYER_STATES.put(uuid, State.READY);
    }

    /**
     * Limpia el estado de un jugador (tras carga exitosa o desconexión).
     * @param uuid UUID del jugador
     */
    public static void clear(UUID uuid) {
        PLAYER_STATES.remove(uuid);
        PLAYER_ORIGIN_SERVER.remove(uuid);
        PLAYER_TARGET_SERVER.remove(uuid);
        PLAYER_TARGET_GROUP.remove(uuid);
    }

    /**
     * Obtiene el estado actual de sincronización de un jugador.
     */
    public static State getState(UUID uuid) {
        return PLAYER_STATES.getOrDefault(uuid, State.NONE);
    }

    /**
     * Obtiene el servidor de origen del jugador durante la sincronización.
     */
    public static String getOriginServer(UUID uuid) {
        return PLAYER_ORIGIN_SERVER.get(uuid);
    }

    public static String getTargetServer(UUID uuid) {
        return PLAYER_TARGET_SERVER.get(uuid);
    }

    public static String getTargetGroup(UUID uuid) {
        return PLAYER_TARGET_GROUP.get(uuid);
    }

    /**
     * Verifica si un jugador tiene un proceso de sincronización activo.
     */
    public static boolean isActive(UUID uuid) {
        State state = getState(uuid);
        return state == State.SAVING || state == State.READY;
    }
}
