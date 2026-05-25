package com.cretania.invsync.database;

import com.cretania.invsync.CretaniaSync;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;

import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DatabaseManager {

    private static final String COLLECTION_NAME = "player_inventory_data";
    private static final String SKINS_COLLECTION_NAME = "player_skins";

    private MongoClient mongoClient;
    private MongoCollection<Document> collection;
    private MongoCollection<Document> skinsCollection;
    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "Cretania-DB-Worker");
        t.setDaemon(true);
        return t;
    });

    public void initialize(String connectionUri, String databaseName) {
        mongoClient = MongoClients.create(connectionUri);
        MongoDatabase database = mongoClient.getDatabase(databaseName);
        collection = database.getCollection(COLLECTION_NAME);
        skinsCollection = database.getCollection(SKINS_COLLECTION_NAME);
        CretaniaSync.LOGGER.info("[Cretania] Conexión a MongoDB inicializada correctamente.");
    }

    public CompletableFuture<Void> savePlayerData(UUID uuid, String playerName, String base64Nbt, String serverName, String inventoryScope) {
        return CompletableFuture.runAsync(() -> {
            Document doc = new Document("uuid", uuid.toString())
                    .append("inventoryScope", inventoryScope)
                    .append("playerName", playerName)
                    .append("nbtData", base64Nbt)
                    .append("lastServer", serverName)
                    .append("updatedAt", new Date());

            collection.replaceOne(
                    Filters.and(
                            Filters.eq("uuid", uuid.toString()),
                            Filters.eq("inventoryScope", inventoryScope)
                    ),
                    doc,
                    new ReplaceOptions().upsert(true)
            );
            CretaniaSync.LOGGER.debug("[Cretania] Datos guardados para: {} ({}) scope={}", playerName, uuid, inventoryScope);
        }, executor);
    }

    public CompletableFuture<Optional<String>> loadPlayerData(UUID uuid, String inventoryScope) {
        return CompletableFuture.supplyAsync(() -> {
            Document result = collection.find(
                    Filters.and(
                            Filters.eq("uuid", uuid.toString()),
                            Filters.eq("inventoryScope", inventoryScope)
                    )
            ).first();

            if (result != null) {
                return Optional.ofNullable(result.getString("nbtData"));
            }
            return Optional.<String>empty();
        }, executor);
    }

    public void shutdown() {
        executor.shutdown();
        if (mongoClient != null) {
            mongoClient.close();
        }
        CretaniaSync.LOGGER.info("[Cretania] Conexión a MongoDB cerrada.");
    }

    public boolean isConnected() {
        return mongoClient != null && collection != null;
    }

    // ─────────────────────────── Skins ────────────────────────────
    // Documento: { _id: <uuid>, value: <textures-b64>, signature: <sig>, type: "premium"|"cracked", name?: <username>, updatedAt }

    public record SkinDocument(String value, String signature, String type) {}

    /** Lectura SÍNCRONA — para uso desde el server thread durante onPlayerLogIn (consulta rápida ~1ms local Mongo). */
    public Optional<SkinDocument> findSkinSync(UUID uuid) {
        if (skinsCollection == null) return Optional.empty();
        try {
            Document doc = skinsCollection.find(Filters.eq("_id", uuid.toString())).first();
            if (doc == null) return Optional.empty();
            String value = doc.getString("value");
            String signature = doc.getString("signature");
            String type = doc.getString("type");
            if (value == null) return Optional.empty();
            return Optional.of(new SkinDocument(value, signature, type));
        } catch (Exception e) {
            CretaniaSync.LOGGER.warn("[Cretania-Skin] findSkin falló para {}: {}", uuid, e.getMessage());
            return Optional.empty();
        }
    }

    /** Persistir skin (ASYNC — no bloquea el server thread). */
    public CompletableFuture<Void> upsertSkin(UUID uuid, String username, String value, String signature, String type) {
        if (skinsCollection == null) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> {
            try {
                Document doc = new Document("_id", uuid.toString())
                        .append("name", username)
                        .append("value", value)
                        .append("signature", signature)
                        .append("type", type)
                        .append("updatedAt", new Date());
                skinsCollection.replaceOne(Filters.eq("_id", uuid.toString()), doc, new ReplaceOptions().upsert(true));
                CretaniaSync.LOGGER.debug("[Cretania-Skin] Skin upsert para {} (type={})", username, type);
            } catch (Exception e) {
                CretaniaSync.LOGGER.warn("[Cretania-Skin] upsertSkin falló para {}: {}", username, e.getMessage());
            }
        }, executor);
    }
}

