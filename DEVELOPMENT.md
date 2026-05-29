# Solidus — Developer Guide

Technical reference for developers building, contributing to, or integrating with Solidus.

For user-facing documentation, see [README.md](README.md). For full architecture details, see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

---

## Architecture Overview

Solidus separates concerns into six layers:

| Layer | Components |
| --- | --- |
| **Public API** | `SolidusAPI`, `SolidusIntegration` |
| **Commands** | `BalanceCommand`, `PayCommand`, `BaltopCommand`, `ShopCommand`, `AuctionCommand`, `TransactionsCommand` |
| **Business Logic** | `EconomyEngine`, `BalanceManager`, `ShopManager`, `AuctionManager`, `TransactionLog` |
| **Presentation** | `ShopGUI`, `ShopScreenHandler`, `AuctionGUI`, `AuctionScreenHandler`, `ShopDummyContainer`, `AuctionDummyContainer` |
| **Infrastructure** | `SQLiteStorage`, `PacketHandler`, `RateLimiter`, `ConfigManager`, `TextUtil`, `CurrencyUtil` |
| **Mixins** | `ServerPlayerEntityMixin`, `ScreenHandlerMixin` |

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
│   │   └── AuctionDummyContainer.java   # Display-only container
│   ├── networking/
│   │   ├── PacketHandler.java           # Packet interception
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
└── docs/
    └── ARCHITECTURE.md                  # Full architecture reference
```

---

## Core Subsystems

### Economy Engine

The `EconomyEngine` is the central coordinator. It owns:

- **BalanceManager** — High-level API for balance reads, writes, and transfers
- **SQLiteStorage** — Async persistence layer with in-memory cache

All balance mutations flow through a **Single-Threaded Executor Queue**:

```
Player Action → Command → BalanceManager → ExecutorService.submit()
                                                ↓
                                         Sequential Processing
                                                ↓
                                    1. Validate operation
                                    2. Update in-memory cache
                                    3. Async SQLite write
                                    4. Return CompletableFuture
