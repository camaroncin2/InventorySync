package com.cretania.invsync.visual;

import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.server.level.ServerPlayer;

/**
 * Feedback visual 100% Server-Side para indicar al jugador que su perfil
 * se está sincronizando. Utiliza paquetes de título y efecto de ceguera Vanilla.
 * 
 * No requiere mods en el cliente del jugador. Todo se envía como paquetes estándar.
 */
public class SyncVisuals {

    // Duración de ceguera en ticks (20 ticks = 1 segundo). 
    // Se pone larga para cubrir el tiempo de carga; se elimina manualmente al terminar.
    private static final int BLINDNESS_DURATION_TICKS = 600; // 30 segundos máx

    /**
     * Aplica los efectos visuales de carga al jugador:
     *   - Título "Sincronizando perfil..."
     *   - Subtítulo "Por favor espera..."
     *   - Efecto de ceguera (pantalla oscura)
     */
    public static void applyLoadingVisuals(ServerPlayer player) {
        // Configurar animación del título (fade in, stay, fade out)
        player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 200, 10));

        // Título principal
        player.connection.send(new ClientboundSetTitleTextPacket(
                Component.literal("§6Sincronizando perfil...")));

        // Subtítulo
        player.connection.send(new ClientboundSetSubtitleTextPacket(
                Component.literal("§7Por favor espera...")));

        // Ceguera para ocultar el mundo mientras se carga
        player.addEffect(new MobEffectInstance(
                MobEffects.BLINDNESS, BLINDNESS_DURATION_TICKS, 0, false, false, false));
    }

    /**
     * Remueve todos los efectos visuales de carga:
     *   - Limpia el título
     *   - Remueve ceguera
     *   - Envía mensaje de éxito en el actionbar
     */
    public static void removeLoadingVisuals(ServerPlayer player) {
        // Limpiar títulos
        player.connection.send(new ClientboundClearTitlesPacket(true));

        // Remover ceguera
        player.removeEffect(MobEffects.BLINDNESS);

        // Mensaje de éxito en el actionbar
        player.displayClientMessage(
                Component.literal("§a¡Perfil sincronizado correctamente!"), true);
    }
}
