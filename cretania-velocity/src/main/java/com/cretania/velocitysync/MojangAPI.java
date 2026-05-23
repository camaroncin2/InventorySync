package com.cretania.velocitysync;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;

/**
 * Cliente Mojang API para Velocity.
 * Obtiene si un jugador es premium Y su propiedad de skin firmada,
 * todo en una sola clase para evitar doble llamada HTTP.
 */
public final class MojangAPI {

    private static final String PROFILE_URL  = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String SESSION_URL  = "https://sessionserver.mojang.com/session/minecraft/profile/";
    private static final int TIMEOUT_MS = 4000;

    private MojangAPI() {}

    /**
     * Propiedad de skin: value + signature (firmada por Mojang).
     */
    public record SkinProperty(String value, String signature) {}

    /**
     * Resultado completo: uuid Mojang + skin (puede ser null si la API falló).
     * Si el objeto en sí es null → el jugador no es premium.
     */
    public record MojangProfile(String mojangUuid, SkinProperty skin) {}

    /**
     * Consulta Mojang en dos pasos:
     *   1. username → UUID real (si no existe → no premium → null)
     *   2. UUID → perfil con textura firmada
     * Bloquea el hilo llamante; úsalo siempre en un executor/scheduler async.
     */
    /**
     * @return null si el jugador NO es premium (404 en Mojang).
     * @throws IOException si la API devuelve error (429, 5xx, timeout) — NO cachear.
     */
    public static MojangProfile fetchProfile(String username) throws IOException {
        String uuid = fetchUUID(username);
        if (uuid == null) return null;          // 404 → genuinamente no premium

        SkinProperty skin = fetchSkin(uuid);    // puede ser null si no tiene skin custom
        return new MojangProfile(uuid, skin);
    }

    // ── internos ────────────────────────────────────────────────────────────

    private static String fetchUUID(String username) throws IOException {
        String body = get(PROFILE_URL + username);
        if (body == null) return null;
        // La API puede devolver "id":"..." o "id" : "..." (con espacios)
        return parseJsonStringField(body, "id");
    }

    private static SkinProperty fetchSkin(String uuid) throws IOException {
        String body = get(SESSION_URL + uuid + "?unsigned=false");
        if (body == null) return null;
        // Buscar la propiedad "textures"
        int nameIdx = body.indexOf("\"textures\"");
        if (nameIdx < 0) return null;

        String value = extractField(body, "\"value\"", nameIdx);
        String sig   = extractField(body, "\"signature\"", nameIdx);
        if (value == null) return null;
        return new SkinProperty(value, sig);   // sig puede ser null
    }

    private static String extractField(String json, String key, int fromIdx) {
        int idx = json.indexOf(key, fromIdx);
        if (idx < 0) return null;
        idx += key.length();
        int start = json.indexOf('"', idx);
        if (start < 0) return null;
        start++;
        int end = json.indexOf('"', start);
        return end > start ? json.substring(start, end) : null;
    }

    /**
     * Extrae el valor de un campo JSON de forma robusta,
     * tolerando espacios opcionales alrededor del ':' y el valor.
     * Ej.: "id":"abc", "id" : "abc", "id":  "abc" → "abc"
     */
    private static String parseJsonStringField(String json, String key) {
        int keyIdx = json.indexOf("\"" + key + "\"");
        if (keyIdx < 0) return null;
        int colonIdx = json.indexOf(':', keyIdx + key.length() + 2);
        if (colonIdx < 0) return null;
        int quoteOpen = json.indexOf('"', colonIdx + 1);
        if (quoteOpen < 0) return null;
        quoteOpen++;
        int quoteClose = json.indexOf('"', quoteOpen);
        return quoteClose > quoteOpen ? json.substring(quoteOpen, quoteClose) : null;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "Cretania-Velocity/1.0");
        conn.connect();

        int status = conn.getResponseCode();
        if (status == 200) { /* sigue */ }
        else if (status == 404 || status == 204) { conn.disconnect(); return null; }  // no existe
        else { conn.disconnect(); throw new IOException("HTTP " + status); }          // rate-limit / server error

        try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }
}
