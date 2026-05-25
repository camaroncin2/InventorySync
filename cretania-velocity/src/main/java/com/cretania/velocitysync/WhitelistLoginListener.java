package com.cretania.velocitysync;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

/**
 * Rechaza conexiones de jugadores no listados en la whitelist (comparación por
 * NOMBRE case-insensitive — funciona en backends cracked donde los UUIDs
 * no son confiables).
 *
 * Se ejecuta en PreLoginEvent → el cliente recibe el kick ANTES de que el
 * proxy intente conectarlo a un backend.
 */
public class WhitelistLoginListener {

    private final WhitelistManager whitelist;
    private final Logger logger;

    public WhitelistLoginListener(WhitelistManager whitelist, Logger logger) {
        this.whitelist = whitelist;
        this.logger = logger;
    }

    @Subscribe(order = PostOrder.EARLY)
    public void onPreLogin(PreLoginEvent event) {
        String name = event.getUsername();
        if (!whitelist.isAllowed(name)) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                    Component.text("No estás en la whitelist del servidor.", NamedTextColor.RED)
            ));
            logger.info("[Cretania-Whitelist] {} rechazado (no listado).", name);
        }
    }
}
