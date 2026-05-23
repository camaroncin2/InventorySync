package com.cretania.client;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Payload S2C: el servidor envía datos de skin premium al cliente.
 * Canal: cretania:client_skin (debe coincidir con el servidor).
 * Formato: UUID (16 bytes) | value (UTF) | signature (UTF, vacío si null)
 */
public record SkinClientPayload(UUID uuid, String value, String signature)
        implements CustomPacketPayload {

    public static final Type<SkinClientPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("cretania", "client_skin"));

    public static final StreamCodec<FriendlyByteBuf, SkinClientPayload> STREAM_CODEC =
            StreamCodec.of(SkinClientPayload::write, SkinClientPayload::read);

    private static void write(FriendlyByteBuf buf, SkinClientPayload p) {
        buf.writeUUID(p.uuid());
        buf.writeUtf(p.value());
        buf.writeUtf(p.signature() != null ? p.signature() : "");
    }

    private static SkinClientPayload read(FriendlyByteBuf buf) {
        UUID uuid = buf.readUUID();
        String value = buf.readUtf(32767);
        String sig = buf.readUtf(32767);
        return new SkinClientPayload(uuid, value, sig.isEmpty() ? null : sig);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
