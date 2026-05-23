package com.cretania.velocitysync;

import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Servidor TCP local que recibe solicitudes de transferencia de servidor
 * directamente desde los backends NeoForge, sin pasar por el cliente.
 *
 * Solo escucha en 127.0.0.1 (loopback) — inaccesible desde Internet.
 * Protocolo: una línea de texto con formato TRANSFER:<uuid>:<serverName>\n
 */
public class TransferSocketServer {

    static final int PORT = 25899;

    /**
     * Posición del jugador capturada en el servidor origen (lobby).
     * PlayerConnectionListener la lee y envía SET_POSITION al backend destino.
     */
    public record PositionData(String targetServer, double x, double y, double z, float yaw, float pitch) {}

    /** Posiciones pendientes de aplicar: se llenan al recibir TRANSFER con posición. */
    public static final ConcurrentHashMap<UUID, PositionData> PENDING_POSITIONS = new ConcurrentHashMap<>();

    private final ProxyServer proxy;
    private final Logger logger;

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "cretania-transfer-socket");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean running = false;
    private ServerSocket serverSocket;

    public TransferSocketServer(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public void start() {
        running = true;
        executor.submit(() -> {
            try {
                serverSocket = new ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1"));
                logger.info("[Cretania-Socket] Escuchando transferencias en 127.0.0.1:{}", PORT);
                while (running) {
                    try {
                        Socket client = serverSocket.accept();
                        executor.submit(() -> handle(client));
                    } catch (Exception e) {
                        if (running) logger.warn("[Cretania-Socket] Error aceptando conexión: {}", e.getMessage());
                    }
                }
            } catch (Exception e) {
                logger.error("[Cretania-Socket] No se pudo iniciar servidor TCP en puerto {}: {}", PORT, e.getMessage());
            }
        });
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        executor.shutdownNow();
    }

    // -------------------------------------------------------------------------
    // Procesamiento de mensajes
    // -------------------------------------------------------------------------

    private void handle(Socket client) {
        try (client;
             BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()))) {

            client.setSoTimeout(3000);
            String line = reader.readLine();
            if (line == null) return;

            line = line.trim();
            if (line.startsWith("TRANSFER:")) {
                // TRANSFER:<uuid>:<serverName>[:<x>:<y>:<z>:<yaw>:<pitch>]
                String[] parts = line.split(":", 8);
                if (parts.length >= 3) {
                    try {
                        UUID uuid = UUID.fromString(parts[1].trim());
                        String serverName = parts[2].trim();
                        if (parts.length == 8) {
                            double x = Double.parseDouble(parts[3]);
                            double y = Double.parseDouble(parts[4]);
                            double z = Double.parseDouble(parts[5]);
                            float yaw = Float.parseFloat(parts[6]);
                            float pitch = Float.parseFloat(parts[7].trim());
                            PENDING_POSITIONS.put(uuid, new PositionData(serverName, x, y, z, yaw, pitch));
                        }
                        executeTransfer(uuid, serverName);
                    } catch (IllegalArgumentException e) {
                        logger.warn("[Cretania-Socket] UUID inválido en TRANSFER: {}", line);
                    }
                } else {
                    logger.warn("[Cretania-Socket] Mensaje TRANSFER malformado: {}", line);
                }
            } else {
                logger.warn("[Cretania-Socket] Mensaje desconocido: {}", line);
            }

        } catch (Exception e) {
            logger.warn("[Cretania-Socket] Error procesando cliente: {}", e.getMessage());
        }
    }

    private void executeTransfer(UUID uuid, String serverName) {
        proxy.getPlayer(uuid).ifPresentOrElse(
                player -> proxy.getServer(serverName).ifPresentOrElse(
                        targetServer -> player.createConnectionRequest(targetServer).connect()
                                .whenComplete((result, ex) -> {
                                    if (ex != null) {
                                        logger.warn("[Cretania-Socket] Error transfiriendo {} → {}: {}",
                                                player.getUsername(), serverName, ex.getMessage());
                                    } else {
                                        logger.info("[Cretania-Socket] {} transferido a {}",
                                                player.getUsername(), serverName);
                                    }
                                }),
                        () -> logger.warn("[Cretania-Socket] Servidor '{}' no encontrado en Velocity (TRANSFER ignorado)", serverName)
                ),
                () -> logger.warn("[Cretania-Socket] Jugador {} no está en el proxy (TRANSFER ignorado)", uuid)
        );
    }
}
