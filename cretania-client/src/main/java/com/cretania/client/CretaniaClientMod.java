package com.cretania.client;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Mod cliente de Cretania: recibe SkinClientPayload del servidor y aplica
 * la skin premium directamente en el cliente sin necesidad de entity-respawn.
 * Persiste las skins entre cambios de servidor (lobby â†’ survival1, etc.).
 */
@Mod(CretaniaClientMod.MOD_ID)
public class CretaniaClientMod {

    public static final String MOD_ID = "cretania_client";

    public CretaniaClientMod(IEventBus modEventBus) {
        // Registrar payload S2C
        modEventBus.addListener(this::onRegisterPayloads);
        // Registrar listeners del juego
        NeoForge.EVENT_BUS.register(ClientSkinHandler.class);
    }

    private void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").optional();
        // S2C: recibir skin premium del servidor
        registrar.playToClient(
                SkinClientPayload.TYPE,
                SkinClientPayload.STREAM_CODEC,
                ClientSkinHandler::handleSkinPayload
        );
        // C2S: enviar skin propia al servidor en cuanto conecta (sin esperar Velocity)
        registrar.playToServer(
                ClientSyncPayload.TYPE,
                ClientSyncPayload.STREAM_CODEC,
                (payload, context) -> {} // el cliente solo envÃ­a en esta direcciÃ³n
        );
        // Canal authmod:check â€” bidireccional (igual que el servidor).
        // El cliente nunca recibe S2C real en este canal (Velocity los intercepta),
        // pero debe declarar soporte bidireccional para que la negociaciÃ³n no falle.
        // Registrar solo playToServer causaba: "server wants BIDIRECTIONAL, client only SERVERBOUND".
        registrar.playBidirectional(
                ClientAuthChannelPayload.TYPE,
                ClientAuthChannelPayload.STREAM_CODEC,
                (payload, context) -> {
                    String msg = new String(payload.data(), java.nio.charset.StandardCharsets.UTF_8);
                    if (msg.startsWith("CHALLENGE:")) {
                        ClientAuthSender.handleChallenge(payload.data());
                    } else if (msg.startsWith("CRACKED_SKIN:")) {
                        // Velocity reenvía CRACKED_SKIN al cliente; lo almacenamos en caché
                        // para que se aplique sin recarga de mundo y persista cross-server.
                        context.enqueueWork(() -> ClientSkinHandler.handleCrackedSkin(msg));
                    }
                }
        );
    }
}

