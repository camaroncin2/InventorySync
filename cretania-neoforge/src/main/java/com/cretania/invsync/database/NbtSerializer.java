package com.cretania.invsync.database;

import com.cretania.invsync.CretaniaSync;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;

import java.io.*;
import java.util.Base64;

/**
 * Utilidad para serializar/deserializar CompoundTag (NBT) a/desde Base64.
 * Permite almacenar el NBT completo del jugador (incluyendo Capabilities de mods como Create y TFC)
 * en un campo LONGTEXT de MySQL.
 */
public class NbtSerializer {

    /**
     * Serializa un CompoundTag a una cadena Base64.
     * @param tag El CompoundTag a serializar
     * @return Cadena Base64 representando el NBT comprimido
     */
    public static String toBase64(CompoundTag tag) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (IOException e) {
            CretaniaSync.LOGGER.error("[Cretania] Error serializando NBT a Base64: {}", e.getMessage());
            throw new RuntimeException("Fallo en serialización NBT", e);
        }
    }

    /**
     * Deserializa una cadena Base64 a un CompoundTag.
     * @param base64 Cadena Base64 con el NBT comprimido
     * @return CompoundTag reconstruido
     */
    public static CompoundTag fromBase64(String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
            return NbtIo.readCompressed(bais, NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            CretaniaSync.LOGGER.error("[Cretania] Error deserializando Base64 a NBT: {}", e.getMessage());
            throw new RuntimeException("Fallo en deserialización NBT", e);
        }
    }
}
