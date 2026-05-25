# Solidus






\

### Server-side economy, shop, and auction system for Minecraft Fabric

Stable economies · Vanilla compatibility · No client installation

---

## Why Solidus?

Solidus is a complete **server-side economy and commerce engine** for Minecraft Fabric.

Create long-term survival economies without requiring client mods, resource packs, custom assets, or plugin stacks.

### Highlights

* Fully server-side architecture
* Works with vanilla and modded clients
* Built-in virtual economy
* GUI-based server shop
* Player-driven auction house
* Anti-inflation economy balancing
* Crash-resilient persistence
* Minimal operational overhead

---

## Features

### Economy

A lightweight virtual economy designed for multiplayer survival servers.

Features include:

* Memory-backed balance reads
* Async SQLite persistence
* Configurable starting balance
* Secure player transfers (`/pay`)
* Global wealth leaderboard (`/baltop`)
* Consistency-focused execution model

---

### Server Shop (`/shop`)

Virtual shop interface powered entirely by the server.

Includes:

* 11 categories
* 120+ configured items
* Stack trading support
* Hot-reload configuration
* Display-only GUI protection

---

### Auction House (`/ah`)

Marketplace for player-to-player trading.

Capabilities:

* Item listing directly from inventory
* Listing expiration
* Listing fee support
* Purchase protection
* Progression-focused balancing

---

### Economy Protection

Solidus includes balancing mechanisms to reduce the economic impact of automated farms.

Examples:

| Resource             | Reduction             |
| -------------------- | --------------------- |
| Emerald              | 70%                   |
| Gold                 | 50%                   |
| Iron                 | 30%                   |
| Trial rewards        | 50–70%                |
| Additional materials | Configured internally |

---

## Screenshots

> Screenshots and GIF previews coming soon.

Suggested media:

* Shop GUI
* Auction interface
* Leaderboards
* Configuration examples

---

## Download

| Platform        | Link        |
| --------------- | ----------- |
| GitHub Releases | Releases    |
| Modrinth        | Coming soon |

---

## Installation

> Requirements: Minecraft 26.1.x · Java 25 · Fabric Loader

1. Install Fabric Loader
2. Download the latest Solidus release
3. Place the `.jar` into `mods/`
4. Start the server
5. Configure `config/solidus/shop.json`

No client installation required.

---

## Commands

| Command    | Description        |
| ---------- | ------------------ |
| `/balance` | Show balance       |
| `/pay`     | Transfer currency  |
| `/baltop`  | Wealth leaderboard |
| `/shop`    | Open shop          |
| `/ah`      | Open auction       |
| `/ah sell` | Create listing     |

---

## Configuration

Solidus generates configuration automatically.

Location:

```text
config/solidus/shop.json
```

Example:

```json
{
  "startingBalance":500,
  "currency":"S$",
  "listingFee":2
}
```

Supports:

* Categories
* Prices
* Text formatting
* Reload without restart

---

## Compatibility

| Component | Requirement |
| --------- | ----------- |
| Minecraft | 26.1.x      |
| Loader    | Fabric      |
| Java      | 25          |
| Client    | Any         |
| Database  | SQLite      |
| Side      | Server      |

---

## FAQ

### Does this require client mods?

No.

Players join using standard Minecraft clients.

---

### Works with proxy networks?

Yes.

Solidus runs on backend servers.

---

### Supports offline mode?

Yes, but online-mode servers are recommended.

---

### Can prices be changed live?

Yes.

Configuration supports hot reload.

---

### Does Solidus integrate with economy plugins?

No.

Solidus is intentionally standalone.

---

## Developer Notes

### Architecture

Solidus separates:

* Economy
* Shop
* Auction
* Persistence
* Networking

### Storage

* SQLite
* WAL mode
* Async persistence
* Memory cache

### Concurrency

Operations are serialized internally to reduce contention and preserve consistency.

### Text System

Uses Minecraft component serialization.

More details:

```text
docs/ARCHITECTURE.md
```

---

## Building

```bash
git clone <repository>

cd solidus

./gradlew build
```

Output:

```text
build/libs/
```

---

## Roadmap

* [ ] Transaction taxes
* [ ] Multi-currency
* [ ] REST API
* [ ] Redis backend
* [ ] Metrics & analytics
* [ ] Backup & restore

---

## Contributing

Contributions are welcome.

* Report issues
* Suggest features
* Submit pull requests

---

## License

Licensed under:

**Solidus Community & Commercial License (SCCL) v1.0**

| Usage               | Status           |
| ------------------- | ---------------- |
| Private servers     | Allowed          |
| Study & modify      | Allowed          |
| Open redistribution | Allowed          |
| Commercial use      | License required |

See `LICENSE`.

---

Built with ☕ by MOHD_Gs
