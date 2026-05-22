package com.cretania.invsync.logic;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;

/**
 * Administra el estado de bloqueo de jugadores durante la sincronización.
 * Utiliza un ConcurrentHashMap para seguridad en hilos (thread-safe).
 * 
 * Mientras un jugador está bloqueado (esperando la carga de datos desde MySQL):
 *   - No puede tirar items
 *   - No puede interactuar con bloques/entidades
 *   - No puede atacar
 *   - No puede abrir contenedores
 *   - No recibe daño
 * 
 * Esto previene exploits de duplicación de items durante la ventana de sincronización.
 */
public class SyncStateManager {

    private static final ConcurrentHashMap<UUID, Boolean> PENDING_PLAYERS = new ConcurrentHashMap<>();

    public static void lock(UUID uuid) {
        PENDING_PLAYERS.put(uuid, true);
    }

    public static void unlock(UUID uuid) {
        PENDING_PLAYERS.remove(uuid);
    }

    public static boolean isLocked(UUID uuid) {
        return PENDING_PLAYERS.getOrDefault(uuid, false);
    }

    /**
     * Bloquea al jugador de tirar items mientras sincroniza.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onItemToss(ItemTossEvent event) {
        if (isLocked(event.getPlayer().getUUID())) {
            event.setCanceled(true);
        }
    }

    /**
     * Bloquea interacciones con bloques (click derecho).
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onBlockInteract(PlayerInteractEvent.RightClickBlock event) {
        if (isLocked(event.getEntity().getUUID())) {
            event.setCanceled(true);
        }
    }

    /**
     * Bloquea interacciones con entidades.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (isLocked(event.getEntity().getUUID())) {
            event.setCanceled(true);
        }
    }

    /**
     * Bloquea uso de items (click derecho con item en mano).
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onItemUse(PlayerInteractEvent.RightClickItem event) {
        if (isLocked(event.getEntity().getUUID())) {
            event.setCanceled(true);
        }
    }

    /**
     * Bloquea ataques a entidades.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onAttackEntity(AttackEntityEvent event) {
        if (isLocked(event.getEntity().getUUID())) {
            event.setCanceled(true);
        }
    }

    /**
     * Bloquea apertura de contenedores (cofres, hornos, etc.).
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onContainerOpen(PlayerContainerEvent.Open event) {
        if (isLocked(event.getEntity().getUUID())) {
            // Cerrar el contenedor inmediatamente
            event.getEntity().closeContainer();
        }
    }

    /**
     * Previene que el jugador reciba daño mientras está bloqueado.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPlayerDamage(LivingDamageEvent.Pre event) {
        if (event.getEntity() instanceof Player player) {
            if (isLocked(player.getUUID())) {
                event.setNewDamage(0);
            }
        }
    }
}
