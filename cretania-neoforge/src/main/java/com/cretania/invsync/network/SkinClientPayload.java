package com.cretania.invsync.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Payload S2C enviado por el servidor a todos los clientes cuando se aplica
 * una skin premium. El mod cliente (cretania-client) lo recibe y actualiza
 * el GameProfile del jugador en el cliente directamente, sin entity-respawn.
 *
 * Canal: cretania:client_skin
 * Formato: UUID (16 bytes) | value (UTF) | signature (UTF, vacío si null)
 *
 * IMPORTANTE: Este payload debe tener exactamente el mismo ResourceLocation
 * y StreamCodec que com.cretania.client.SkinClientPayload en cretania-client.
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
