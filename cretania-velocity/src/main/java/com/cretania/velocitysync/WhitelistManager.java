package com.cretania.velocitysync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whitelist por NOMBRE (case-insensitive) — diseñada para servers cracked/mixtos
 * donde los UUIDs no son confiables.
 *
 * Persistido en {@code plugins/cretania-velocity-sync/whitelist.json}:
 * <pre>
 * {
 *   "enabled": true,
 *   "names": ["Pepe", "juan_v2", "Maria"]
 * }
 * </pre>
 */
public class WhitelistManager {

    private final Path file;
    private final Logger logger;
    private final Set<String> namesLower = ConcurrentHashMap.newKeySet();
    /** Preserva capitalización original para mostrar en /cwl list. */
    private final Set<String> namesDisplay = ConcurrentHashMap.newKeySet();
    private volatile boolean enabled = false;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public WhitelistManager(Path dataDir, Logger logger) {
        this.file = dataDir.resolve("whitelist.json");
        this.logger = logger;
    }

    public void load() {
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                save();
                logger.info("[Cretania-Whitelist] Archivo creado vacío en {}", file);
                return;
            }
            try (var reader = Files.newBufferedReader(file)) {
                JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
                this.enabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
                namesLower.clear();
                namesDisplay.clear();
                if (obj.has("names")) {
                    for (var el : obj.getAsJsonArray("names")) {
                        String n = el.getAsString();
                        if (n == null || n.isBlank()) continue;
                        namesLower.add(n.toLowerCase(Locale.ROOT));
                        namesDisplay.add(n);
                    }
                }
            }
            logger.info("[Cretania-Whitelist] Cargada: enabled={}, {} nombres", enabled, namesLower.size());
        } catch (Exception e) {
            logger.error("[Cretania-Whitelist] Error cargando whitelist.json: {}", e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            var arr = new com.google.gson.JsonArray();
            new TreeSet<>(namesDisplay).forEach(arr::add);
            obj.add("names", arr);
            try (Writer w = Files.newBufferedWriter(file)) {
                gson.toJson(obj, w);
            }
        } catch (IOException e) {
            logger.error("[Cretania-Whitelist] Error guardando whitelist.json: {}", e.getMessage());
        }
    }

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        save();
    }

    /** True si el jugador puede entrar (whitelist apagada o nombre listado). */
    public boolean isAllowed(String name) {
        if (!enabled) return true;
        return namesLower.contains(name.toLowerCase(Locale.ROOT));
    }

    /** @return true si se agregó, false si ya estaba. */
    public boolean add(String name) {
        if (name == null || name.isBlank()) return false;
        boolean a = namesLower.add(name.toLowerCase(Locale.ROOT));
        if (a) {
            namesDisplay.add(name);
            save();
        }
        return a;
    }

    /** @return true si se quitó, false si no estaba. */
    public boolean remove(String name) {
        if (name == null || name.isBlank()) return false;
        boolean r = namesLower.remove(name.toLowerCase(Locale.ROOT));
        if (r) {
            namesDisplay.removeIf(n -> n.equalsIgnoreCase(name));
            save();
        }
        return r;
    }

    public Set<String> all() {
        return Collections.unmodifiableSet(new TreeSet<>(namesDisplay));
    }
}
