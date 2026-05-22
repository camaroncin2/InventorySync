package com.cretania.velocitysync;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

public final class InventoryGroupConfig {
    private static final String FILE_NAME = "inventory-groups.properties";
    private static final String SERVER_PREFIX = "server.";

    private final Path file;
    private final Logger logger;
    private final Properties properties = new Properties();

    public InventoryGroupConfig(Path dataDirectory, Logger logger) {
        this.file = dataDirectory.resolve(FILE_NAME);
        this.logger = logger;
    }

    public void load() {
        try {
            Files.createDirectories(file.getParent());
            if (Files.notExists(file)) {
                writeDefaultFile();
            }
            try (InputStream input = Files.newInputStream(file)) {
                properties.clear();
                properties.load(input);
            }
            logger.info("[Cretania] Inventory groups loaded from {}", file);
        } catch (IOException ex) {
            logger.error("[Cretania] Could not load {}: {}", file, ex.getMessage());
        }
    }

    public String groupForServer(String serverName) {
        String value = properties.getProperty(SERVER_PREFIX + serverName, "");
        return value == null ? "" : value.trim();
    }

    public boolean shouldTransfer(String originServer, String targetServer) {
        String originGroup = groupForServer(originServer);
        String targetGroup = groupForServer(targetServer);
        return !originGroup.isBlank() && Objects.equals(originGroup, targetGroup);
    }

    public boolean isSyncEnabled(String serverName) {
        return !groupForServer(serverName).isBlank();
    }

    private void writeDefaultFile() throws IOException {
        Files.writeString(file, """
                # Cretania Inventory Groups
                #
                # Same value = same shared inventory.
                # Empty value = no shared inventory on that server.

                server.neoforge_1=survival
                server.tienda=survival

                server.neoforge_2=
                server.lobby=

                server.minijuegos=minigames
                server.minigames=minigames
                """);
    }
}
