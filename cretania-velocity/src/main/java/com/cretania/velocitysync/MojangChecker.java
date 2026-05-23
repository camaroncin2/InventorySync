package com.cretania.velocitysync;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

/**
 * Comprueba si un nombre de usuario existe como cuenta premium en Mojang.
 * Llama a: https://api.mojang.com/users/profiles/minecraft/<nombre>
 *  - 200 OK   → premium
 *  - 204 / 404 → no existe (cracked)
 *  - Error     → asumir cracked (fail-safe)
 */
public final class MojangChecker {

    private static final String API_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS    = 3000;

    private MojangChecker() {}

    /**
     * Bloquea el hilo actual hasta tener respuesta (usa un pool de Velocity).
     * @return true si el nombre pertenece a una cuenta Mojang activa.
     */
    public static boolean isPremium(String username) {
        try {
            URL url = URI.create(API_URL + username).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "Cretania-Velocity/1.0");
            conn.connect();

            int status = conn.getResponseCode();
            // Drenar la respuesta para liberar la conexión HTTP
            try (InputStream is = conn.getInputStream()) {
                byte[] buf = new byte[256];
                while (is.read(buf) != -1) { /* descartar */ }
            } catch (IOException ignored) { /* puede que no haya cuerpo */ }
            conn.disconnect();

            return status == 200;
        } catch (IOException e) {
            // Si Mojang no responde, tratamos al jugador como cracked (fail-safe)
            return false;
        }
    }
}
