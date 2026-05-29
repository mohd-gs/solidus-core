# Solidus Architecture

Technical architecture, project structure, APIs, and design decisions for Solidus — the server-side Economy & Commerce Engine for Minecraft Fabric.

For user-facing documentation, see [README.md](../README.md).

---

## Table of Contents

- [Project Structure](#project-structure)
- [System Architecture](#system-architecture)
- [Initialization Flow](#initialization-flow)
- [Core Subsystems](#core-subsystems)
  - [Economy Engine](#economy-engine)
  - [SQLite Storage](#sqlite-storage)
  - [Balance Manager](#balance-manager)
  - [Transaction Log](#transaction-log)
  - [Shop System](#shop-system)
  - [Auction System](#auction-system)
  - [Packet Interception](#packet-interception)
  - [Rate Limiter](#rate-limiter)
  - [Mixin Layer](#mixin-layer)
- [Utility APIs](#utility-apis)
  - [CurrencyUtil](#currencyutil)
  - [TextUtil](#textutil)
  - [ConfigManager](#configmanager)
- [Inter-Mod API](#inter-mod-api)
  - [SolidusAPI Reference](#solidusapi-reference)
  - [SolidusIntegration Example](#solidusintegration-example)
  - [Transaction Types](#transaction-types)
- [Database Schema](#database-schema)
- [Configuration Format](#configuration-format)
- [Economy Balancing](#economy-balancing)
- [Build System](#build-system)
- [Critical Rules for Contributors](#critical-rules-for-contributors)

---

## Project Structure

```
solidus/
├── build.gradle
├── settings.gradle
├── gradle.properties
├── src/main/java/com/solidus/
│   ├── SolidusMod.java                  # Main entry point
│   ├── api/
│   │   ├── SolidusAPI.java              # Stable public API for inter-mod integration
│   │   └── SolidusIntegration.java      # Reference implementation (CombatKeepMod example)
│   ├── economy/
│   │   ├── EconomyEngine.java           # Central coordinator (executor queue)
│   │   ├── SQLiteStorage.java           # Async SQLite + in-memory cache
│   │   ├── TransactionLog.java          # Audit trail & offline notifications
│   │   └── BalanceManager.java          # High-level balance API (online + offline)
│   ├── commands/
│   │   ├── BalanceCommand.java          # /balance, /bal
│   │   ├── PayCommand.java              # /pay (online + offline)
│   │   ├── BaltopCommand.java           # /baltop
│   │   ├── ShopCommand.java             # /shop, /shop search
│   │   ├── AuctionCommand.java          # /ah, /ah sell, /ah collect, /ah cancel, /ah sort
│   │   └── TransactionsCommand.java     # /transactions
│   ├── shop/
│   │   ├── ShopManager.java             # Shop config loader & Codec parser
│   │   ├── ShopGUI.java                 # GUI layout builder
│   │   ├── ShopScreenHandler.java       # Native ScreenHandler
│   │   └── ShopDummyContainer.java      # Display-only container
│   ├── auction/
│   │   ├── AuctionManager.java          # Auction engine (executor queue)
│   │   ├── AuctionGUI.java              # Auction GUI builder
│   │   ├── AuctionScreenHandler.java    # Native ScreenHandler
│   │   ├── AuctionEntry.java            # Listing data model
│   │   ├── ListingStatus.java           # ACTIVE / SOLD / EXPIRED enum
│   │   └── AuctionDummyContainer.java   # Display-only container
│   ├── networking/
│   │   ├── PacketHandler.java           # Packet interception & routing
│   │   └── RateLimiter.java             # Click cooldown manager
│   ├── mixin/
│   │   ├── ServerPlayerEntityMixin.java # Container click + ghost item fix
│   │   └── ScreenHandlerMixin.java      # Quick-move blocker + resync
│   └── util/
│       ├── TextUtil.java                # Component factory (NO legacy chars)
│       ├── CurrencyUtil.java            # Currency formatting & limits
│       └── ConfigManager.java           # File I/O manager
├── src/main/resources/
│   ├── fabric.mod.json
│   ├── solidus.mixins.json
│   ├── shop.json                        # 120+ items, 11 sections
│   └── pack.mcmeta
├── docs/
│   └── ARCHITECTURE.md                  # This file
├── README.md                            # User-facing documentation
└── LICENSE                              # MIT License
```

---

## System Architecture

### Layer Overview

```
┌─────────────────────────────────────────────┐
│             Public API (Stable)             │
│  SolidusAPI · SolidusIntegration            │
├─────────────────────────────────────────────┤
│                 Commands                     │
│  BalanceCommand · PayCommand · BaltopCommand │
│  ShopCommand · AuctionCommand                │
│  TransactionsCommand                        │
├─────────────────────────────────────────────┤
│              Business Logic                  │
│  EconomyEngine · BalanceManager              │
│  ShopManager · AuctionManager                │
│  TransactionLog                              │
├─────────────────────────────────────────────┤
│              Presentation                    │
│  ShopGUI · ShopScreenHandler                │
│  AuctionGUI · AuctionScreenHandler           │
│  ShopDummyContainer · AuctionDummyContainer  │
├─────────────────────────────────────────────┤
│              Infrastructure                  │
│  SQLiteStorage · PacketHandler               │
│  RateLimiter · ConfigManager                 │
│  TextUtil · CurrencyUtil                     │
├─────────────────────────────────────────────┤
│              Mixins                          │
│  ServerPlayerEntityMixin · ScreenHandlerMixin│
└─────────────────────────────────────────────┘
```

### Thread Architecture

Solidus uses two dedicated single-threaded executors to guarantee sequential consistency:

| Executor | Thread Name | Responsibility |
| --- | --- | --- |
| **Economy Worker** | `Solidus-Economy-Worker` | All balance mutations, transaction logging, player data persistence |
| **Auction Worker** | `Solidus-Auction-Worker` | All auction listing mutations, purchase atomicity, expiration processing |
| **Server Tick Thread** | `Server thread` | Command handling, GUI interactions, item spawning, player notifications |

The server tick thread **never blocks** — all async operations use `CompletableFuture` chaining (`.thenAccept()`, `.thenCompose()`) with `server.execute()` callbacks for game-state updates.

---

## Initialization Flow

The `SolidusMod.onInitializeServer()` method starts all subsystems in strict dependency order:

```
1. RateLimiter              → No dependencies
2. EconomyEngine            → Creates ConfigManager, SQLiteStorage, BalanceManager
3. ShopManager              → Depends on EconomyEngine
4. AuctionManager           → Depends on EconomyEngine
5. PacketHandler            → Depends on ShopManager, AuctionManager, RateLimiter
6. Command Registration     → Depends on all managers
7. Shutdown Hook            → Registered for clean database closure
8. SERVER_STARTED Event     → Injects MinecraftServer into AuctionManager, initializes SolidusAPI
9. Player Join Event        → Delivers pending offline notifications
```

Key design decisions:
- `MinecraftServer.getServer()` does **not exist** in Fabric, so the server instance is injected via `ServerLifecycleEvents.SERVER_STARTED`
- `SolidusAPI.initialize()` is called **after** the server starts, ensuring external mods can only access the API when the economy is fully ready
- Offline notification delivery is triggered by `ServerPlayConnectionEvents.JOIN`

---

## Core Subsystems

### Economy Engine

The `EconomyEngine` is the central coordinator. It owns:

- **BalanceManager** — High-level API for balance reads, writes, and transfers
- **SQLiteStorage** — Async persistence layer with in-memory cache
- **TransactionLog** — Audit trail and offline notification system

All balance mutations flow through a **Single-Threaded Executor Queue**:

```
Player Action → Command → BalanceManager → ExecutorService.submit()
                                                ↓
                                         Sequential Processing
                                                ↓
                                    1. Validate operation
                                    2. Update in-memory cache
                                    3. Async SQLite write (UPSERT)
                                    4. Rollback cache on failure
                                    5. Return CompletableFuture
```

**Cache-first strategy:** Reads are served instantly from `ConcurrentHashMap` — no database query needed. Writes update the cache first, then persist asynchronously. If the database write fails, the cache is rolled back to the previous value.

### SQLite Storage

The `SQLiteStorage` class manages the persistent data layer.

**Architecture:**
- **Persistent Connection** — A single `Connection` is shared across all executor operations. Since the single-threaded executor serializes all access, connection sharing is inherently safe. This eliminates the overhead of opening/closing connections per operation.
- **WAL Mode** — Write-Ahead Logging allows concurrent reads while writing, ensuring committed transactions survive crashes.
- **In-memory Cache** — `ConcurrentHashMap<UUID, Double>` for instant balance reads, plus `ConcurrentHashMap<UUID, String>` for player name lookups.
- **UPSERT Strategy** — New player inserts use `INSERT ... ON CONFLICT DO UPDATE` to guarantee ordering safety. Since all operations go through the same executor queue, a new player's INSERT completes before any subsequent UPDATE.
- **Startup Pre-load** — All existing balances are loaded from the database into the in-memory cache on initialization.

**SQLite PRAGMA Configuration:**

| PRAGMA | Value | Purpose |
| --- | --- | --- |
| `journal_mode` | WAL | Crash resilience, concurrent reads |
| `synchronous` | NORMAL | Balance between safety and performance |
| `temp_store` | MEMORY | Temporary tables in RAM |
| `mmap_size` | 64MB | Memory-mapped I/O for faster reads |
| `cache_size` | 2MB | SQLite page cache |

**Write Flow with Rollback:**

```
1. Get previous balance from cache
2. Update cache with new balance
3. Persist to SQLite via UPSERT
4. If persist fails → rollback cache to previous value
5. If persist succeeds → return new balance
```

### Balance Manager

The `BalanceManager` provides the high-level business logic layer over `SQLiteStorage`.

**Key Operations:**

| Method | Description | Failure Return |
| --- | --- | --- |
| `getBalance(player)` | Get online player balance (instant from cache) | Starting balance (creates new entry) |
| `getBalance(uuid, name)` | Get offline player balance (instant from cache) | Starting balance (creates new entry) |
| `setBalance(player, amount)` | Set exact balance (admin operation) | `false` |
| `addBalance(player, amount)` | Add currency to online player | `-1.0` |
| `addBalance(uuid, name, amount)` | Add currency to offline player | `-1.0` |
| `subtractBalance(player, amount)` | Subtract from online player | `-1.0` (insufficient funds) |
| `subtractBalance(uuid, name, amount)` | Subtract from offline player | `-1.0` (insufficient funds) |
| `hasSufficientBalance(player, amount)` | Check affordability | `false` |
| `transfer(sender, receiver, amount)` | Atomic online P2P transfer | `TransferResult` with failure reason |
| `transferOffline(...)` | Atomic offline P2P transfer | `TransferResult` with failure reason |
| `getTopBalances(limit)` | Leaderboard entries | Empty list |

**Transfer Anti-Exploit Protections:**

The `transferOffline()` method enforces these checks before any database operation:

1. **Negative amount rejection** — prevents reverse-transfer exploits
2. **Zero amount rejection** — prevents spam
3. **Self-transfer rejection** — prevents confusion
4. **Maximum transaction cap** — enforced via `CurrencyUtil.MAX_TRANSACTION`
5. **Atomic deduct-then-add** — if deduction from sender succeeds but addition to receiver fails, the sender is refunded automatically

**TransferResult Record:**

```java
public record TransferResult(
    boolean success,
    String message,
    double senderNewBalance,
    double receiverNewBalance
) {}
```

### Transaction Log

The `TransactionLog` provides persistent audit trail and offline notification delivery.

**Architecture:**
- Shares the economy.db persistent connection and executor with `SQLiteStorage` (no separate thread pool or connection)
- All transaction records are stored in SQLite for durability
- Offline player notifications are queued and delivered on login

**Logging API:**

```java
void log(Type type, UUID playerUuid, String playerName,
         UUID targetUuid, String targetName,
         double amount, String itemMaterial, int itemQuantity,
         String description)
```

This is fire-and-forget — the log operation runs asynchronously on the economy worker thread with no return value needed.

**Querying:**

```java
CompletableFuture<List<TransactionEntry>> getTransactions(UUID playerUuid, int limit)
```

Returns the most recent transactions for a player, ordered by timestamp descending.

**TransactionEntry Record:**

```java
public record TransactionEntry(
    long timestamp,
    Type type,
    UUID playerUuid,
    String playerName,
    UUID targetUuid,
    String targetName,
    double amount,
    String itemMaterial,
    int itemQuantity,
    String description
) {}
```

**Offline Notification System:**

When a transaction involves an offline player (e.g., auction seller receiving payment while offline), a pending notification is stored:

1. If the player is online → deliver immediately via `sendSystemMessage()`
2. If offline → store in both `ConcurrentHashMap` and `pending_notifications` table
3. On player login → `deliverPendingNotifications()` delivers all cached and persisted notifications, then deletes them from the database

This dual-cache approach ensures notifications survive server restarts while providing fast delivery for recently disconnected players.

### Shop System

The shop operates entirely via server-driven packet manipulation. The client sees a standard `ChestMenu`, but all slot interactions are intercepted and processed server-side.

**ShopManager** — Loads `shop.json` and processes buy/sell transactions:
- Parses text components using `ComponentSerialization.CODEC` (Minecraft's official parser)
- Supports hot-reload without server restart
- Uses `ConcurrentHashMap` for thread-safe section access

**ShopGUI** — Builds the 6-row chest inventory layout with category navigation

**ShopScreenHandler** — Extends `ScreenHandler` (GENERIC_9x6), manages buy/sell logic and click routing

**ShopDummyContainer** — `SimpleContainer` that rejects all item modifications (display-only protection)

**Data Models:**

```java
// A shop section (category)
public record ShopSection(
    String key,
    Component displayName,
    String icon,
    List<ShopItem> items
) {}

// A single shop item with pricing
public record ShopItem(
    String material,      // Minecraft Material name (e.g., "DIAMOND")
    double buyPrice,      // Price to buy 1 unit (-1 = not purchasable)
    double sellPrice      // Price received for selling 1 unit (-1 = not sellable)
) {}
```

**Buy Transaction Flow (fully async):**

```
1. Validate item exists and has a valid buy price
2. Check player balance (instant from cache)
3. Deduct price atomically (async chain)
4. Spawn item into player's inventory
5. If inventory full → drop at player's feet
6. Log SHOP_BUY transaction
```

**Sell Transaction Flow (fully async):**

```
1. Validate item exists and has a valid sell price
2. Verify player has the item in inventory
3. Remove item from inventory (synchronous — prevents double-sell)
4. Add sell price to balance (async chain)
5. Log SHOP_SELL transaction

IMPORTANT: Items are removed BEFORE the async balance add. If the
balance add fails, items are already gone. This is logged as CRITICAL.
```

### Auction System

The auction house enables peer-to-peer commerce with race condition protection.

**AuctionManager** — Manages all auction operations with its own single-threaded executor:
- Separate database file (`auctions.db`) with persistent connection
- WAL mode and UPSERT strategy (same as economy database)
- `MinecraftServer` instance injected via `SERVER_STARTED` event (not available statically in Fabric)

**AuctionEntry** — Immutable data model for listings:

```java
public record AuctionEntry(
    UUID listingId,           // Unique identifier
    UUID sellerUuid,          // Seller's UUID
    String sellerName,        // Cached seller display name
    String materialName,      // Minecraft Material name
    int quantity,             // Stack size
    String itemNbt,           // Serialized item data (for enchanted/custom items)
    double price,             // Listed sale price
    long listedTimestamp,     // Epoch millis when listed
    long expireTimestamp,     // Epoch millis when listing expires
    ListingStatus status      // Current lifecycle status
) {}
```

**Auction Constants:**

| Constant | Value | Description |
| --- | --- | --- |
| `DEFAULT_DURATION_MS` | 72 hours | Default listing duration |
| `MAX_DURATION_MS` | 168 hours (7 days) | Maximum listing duration |
| `MIN_LISTING_PRICE` | 1.0 S$ | Minimum listing price |
| `MAX_LISTING_PRICE` | 10,000,000 S$ | Maximum listing price |
| `LISTING_FEE_PERCENT` | 2% | Fee deducted on listing (minimum 1 S$) |

**Listing Fee Calculation:**

```java
public static double calculateListingFee(double price) {
    return Math.max(1.0, price * LISTING_FEE_PERCENT);
}
```

**ListingStatus Enum:**

| Status | DB Code | Description |
| --- | --- | --- |
| `ACTIVE` | 0 | Listed and available for purchase |
| `SOLD` | 1 | Purchased by another player |
| `EXPIRED` | 2 | Duration exceeded, item should be returned |

Unknown database codes default to `EXPIRED` for safety — prevents stale listings from appearing as active.

**SortOrder Enum:**

| Sort Order | SQL ORDER BY |
| --- | --- |
| `NEWEST` | `listed_timestamp DESC` |
| `PRICE_LOW` | `price ASC` |
| `PRICE_HIGH` | `price DESC` |
| `MATERIAL` | `material_name ASC, price ASC` |

**Purchase Flow (race condition protected):**

```
1. On Auction Worker thread:
   a. SELECT listing WHERE status = 0 (ACTIVE)
   b. Verify not expired, not buyer's own item
   c. UPDATE status to 1 (SOLD) IMMEDIATELY
   d. ← The executor IS the lock — no other thread can interfere

2. Back on Server Thread:
   a. Check buyer balance (instant from cache)
   b. If insufficient → rollback status to 0 (ACTIVE)
   c. Deduct from buyer (async)
   d. If deduction fails → rollback status to 0
   e. Add to seller (offline-safe, async)
   f. Spawn item into buyer's inventory
   g. Log AUCTION_BOUGHT and AUCTION_SOLD transactions
   h. Queue notification for seller (delivers immediately if online)
```

**Cancel Flow:**

```
1. On Auction Worker: verify listing is ACTIVE and belongs to seller
2. Mark as EXPIRED (status=2)
3. Return item to seller's inventory
```

**Collect Expired Items Flow:**

```
1. Query all EXPIRED listings for this seller
2. Give items to player (drop at feet if inventory full)
3. Delete collected listings from database
```

### Packet Interception

The `PacketHandler` is the gateway between raw network packets and high-level ScreenHandler processing.

**Flow:**

```
Client Click Packet
    ↓
ServerPlayerEntityMixin (intercepts at player level)
    ↓
PacketHandler.handleContainerClick()
    ↓
RateLimiter.allowClick() — 150ms cooldown check
    ↓
Route to ShopScreenHandler.clicked() or AuctionScreenHandler.clicked()
    ↓
ScreenHandlerMixin — Blocks quick-move (shift-click) + calls sendContentUpdates()
```

**Key Methods:**

| Method | Description |
| --- | --- |
| `handleContainerClick(player, slotIndex, button, clickType)` | Routes click to appropriate ScreenHandler; returns `true` if consumed by Solidus |
| `hasSolidusScreenOpen(player)` | Checks if player has a Shop or Auction screen open |
| `register()` | Registers disconnect handler for rate limiter cleanup |

### Rate Limiter

The `RateLimiter` prevents cheat clients from exploiting virtual menus through packet flooding.

**Configuration:**

| Setting | Value | Description |
| --- | --- | --- |
| `MIN_CLICK_INTERVAL_MS` | 150ms | Maximum ~6.6 clicks/second |
| `CLEANUP_INTERVAL_MS` | 60 seconds | How often to clean stale entries |
| `STALE_THRESHOLD_MS` | 5 minutes | How long before an entry is considered stale |

**Design:**
- Uses `ConcurrentHashMap.compute()` for atomic check-then-update, preventing race conditions even under concurrent access
- Clicks that come too fast are **silently dropped** — the client never knows
- Stale entries are periodically cleaned up to prevent memory leaks
- Player disconnect triggers immediate cleanup via `ServerPlayConnectionEvents.DISCONNECT`

### Mixin Layer

Two mixins handle virtual GUI protection at the Minecraft level:

**ServerPlayerEntityMixin:**
- Intercepts `ServerboundContainerClickPacket` at the player level
- Routes clicks through `PacketHandler.handleContainerClick()`
- If the click is consumed by Solidus → cancel the packet
- Always calls `sendContentUpdates()` after cancellation to prevent ghost items

**ScreenHandlerMixin:**
- Blocks quick-move (shift-click) operations in virtual containers
- Calls `sendContentUpdates()` after every cancellation

Both mixins are essential — without `sendContentUpdates()`, canceled clicks would leave "ghost items" on the client that don't exist on the server.

---

## Utility APIs

### CurrencyUtil

Centralized currency constants, formatting, and validation.

**Constants:**

| Constant | Value | Description |
| --- | --- | --- |
| `CURRENCY_SYMBOL` | `S$` | Display symbol |
| `CURRENCY_NAME` | `Solidus` | Full currency name |
| `DEFAULT_STARTING_BALANCE` | 500.0 | New player starting balance |
| `MIN_TRANSACTION` | 0.01 | Minimum transaction amount (prevents dust) |
| `MAX_TRANSACTION` | 10,000,000.0 | Maximum single transaction (anti-exploit) |
| `MAX_BALANCE` | 100,000,000.0 | Maximum player balance |

**Methods:**

| Method | Description |
| --- | --- |
| `format(amount)` | Full format: `"1,250.5 S$"` |
| `formatCompact(amount)` | Compact format: `"1.2M S$"`, `"5.3K S$"` |
| `isValidAmount(amount)` | Validates `MIN_TRANSACTION <= amount <= MAX_TRANSACTION`, no NaN/Infinite |
| `isValidBalance(balance)` | Validates `0.0 <= balance <= MAX_BALANCE`, no NaN/Infinite |
| `round(amount)` | Rounds to 2 decimal places (prevents floating-point drift) |

### TextUtil

Component factory for all player-facing text. This is the **crash prevention layer** — using legacy formatting characters (`§`) will cause client disconnects and thread crashes.

**Methods:**

| Method | Formatting | Use Case |
| --- | --- | --- |
| `styled(text, color)` | Colored | General styled text |
| `styledBold(text, color)` | Bold + colored | Emphasized text |
| `styledItalic(text, color)` | Italic + colored | Descriptions |
| `styledBoldItalic(text, color)` | Bold + italic + colored | Strong emphasis |
| `plain(text)` | No formatting | Raw text |
| `error(text)` | Red + bold | Error messages |
| `success(text)` | Green | Success messages |
| `warning(text)` | Yellow | Warning messages |
| `currency(text)` | Gold | Currency amounts |
| `shopTitle(text)` | Gold + bold | Shop title |
| `sectionHeader(text)` | Dark aqua + bold | Category headers |
| `loreLine(text)` | Gray + italic | Item descriptions |
| `buyPriceLore(price)` | Green | Buy price in lore |
| `sellPriceLore(price)` | Red | Sell price in lore |
| `sanitizeLegacyFormatting(input)` | Strip `§` codes | Safety net for external input |

### ConfigManager

File I/O manager for all Solidus configuration. Manages the `config/solidus/` directory.

**Methods:**

| Method | Description |
| --- | --- |
| `initialize(serverRunDir)` | Creates `config/solidus/` directory |
| `getConfigDir()` | Returns the config directory path |
| `loadJson(fileName)` | Loads and parses a JSON config file |
| `saveJson(fileName, json)` | Saves a JSON config file (pretty-printed) |
| `copyDefaultIfMissing(resourceName, fileName)` | Copies default from JAR if file doesn't exist |
| `readFile(fileName)` | Reads a config file as raw string |
| `getGson()` | Returns the shared Gson instance |

**Config Directory Structure:**

```
<server-root>/
├── config/
│   └── solidus/
│       ├── shop.json        # Shop configuration (120+ items)
│       ├── economy.db       # Player balances & transactions
│       └── auctions.db      # Auction listings
```

---

## Inter-Mod API

Solidus provides a **stable public API** in `com.solidus.api.SolidusAPI` for other mods to integrate with the economy system. This is the **only** class external mods should depend on — internal classes may change between versions without notice.

### SolidusAPI Reference

**Lifecycle:**
- `initialize(EconomyEngine)` — Called once by SolidusMod during `SERVER_STARTED`. External mods must NOT call this.
- `getInstance()` — Returns the API singleton, or `null` if Solidus is not loaded.
- `isAvailable()` — Fast, non-blocking check if Solidus is loaded and ready.

**Balance Operations (Online Players):**

| Method | Return Type | Description |
| --- | --- | --- |
| `getBalance(ServerPlayer)` | `CompletableFuture<Double>` | Get online player's balance |
| `addBalance(ServerPlayer, double)` | `CompletableFuture<Double>` | Add to online player (returns new balance, or -1 on failure) |
| `subtractBalance(ServerPlayer, double)` | `CompletableFuture<Double>` | Subtract from online player (returns -1.0 if insufficient funds) |
| `hasSufficientBalance(ServerPlayer, double)` | `CompletableFuture<Boolean>` | Check affordability |

**Balance Operations (Offline Players):**

| Method | Return Type | Description |
| --- | --- | --- |
| `getBalanceOffline(UUID, String)` | `CompletableFuture<Double>` | Get offline player's balance |
| `addBalanceOffline(UUID, String, double)` | `CompletableFuture<Double>` | Add to offline player |
| `subtractBalanceOffline(UUID, String, double)` | `CompletableFuture<Double>` | Subtract from offline player |

**Transfer Operations:**

| Method | Return Type | Description |
| --- | --- | --- |
| `transfer(ServerPlayer, ServerPlayer, double)` | `CompletableFuture<TransferResult>` | Atomic online P2P transfer |
| `transferOffline(UUID, String, UUID, String, double)` | `CompletableFuture<TransferResult>` | Atomic offline transfer |

**Other:**

| Method | Return Type | Description |
| --- | --- | --- |
| `getTopBalances(int)` | `CompletableFuture<List<BalanceEntry>>` | Leaderboard data |
| `getTransactionLog()` | `TransactionLog` | For logging custom events (null if not initialized) |
| `getEconomyEngine()` | `EconomyEngine` | Advanced use only — direct access to internals |

**Thread Safety:** All methods return `CompletableFuture` and execute asynchronously on Solidus's economy worker thread. Callers on the server tick thread must use `.thenAccept()` + `server.execute()` for any UI or game-state updates.

### SolidusIntegration Example

`SolidusIntegration.java` is a reference implementation showing how to integrate with Solidus using **pure reflection** (zero compile-time dependency). Other mods should copy and adapt this pattern into their own packages.

**Key Methods:**

| Method | Description |
| --- | --- |
| `isSolidusAvailable()` | Check if Solidus is loaded via `FabricLoader` |
| `getAPI()` | Get SolidusAPI instance via reflection (null if unavailable) |
| `applyDeathPenalty(victim, killer, penaltyPercent)` | Full death penalty implementation with async balance operations and player notifications |

**Integration Pattern (No Compile Dependency):**

```java
// 1. Check if Solidus is loaded
if (!FabricLoader.getInstance().isModLoaded("solidus")) return;

// 2. Get the API instance via reflection
Class<?> apiClass = Class.forName("com.solidus.api.SolidusAPI");
Method getInstance = apiClass.getMethod("getInstance");
Object api = getInstance.invoke(null);
if (api == null) return;

// 3. Call methods via reflection
Method getBalance = apiClass.getMethod("getBalance", ServerPlayer.class);
CompletableFuture<Double> balance = (CompletableFuture<Double>) getBalance.invoke(api, player);
```

**fabric.mod.json Integration:**

```json
{
  "suggests": {
    "solidus": "*"
  }
}
```

### Transaction Types

The `TransactionLog.Type` enum defines all transaction categories:

| Type | Code | Description | Triggered By |
| --- | --- | --- | --- |
| `SHOP_BUY` | `SHOP_BUY` | Player purchased from server shop | ShopManager.processBuy() |
| `SHOP_SELL` | `SHOP_SELL` | Player sold to server shop | ShopManager.processSell() |
| `AUCTION_LIST` | `AUCTION_LIST` | Player listed an item | AuctionManager.listItem() |
| `AUCTION_SOLD` | `AUCTION_SOLD` | Player's item was purchased | AuctionManager.purchaseItem() (seller log) |
| `AUCTION_BOUGHT` | `AUCTION_BOUGHT` | Player purchased from auction | AuctionManager.purchaseItem() (buyer log) |
| `AUCTION_EXPIRED` | `AUCTION_EXPIRED` | Listing expired | AuctionManager.processExpiredListings() |
| `PAY_SEND` | `PAY_SEND` | Player sent money | PayCommand |
| `PAY_RECEIVE` | `PAY_RECEIVE` | Player received money | PayCommand |
| `DEATH_PENALTY` | `DEATH_PENALTY` | Player lost money from being killed | External mod (e.g., CombatKeepMod) |
| `DEATH_REWARD` | `DEATH_REWARD` | Player gained money from killing | External mod (e.g., CombatKeepMod) |

External mods can log custom transactions via `getTransactionLog().log(...)` so they appear in the player's `/transactions` history. The `fromCode()` method provides a safe fallback to `PAY_SEND` for unknown codes.

---

## Database Schema

Solidus uses two SQLite databases, both in `config/solidus/`:

### economy.db

**player_balances table:**

| Column | Type | Description |
| --- | --- | --- |
| `uuid` | TEXT (PK) | Player UUID |
| `player_name` | TEXT | Last known player name |
| `balance` | REAL | Current balance |
| `last_updated` | INTEGER | Epoch millis of last update |

**Index:** `idx_balance_rank` on `(balance DESC)` — optimizes leaderboard queries.

**transaction_log table:**

| Column | Type | Description |
| --- | --- | --- |
| `id` | INTEGER (PK, AUTO) | Auto-increment ID |
| `timestamp` | INTEGER | Epoch millis |
| `type` | TEXT | Transaction type code (e.g., `SHOP_BUY`) |
| `player_uuid` | TEXT | Primary player UUID |
| `player_name` | TEXT | Primary player name |
| `target_uuid` | TEXT | Secondary player UUID (nullable) |
| `target_name` | TEXT | Secondary player name (nullable) |
| `amount` | REAL | Currency amount |
| `item_material` | TEXT | Item material name (nullable) |
| `item_quantity` | INTEGER | Item count (0 if N/A) |
| `description` | TEXT | Human-readable description |

**Index:** `idx_transaction_player` on `(player_uuid, timestamp DESC)` — optimizes player transaction history queries.

**pending_notifications table:**

| Column | Type | Description |
| --- | --- | --- |
| `id` | INTEGER (PK, AUTO) | Auto-increment ID |
| `timestamp` | INTEGER | Epoch millis |
| `player_uuid` | TEXT | Target player UUID |
| `message` | TEXT | Notification message |

**Index:** `idx_notifications_player` on `(player_uuid)` — optimizes lookup on player login.

### auctions.db

**auction_listings table:**

| Column | Type | Description |
| --- | --- | --- |
| `listing_id` | TEXT (PK) | UUID of the listing |
| `seller_uuid` | TEXT | Seller's UUID |
| `seller_name` | TEXT | Seller's display name |
| `material_name` | TEXT | Minecraft Material name |
| `quantity` | INTEGER | Stack size |
| `item_nbt` | TEXT | Serialized item data (nullable) |
| `price` | REAL | Listed sale price |
| `listed_timestamp` | INTEGER | Epoch millis when listed |
| `expire_timestamp` | INTEGER | Epoch millis when listing expires |
| `status` | INTEGER | 0=ACTIVE, 1=SOLD, 2=EXPIRED |

**Index:** `idx_active_listings` on `(status, expire_timestamp)` — optimizes active listing queries and expiration processing.

---

## Configuration Format

### shop.json

The shop configuration uses JSON with Minecraft text component syntax for display names:

```json
{
  "sections": {
    "section_key": {
      "display_name": {
        "text": "Section Name",
        "color": "gold",
        "bold": true
      },
      "icon": "MATERIAL_NAME",
      "items": {
        "1": {
          "material": "DIAMOND",
          "buy-price": 150,
          "sell-price": 60
        },
        "2": {
          "material": "EMERALD",
          "buy-price": 30,
          "sell-price": -1
        }
      }
    }
  }
}
```

**Field Reference:**

| Field | Type | Description |
| --- | --- | --- |
| `sections` | Object | Map of section key → section data |
| `display_name` | Object | Minecraft text component (parsed by `ComponentSerialization.CODEC`) |
| `icon` | String | Material name for the category icon (default: `CHEST`) |
| `items` | Object | Map of numeric key → item data |
| `material` | String | Minecraft Material name (required) |
| `buy-price` | Number | Price to buy 1 unit (-1 = not purchasable, optional) |
| `sell-price` | Number | Price for selling 1 unit (-1 = not sellable, optional) |

**Text Component Format:**

The `display_name` field supports the full Minecraft JSON text component format:

```json
{
  "text": "Display Text",
  "color": "gold",
  "bold": true,
  "italic": false,
  "underlined": false,
  "strikethrough": false,
  "obfuscated": false,
  "hoverEvent": { ... },
  "clickEvent": { ... },
  "extra": [ ... ]
}
```

This is parsed by `ComponentSerialization.CODEC` — Minecraft's official parser — ensuring full forward compatibility with any changes Mojang makes to the Component system.

**Hot Reload:** Changes to `shop.json` can be applied without restarting the server. The `ShopManager.loadConfiguration()` method safely swaps the section map using `ConcurrentHashMap`.

---

## Economy Balancing

Sell prices for farmed resources are configured directly in `shop.json` by the server operator. This approach gives operators full control over economic balance — they can adjust individual item prices to counter inflation from automated farms without needing code changes or server restarts.

**How it works:** Prices are defined per-item in the `sell-price` field within `shop.json`. To reduce the profitability of farmed resources, operators simply set lower sell prices for those items. Changes take effect immediately with hot-reload support.

**Example:** The default `shop.json` already includes reduced sell prices for commonly farmed items:

| Item | Buy Price | Sell Price | Notes |
| --- | --- | --- | --- |
| EMERALD | 30 S$ | 4.5 S$ | Raid farm countermeasure |
| GOLD_INGOT | 30 S$ | 7.5 S$ | Piglin farm countermeasure |
| IRON_INGOT | 15 S$ | 6 S$ | Iron farm countermeasure |
| SHULKER_SHELL | 225 S$ | 42.5 S$ | Shulker farm countermeasure |
| TRIAL_KEY | 750 S$ | 45 S$ | Trial farm countermeasure |
| SCUTE | 300 S$ | 40 S$ | Turtle farm countermeasure |

Operators can further adjust these values at any time by editing `shop.json`.

---

## Build System

> **Minecraft 26.1.x Migration**: This project uses **Mojang Official Mappings** (Yarn is retired). Requires **Java 25**, **Gradle 9.4+**, and **Fabric Loom 1.16+**.

```bash
git clone <repository>
cd solidus
./gradlew build
```

Output: `build/libs/`

### Build Configuration

| Setting | Value | Notes |
| --- | --- | --- |
| Loom Plugin | `net.fabricmc.fabric-loom` | New plugin ID for Fabric Loom 1.16+ |
| Dependency type | `implementation` | Not `modImplementation` (unobfuscated) |
| Mappings | None | Unobfuscated Minecraft 26.1.x |
| Java target | `LanguageVersion.of(25)` | Java 25 strict enforcement |
| Jar task | `jar` | Not `remapJar` (no remapping needed) |

### Intermediary Names

Without a mappings block, code in the IDE uses Intermediary names (e.g., `class_1703`) instead of official Mojang names. The Yarn mappings dependency resolves these at compile time. This is expected behavior for unobfuscated Minecraft 26.1.x environments.

### Migration from pre-26.1

If upgrading from Minecraft 1.21.x (Yarn mappings):

| Change | Before (1.21.x) | After (26.1.x) |
| --- | --- | --- |
| Loom plugin | `fabric-loom` | `net.fabricmc.fabric-loom` |
| Mappings | `net.fabricmc:yarn:...` | **Removed** (unobfuscated) |
| Dependencies | `modImplementation` | `implementation` |
| Build task | `remapJar` | `jar` |
| MC version | `1.21.7` | `26.1.2` |
| Fabric API | `0.127.0+1.21.7` | `0.149.1+26.1.2` |
| Java | 25 | 25 |

---

## Critical Rules for Contributors

1. **NEVER use legacy formatting characters** — causes client disconnects and thread crashes. Use `Component.literal().withStyle()` exclusively.
2. **NEVER use `BEGIN IMMEDIATE` for concurrency** — SQLite does NOT support row-level locking. Use single-threaded Executor Queues.
3. **NEVER use third-party GUI libraries** — write native `ScreenHandler` extensions only.
4. **ALWAYS call `sendContentUpdates()`** after canceling packets in Mixins to prevent ghost items.
5. **Use `ComponentSerialization.CODEC`** for text component parsing from JSON — not custom GSON parsers.
6. **NEVER use `modImplementation`** for non-Fabric dependencies — use standard `implementation`.
7. **Java 25 strict enforcement** — the project targets `LanguageVersion.of(25)`.
8. **NEVER call `.join()` on CompletableFuture** — always use `.thenAccept()` + `server.execute()` to avoid blocking the server tick thread.
9. **NEVER use `MinecraftServer.getServer()`** — it does not exist in Fabric. Inject the server instance via `ServerLifecycleEvents.SERVER_STARTED`.
10. **ALWAYS rollback cache on persistence failure** — if a SQLite write fails, restore the in-memory cache to its previous value to maintain consistency.
