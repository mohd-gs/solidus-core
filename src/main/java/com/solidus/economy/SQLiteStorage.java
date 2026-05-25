package com.solidus.economy;

import com.solidus.SolidusMod;
import com.solidus.util.CurrencyUtil;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Asynchronous SQLite Storage Backend for Solidus Economy Engine.
 *
 * Architecture: Single-Threaded Executor Queue + In-Memory Cache + Persistent Connection
 *
 * Race Condition Strategy:
 * All balance mutations are processed through a dedicated single-thread executor,
 * which guarantees sequential execution without any overlap. An in-memory balance
 * cache (ConcurrentHashMap) provides instant reads without hitting the database,
 * while all writes update the cache first and then persist asynchronously to SQLite.
 *
 * Persistent Connection:
 * A single persistent SQLite connection is shared across all executor operations.
 * Since the single-threaded executor serializes all access, connection sharing is
 * inherently safe — no two operations can use the connection simultaneously.
 * This eliminates the overhead of opening/closing connections for every operation.
 *
 * Write Ordering:
 * New player INSERT operations use UPSERT (INSERT ... ON CONFLICT DO UPDATE) and
 * are submitted directly to the executor queue alongside all other mutations.
 * Since the executor is single-threaded, operations execute in submission order,
 * guaranteeing that a new player's INSERT completes before any subsequent UPDATE.
 *
 * Crash Resilience:
 * - WAL (Write-Ahead Logging) mode ensures committed transactions survive crashes.
 * - The in-memory cache is rebuilt from the database on startup.
 * - Auto-checkpoint balances performance vs. crash recovery window.
 * - All critical mutations are persisted to SQLite immediately after the
 *   in-memory state is updated, minimizing the data-at-risk window.
 */
public class SQLiteStorage {