```

### SQLite Storage

- **WAL mode** — Write-Ahead Logging allows concurrent reads while writing
- **In-memory cache** — `ConcurrentHashMap<UUID, Double>` for balance reads
- **Async writes** — Cache is updated immediately, disk persistence happens asynchronously
- **No `BEGIN IMMEDIATE`** — Executor Queue eliminates the need for database-level locking

### Shop System

- **ShopManager** — Loads `shop.json` using `ComponentSerialization.CODEC`, supports hot-reload
- **ShopGUI** — Builds the 6-row chest inventory layout with category navigation
- **ShopScreenHandler** — Extends `ScreenHandler` (GENERIC_9x6), manages buy/sell logic
- **ShopDummyContainer** — `SimpleContainer` that rejects all item modifications (display-only)

### Auction System

- **AuctionManager** — Manages listings with its own Executor Queue for serialized transactions
- **AuctionEntry** — Data model: seller UUID, item, price, timestamp, status
- **AuctionGUI** — Paginated auction browser
- **AuctionScreenHandler** — Handles purchase flow with race-condition protection

### Packet Interception

- **PacketHandler** — Intercepts `ServerboundContainerClickPacket` to block item movement in virtual GUIs
- **RateLimiter** — 150ms cooldown per player, prevents cheat-client spam
- **Ghost Item Fix** — After canceling a packet, calls `sendContentUpdates()` to force client resync

### Mixin Layer

- **ServerPlayerEntityMixin** — Intercepts container clicks at the player level
- **ScreenHandlerMixin** — Blocks quick-move (shift-click) operations in virtual containers

Both mixins call `sendContentUpdates()` after every cancellation to prevent ghost items.

---

## Inter-Mod API

Solidus provides a **stable public API** in `com.solidus.api.SolidusAPI` for other mods to integrate with the economy system. This is the **only** class external mods should depend on — internal classes may change between versions without notice.

### API Methods

| Method | Return Type | Description |
| --- | --- | --- |
| `getInstance()` | `SolidusAPI` | Get API singleton (null if not loaded) |
| `isAvailable()` | `boolean` | Check if Solidus is ready |
| `getBalance(ServerPlayer)` | `CompletableFuture<Double>` | Get online player's balance |
| `getBalanceOffline(UUID, String)` | `CompletableFuture<Double>` | Get offline player's balance |
| `addBalance(ServerPlayer, double)` | `CompletableFuture<Double>` | Add to online player |
| `addBalanceOffline(UUID, String, double)` | `CompletableFuture<Double>` | Add to offline player |
| `subtractBalance(ServerPlayer, double)` | `CompletableFuture<Double>` | Subtract from online player |
| `subtractBalanceOffline(UUID, String, double)` | `CompletableFuture<Double>` | Subtract from offline player |
| `hasSufficientBalance(ServerPlayer, double)` | `CompletableFuture<Boolean>` | Check affordability |
| `transfer(ServerPlayer, ServerPlayer, double)` | `CompletableFuture<TransferResult>` | Atomic P2P transfer |
| `transferOffline(UUID, String, UUID, String, double)` | `CompletableFuture<TransferResult>` | Atomic offline transfer |
| `getTopBalances(int)` | `CompletableFuture<List<BalanceEntry>>` | Leaderboard |
| `getTransactionLog()` | `TransactionLog` | For logging custom events |

### Integration Pattern (No Compile Dependency)

External mods should use **reflection** to avoid a compile-time dependency on Solidus:

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

### CombatKeepMod Integration Example

`SolidusIntegration.java` provides a reference implementation. On combat death, it deducts a percentage of the victim's balance and gives it to the killer:

```java
// In CombatKeepMod's death callback:
SolidusIntegration.applyDeathPenalty(victim, killer, 0.15);
```

The integration uses `DEATH_PENALTY` and `DEATH_REWARD` transaction types, which appear in the player's `/transactions` history.

### Transaction Types

| Type | Description |
| --- | --- |
| `DEATH_PENALTY` | Player lost currency from being killed |
| `DEATH_REWARD` | Player gained currency from killing another player |
| `SHOP_BUY` | Player purchased from the server shop |
| `SHOP_SELL` | Player sold to the server shop |
| `AUCTION_SOLD` | Player's auction item was purchased |
| `AUCTION_BOUGHT` | Player purchased from the auction house |
| `PAY_SEND` | Player sent money to another player |
| `PAY_RECEIVE` | Player received money from another player |

External mods can log custom transactions via `getTransactionLog().log(...)` so they appear in the player's `/transactions` history.

---

## Building

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
2. **NEVER use `BEGIN IMMEDIATE` for concurrency** — SQLite does NOT support row-level locking. Use Executor Queues.
3. **NEVER use third-party GUI libraries** — write native `ScreenHandler` extensions only.
4. **ALWAYS call `sendContentUpdates()`** after canceling packets in Mixins to prevent ghost items.
5. **Use `ComponentSerialization.CODEC`** for text component parsing from JSON — not custom GSON parsers.
6. **NEVER use `modImplementation`** for non-Fabric dependencies — use standard `implementation`.
7. **Java 25 strict enforcement** — the project targets `LanguageVersion.of(25)`.

---

## Text System

All player-facing text uses Minecraft's component serialization system. The `TextUtil` class provides a centralized factory for building styled components.

Key principles:

- **No legacy formatting codes** — `§` characters are forbidden and will crash the client
- **Use `Component.literal().withStyle()`** — the only approved method for styled text
- **Use `ComponentSerialization.CODEC`** — for parsing JSON text components from configuration

---

## Storage

| Aspect | Implementation |
| --- | --- |
| Database | SQLite (bundled, no external setup) |
| Journal mode | WAL (Write-Ahead Logging) |
| Read path | In-memory `ConcurrentHashMap` cache |
| Write path | Async via `ExecutorService` queue |
| Consistency | Sequential execution, no database-level locking needed |

---

## Contributing

Contributions are welcome. Please follow these steps:

1. **Fork** the repository
2. **Create a feature branch** from `main`
3. **Follow the critical rules** listed above
4. **Test thoroughly** on a dedicated server before submitting
5. **Submit a pull request** with a clear description of changes

### Areas of Interest

* Transaction tax system
* Multi-currency support
* REST API for external tools
* Redis backend for scaled deployments
* Metrics and analytics integration
* Backup and restore utilities

---

## Additional Resources

| Resource | Description |
| --- | --- |
| [README.md](README.md) | User-facing documentation |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Full architecture reference and design decisions |
