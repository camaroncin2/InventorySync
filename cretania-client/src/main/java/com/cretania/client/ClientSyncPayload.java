package com.cretania.client;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Payload C2S que el cliente envía al backend via cretania:sync.
 * Compatible en wire con SyncPayload del servidor (mismo channel + byteArray codec).
 * Permite al cliente pasar datos al servidor inmediatamente al conectar.
 */
public record ClientSyncPayload(byte[] data) implements CustomPacketPayload {

    public static final Type<ClientSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cretania", "sync"));

    public static final StreamCodec<FriendlyByteBuf, ClientSyncPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> buf.writeByteArray(p.data()),
                    buf -> new ClientSyncPayload(buf.readByteArray())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
