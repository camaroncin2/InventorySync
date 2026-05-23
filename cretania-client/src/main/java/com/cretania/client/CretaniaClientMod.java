package com.cretania.client;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Mod cliente de Cretania: recibe SkinClientPayload del servidor y aplica
 * la skin premium directamente en el cliente sin necesidad de entity-respawn.
 * Persiste las skins entre cambios de servidor (lobby → survival1, etc.).
 */
@Mod(CretaniaClientMod.MOD_ID)
public class CretaniaClientMod {

    public static final String MOD_ID = "cretania_client";

    public CretaniaClientMod(IEventBus modEventBus) {
        // Registrar payload S2C
        modEventBus.addListener(this::onRegisterPayloads);
        // Registrar listeners del juego (EntityJoinLevelEvent, etc.)
        NeoForge.EVENT_BUS.register(ClientSkinHandler.class);
    }

    private void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").optional();
        registrar.playToClient(
                SkinClientPayload.TYPE,
                SkinClientPayload.STREAM_CODEC,
                ClientSkinHandler::handleSkinPayload
        );
    }
}