    private static final String DATABASE_NAME = "economy.db";
    private static final String CREATE_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS player_balances (
            uuid TEXT PRIMARY KEY NOT NULL,
            player_name TEXT NOT NULL,
            balance REAL NOT NULL DEFAULT 0.0,
            last_updated INTEGER NOT NULL
        )
    """;
    private static final String CREATE_INDEX_SQL = """
        CREATE INDEX IF NOT EXISTS idx_balance_rank
        ON player_balances (balance DESC)
    """;

    private final ConcurrentHashMap<UUID, Double> balanceCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> playerNameCache = new ConcurrentHashMap<>();

    private final ExecutorService asyncExecutor;
    private final String databaseUrl;
    private volatile boolean initialized = false;

    /** Persistent database connection — shared across all executor operations */
    private volatile Connection persistentConnection;

    /** Transaction log — shares this executor and connection */
    private TransactionLog transactionLog;

    /**
     * Constructs a new SQLiteStorage with the given config directory path.
     *
     * @param configDir The directory where the database file will be stored
     */
    public SQLiteStorage(String configDir) {
        this.databaseUrl = "jdbc:sqlite:" + configDir + "/" + DATABASE_NAME;
        this.asyncExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Solidus-Economy-Worker");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Initializes the database: creates tables, indexes, configures WAL mode,
     * opens the persistent connection, and pre-loads all balances into cache.
     * Must be called once during mod startup before any other operations.
     */
    public void initialize() {
        try {
            // Open persistent connection (safe because single-threaded executor serializes all access)
            persistentConnection = DriverManager.getConnection(databaseUrl);

            // Enable WAL mode for crash resilience
            try (Statement stmt = persistentConnection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA synchronous=NORMAL");
                stmt.execute("PRAGMA temp_store=MEMORY");
                stmt.execute("PRAGMA mmap_size=67108864"); // 64MB memory map
                stmt.execute("PRAGMA cache_size=-2000"); // 2MB cache
            }

            // Create tables
            try (Statement stmt = persistentConnection.createStatement()) {
                stmt.execute(CREATE_TABLE_SQL);
                stmt.execute(CREATE_INDEX_SQL);
            }

            // Pre-load all balances into the in-memory cache
            loadAllBalancesIntoCache(persistentConnection);

            // Initialize transaction log (shares connection and executor)
            transactionLog = new TransactionLog(persistentConnection, asyncExecutor);
            transactionLog.initialize();

            initialized = true;
            SolidusMod.LOGGER.info("SQLite database initialized successfully. Cached {} player balances.",
                balanceCache.size());
        } catch (SQLException e) {
            SolidusMod.LOGGER.error("CRITICAL: Failed to initialize SQLite database!", e);
            throw new RuntimeException("Solidus economy database initialization failed", e);
        }
    }

    /**
     * Pre-loads all existing balances and player names from the database
     * into the in-memory cache.
     */
    private void loadAllBalancesIntoCache(Connection conn) throws SQLException {
        String sql = "SELECT uuid, player_name, balance FROM player_balances";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("uuid"));
                String name = rs.getString("player_name");
                double balance = rs.getDouble("balance");
                balanceCache.put(uuid, balance);
                if (name != null && !name.isEmpty()) {
                    playerNameCache.put(uuid, name);
                }
            }
        }
    }

    /**
     * Shuts down the async executor gracefully and closes the persistent connection.
     * All pending database writes are flushed before shutdown completes.
     */
    public void shutdown() {
        asyncExecutor.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                asyncExecutor.shutdownNow();
                SolidusMod.LOGGER.warn("Economy executor forced shutdown after timeout.");
            }
        } catch (InterruptedException e) {
            asyncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Close persistent connection
        if (persistentConnection != null) {
            try {
                persistentConnection.close();
                SolidusMod.LOGGER.info("Economy database connection closed.");
            } catch (SQLException e) {
                SolidusMod.LOGGER.error("Failed to close economy database connection", e);
            }
        }

        SolidusMod.LOGGER.info("SQLite storage shut down complete.");
    }

    // ── Instant Read Operations (from in-memory cache) ────

    /**
     * Retrieves a player's balance instantly from the in-memory cache.
     *
     * If the player has no record in the cache, a new entry is created
     * with the default starting balance and persisted asynchronously.
     *
     * The new player INSERT uses UPSERT to guarantee ordering safety:
     * since it is submitted to the same executor queue as all other mutations,
     * it will complete before any subsequent UPDATE for the same UUID.
     *
     * @param uuid       The player's unique ID
     * @param playerName The player's display name (for record creation)
     * @return CompletableFuture containing the player's balance (completes instantly)
     */
    public CompletableFuture<Double> getBalance(UUID uuid, String playerName) {
        ensureInitialized();

        // Update player name cache whenever we see a non-empty name
        if (playerName != null && !playerName.isEmpty()) {
            playerNameCache.put(uuid, playerName);
        }

        // Check the in-memory cache first (instant, no DB query)
        Double balance = balanceCache.get(uuid);
        if (balance != null) {
            return CompletableFuture.completedFuture(balance);
        }

        // Player not in cache — create a new entry with the default starting balance
        double startingBalance = CurrencyUtil.DEFAULT_STARTING_BALANCE;
        balanceCache.put(uuid, startingBalance);

        // Persist the new player asynchronously using UPSERT
        // This is submitted to the SAME executor queue as all other mutations,
        // guaranteeing it completes before any subsequent UPDATE for this UUID
        asyncPersistNewPlayer(uuid, playerName, startingBalance);

        return CompletableFuture.completedFuture(startingBalance);
    }

    /**
     * Retrieves the top N players by balance for leaderboard display.
     *
     * Uses the SQLite idx_balance_rank index for efficient sorting
     * instead of sorting the entire in-memory cache. This scales
     * much better with thousands of players since SQLite only needs
     * to scan the index and return the top N rows.
     *
     * Player names are resolved from the in-memory playerNameCache
     * for instant lookup without additional DB queries.
     *
     * @param limit Maximum number of entries to return
     * @return CompletableFuture containing list of BalanceEntry objects
     */
    public CompletableFuture<List<BalanceEntry>> getTopBalances(int limit) {
        return CompletableFuture.supplyAsync(() -> {
            ensureInitialized();
            List<BalanceEntry> entries = new ArrayList<>();
            String sql = "SELECT uuid, player_name, balance FROM player_balances ORDER BY balance DESC LIMIT ?";
            try (PreparedStatement ps = persistentConnection.prepareStatement(sql)) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    int rank = 0;
                    while (rs.next()) {
                        rank++;
                        String name = rs.getString("player_name");
                        // Fallback to playerNameCache if DB name is empty
                        if (name == null || name.isEmpty()) {
                            UUID uuid = UUID.fromString(rs.getString("uuid"));
                            name = playerNameCache.getOrDefault(uuid, "Unknown");
                        }
                        entries.add(new BalanceEntry(rank, name, rs.getDouble("balance")));
                    }
                }
            } catch (SQLException e) {
                SolidusMod.LOGGER.error("Failed to get top balances from database", e);
                // Fallback to in-memory sort if DB query fails
                return balanceCache.entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .limit(limit)
                    .collect(ArrayList<BalanceEntry>::new,
                        (list, entry) -> {
                            String name = playerNameCache.getOrDefault(entry.getKey(), "Unknown");
                            list.add(new BalanceEntry(list.size() + 1, name, entry.getValue()));
                        },
                        ArrayList::addAll);
            }
            return entries;
        }, asyncExecutor);
    }

    // ── Write Operations (via Single-Threaded Executor Queue) ──

    /**
     * Sets a player's balance to an exact value.
     *
     * @param uuid       The player's unique ID
     * @param playerName The player's display name
     * @param amount     The new balance value
     * @return CompletableFuture indicating success
     */
    public CompletableFuture<Boolean> setBalance(UUID uuid, String playerName, double amount) {
        ensureInitialized();
        amount = CurrencyUtil.round(amount);
        if (!CurrencyUtil.isValidBalance(amount)) {
            SolidusMod.LOGGER.warn("Invalid balance amount rejected: {}", amount);
            return CompletableFuture.completedFuture(false);
        }

        return CompletableFuture.supplyAsync(() -> {
            Double previousBalance = balanceCache.get(uuid);
            balanceCache.put(uuid, amount);
            boolean success = persistBalance(uuid, playerName, amount);
            if (!success) {
                if (previousBalance != null) {
                    balanceCache.put(uuid, previousBalance);
                } else {
                    balanceCache.remove(uuid);
                }
                SolidusMod.LOGGER.error("Failed to persist balance for UUID: {}. Cache rolled back to previous value.", uuid);
            }
            return success;
        }, asyncExecutor);
    }

    /**
     * Atomically adds an amount to a player's balance.
     *
     * @param uuid       The player's unique ID
     * @param playerName The player's display name
     * @param amount     The amount to add (must be positive)
     * @return CompletableFuture with the new balance, or -1 on failure
     */
    public CompletableFuture<Double> addBalance(UUID uuid, String playerName, double amount) {
        ensureInitialized();
        amount = CurrencyUtil.round(amount);

        return CompletableFuture.supplyAsync(() -> {
            double currentBalance = balanceCache.getOrDefault(uuid, CurrencyUtil.DEFAULT_STARTING_BALANCE);
            double newBalance = CurrencyUtil.round(currentBalance + amount);

            if (!CurrencyUtil.isValidBalance(newBalance)) {
                SolidusMod.LOGGER.warn("Balance overflow prevented for UUID: {} (would be {})",
                    uuid, newBalance);
                return -1.0;
            }

            balanceCache.put(uuid, newBalance);
            boolean success = persistBalance(uuid, playerName, newBalance);
            if (!success) {
                balanceCache.put(uuid, currentBalance);
                SolidusMod.LOGGER.error("Failed to persist add-balance for UUID: {}. Cache rolled back.", uuid);
                return -1.0;
            }

            return newBalance;
        }, asyncExecutor);
    }

    /**
     * Atomically subtracts an amount from a player's balance.
     *
     * @param uuid       The player's unique ID
     * @param playerName The player's display name
     * @param amount     The amount to subtract (must be positive)
     * @return CompletableFuture with the new balance, or -1 on failure/insufficient funds
     */
    public CompletableFuture<Double> subtractBalance(UUID uuid, String playerName, double amount) {
        ensureInitialized();
        amount = CurrencyUtil.round(amount);

        return CompletableFuture.supplyAsync(() -> {
            double currentBalance = balanceCache.getOrDefault(uuid, CurrencyUtil.DEFAULT_STARTING_BALANCE);

            if (currentBalance < amount) {
                return -1.0;
            }

            double newBalance = CurrencyUtil.round(currentBalance - amount);
            balanceCache.put(uuid, newBalance);
            boolean success = persistBalance(uuid, playerName, newBalance);
            if (!success) {
                balanceCache.put(uuid, currentBalance);
                SolidusMod.LOGGER.error("Failed to persist subtract-balance for UUID: {}. Cache rolled back.", uuid);
                return -1.0;
            }

            return newBalance;
        }, asyncExecutor);
    }

    /**
     * Checks whether a player has at least the specified amount.
     *
     * @param uuid   The player's unique ID
     * @param amount The amount to check against
     * @return CompletableFuture with true if the player can afford it
     */
    public CompletableFuture<Boolean> hasBalance(UUID uuid, double amount) {
        return getBalance(uuid, "").thenApply(balance -> balance >= amount);
    }

    // ── Internal Persistence Helpers ─────────────────────

    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("SQLiteStorage accessed before initialization!");
        }
    }

    /**
     * Persists a balance update to SQLite using the persistent connection.
     * Called from the single-threaded executor — no locking needed.
     * Also updates the playerNameCache to keep names current.
     *
     * Uses UPSERT to handle both new and existing players in a single statement,
     * eliminating the need for separate INSERT/UPDATE logic and guaranteeing
     * correct ordering even for newly created player entries.
     */
    private boolean persistBalance(UUID uuid, String playerName, double balance) {
        if (playerName != null && !playerName.isEmpty()) {
            playerNameCache.put(uuid, playerName);
        }

        String upsertSql = """
            INSERT INTO player_balances (uuid, player_name, balance, last_updated)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                balance = excluded.balance,
                player_name = excluded.player_name,
                last_updated = excluded.last_updated
        """;
        try (PreparedStatement ps = persistentConnection.prepareStatement(upsertSql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, playerName);
            ps.setDouble(3, balance);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            SolidusMod.LOGGER.error("Failed to persist balance for UUID: {}", uuid, e);
            return false;
        }
    }

    /**
     * Persists a new player record to SQLite using the persistent connection.
     *
     * Uses UPSERT (INSERT ... ON CONFLICT DO UPDATE) instead of INSERT OR IGNORE
     * to guarantee correct ordering: since this is submitted to the SAME executor
     * queue as all other mutations, it will execute in submission order. If a
     * subtractBalance() for the same UUID arrives after this, the UPSERT ensures
     * the player row exists before the subtract's UPSERT runs.
     *
     * Previous issue: INSERT OR IGNORE could silently fail if a row already existed,
     * and a subsequent UPSERT from subtractBalance could execute before the INSERT,
     * causing unexpected behavior. The UPSERT approach handles all cases correctly.
     */
    private void asyncPersistNewPlayer(UUID uuid, String playerName, double balance) {
        CompletableFuture.runAsync(() -> {
            String upsertSql = """
                INSERT INTO player_balances (uuid, player_name, balance, last_updated)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    player_name = excluded.player_name,
                    last_updated = excluded.last_updated
            """;
            try (PreparedStatement ps = persistentConnection.prepareStatement(upsertSql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, playerName);
                ps.setDouble(3, balance);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                SolidusMod.LOGGER.error("Failed to persist new player: {}", uuid, e);
            }
        }, asyncExecutor);
    }

    /**
     * Returns the transaction log instance.
     * Available after initialize() has been called.
     */
    public TransactionLog getTransactionLog() {
        return transactionLog;
    }

    /**
     * Returns the player name cache for offline player lookups.
     * Maps UUID → last known player name.
     */
    public ConcurrentHashMap<UUID, String> getPlayerNameCache() {
        return playerNameCache;
    }

    /**
     * Immutable data class representing a leaderboard entry.
     */
    public record BalanceEntry(int rank, String playerName, double balance) {}
}
