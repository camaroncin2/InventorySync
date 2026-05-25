package com.cretania.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Responde al CHALLENGE del servidor (flujo challenge-response con Mojang session server).
 *
 * Flujo:
 *   1. Servidor envia CHALLENGE:<serverId> al cliente.
 *   2. Este handler llama POST sessionserver.mojang.com/session/minecraft/join
 *      con el accessToken del launcher y el serverId como nonce.
 *   3. Si OK (204): envia CLIENT_AUTH:MSA:<uuid-mojang>.
 *      Si falla (403, sin token, etc.): envia CLIENT_AUTH:OFFLINE.
 *   4. El servidor verifica con hasJoined - no confia en el contenido del mensaje.
 *
 * El access token NUNCA se envia al servidor, solo se usa localmente contra Mojang.
 */
public class ClientAuthSender {

    private static final Logger LOGGER = LoggerFactory.getLogger(CretaniaClientMod.MOD_ID);
    private static final int TIMEOUT_MS = 5000;

    /**
     * Llamado cuando el servidor envia CHALLENGE:<serverId> por el canal authmod:check.
     */
    public static void handleChallenge(byte[] rawData) {
        String message = new String(rawData, StandardCharsets.UTF_8);
        if (!message.startsWith("CHALLENGE:")) return;

        String serverId = message.substring("CHALLENGE:".length()).trim();
        LOGGER.info("[CretaniaClient] CHALLENGE recibido: {}", serverId);

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) { sendResponse("CLIENT_AUTH:OFFLINE"); return; }

        User user = mc.getUser();
        String accessToken = user.getAccessToken();
        java.util.UUID profileId = user.getProfileId();

        boolean hasCredentials = accessToken != null
                && !accessToken.isBlank()
                && !accessToken.equals("null")
                && profileId != null;

        if (!hasCredentials) {
            LOGGER.info("[CretaniaClient] Sin credenciales MSA validas -> CLIENT_AUTH:OFFLINE");
            sendResponse("CLIENT_AUTH:OFFLINE");
            return;
        }

        String uuid      = profileId.toString();
        String token     = accessToken;
        String challenge = serverId;

        Thread t = new Thread(() -> {
            boolean joined = callMojangJoin(token, uuid, challenge);
            String response = joined
                    ? "CLIENT_AUTH:MSA:" + uuid
                    : "CLIENT_AUTH:OFFLINE";
            LOGGER.info("[CretaniaClient] Auth result: {}", joined ? "MSA" : "OFFLINE");
            Minecraft.getInstance().execute(() -> sendResponse(response));
        }, "CretaniaClient-Auth");
        t.setDaemon(true);
        t.start();
    }

    // --- Llamada a Mojang join ---------------------------------------------------

    private static boolean callMojangJoin(String accessToken, String uuid, String serverId) {
        try {
            String uuidNoDashes = uuid.replace("-", "");
            String body = "{\"accessToken\":\"" + accessToken
                    + "\",\"selectedProfile\":\"" + uuidNoDashes
                    + "\",\"serverId\":\"" + serverId + "\"}";

            HttpURLConnection conn = (HttpURLConnection)
                    URI.create("https://sessionserver.mojang.com/session/minecraft/join")
                       .toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            if (status == 204) return true;

            InputStream es = conn.getErrorStream();
            if (es != null) {
                try {
                    LOGGER.warn("[CretaniaClient] Mojang join rechazado ({}): {}",
                            status, new String(es.readAllBytes(), StandardCharsets.UTF_8));
                } catch (Exception ignored) {}
                es.close();
            }
            return false;

        } catch (Exception e) {
            LOGGER.warn("[CretaniaClient] Error en Mojang join: {}", e.getMessage());
            return false;
        }
    }

    private static void sendResponse(String message) {
        try {
            PacketDistributor.sendToServer(
                    new ClientAuthChannelPayload(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            LOGGER.warn("[CretaniaClient] Error enviando respuesta auth: {}", e.getMessage());
        }
    }
}