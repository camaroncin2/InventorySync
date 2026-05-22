package com.cretania.invsync.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Payload personalizado para el canal "cretania:sync".
 * Encapsula los datos que viajan entre NeoForge y Velocity a través del Plugin Messaging Channel.
 */
public record SyncPayload(byte[] data) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SyncPayload> TYPE =
            new CustomPacketPayload.Type<>(SyncChannelHandler.SYNC_CHANNEL);

    public static final StreamCodec<FriendlyByteBuf, SyncPayload> STREAM_CODEC =
            StreamCodec.of(SyncPayload::write, SyncPayload::read);

    private static void write(FriendlyByteBuf buf, SyncPayload payload) {
        buf.writeByteArray(payload.data());
    }

    private static SyncPayload read(FriendlyByteBuf buf) {
        return new SyncPayload(buf.readByteArray());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
