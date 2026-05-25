package com.cretania.client;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Payload C2S que el cliente envía al lobby por el canal authmod:check.
 * Informa si el cliente tiene sesión Microsoft (MSA) auténtica o es offline.
 *
 * El servidor identifica al jugador remitente via context.player() — no via UUID del mensaje.
 * Esto hace que el contenido no sea falsificable (aunque el tipo de cuenta sí puede serlo
 * con launchers cracked que fingen MSA; en ese caso Velocity contradirá y expulsará).
 *
 * Formato del mensaje: "CLIENT_AUTH:MSA" o "CLIENT_AUTH:OFFLINE"
 * Compatible en wire con AuthPayload del servidor (mismo canal + byteArray codec).
 */
public record ClientAuthChannelPayload(byte[] data) implements CustomPacketPayload {

    public static final Type<ClientAuthChannelPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("authmod", "check"));

    public static final StreamCodec<FriendlyByteBuf, ClientAuthChannelPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> buf.writeByteArray(p.data()),
                    buf -> new ClientAuthChannelPayload(buf.readByteArray())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
