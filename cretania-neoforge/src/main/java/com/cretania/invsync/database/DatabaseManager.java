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

    private MongoClient mongoClient;
    private MongoCollection<Document> collection;
    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "Cretania-DB-Worker");
        t.setDaemon(true);
        return t;
    });

    public void initialize(String connectionUri, String databaseName) {
        mongoClient = MongoClients.create(connectionUri);
        MongoDatabase database = mongoClient.getDatabase(databaseName);
        collection = database.getCollection(COLLECTION_NAME);
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
}

