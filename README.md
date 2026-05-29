# Solidus

[![Platform](https://img.shields.io/badge/Platform-Fabric-blue.svg)](https://fabricmc.net/)
[![Minecraft Version](https://img.shields.io/badge/Minecraft-26.1.x-green.svg)](https://www.minecraft.net/)

**Server-side economy, shop, and auction system for Minecraft Fabric**

Stable economies · Vanilla compatibility · No client installation · Minecraft 26.1.x Ready

---

## Why Solidus?

Solidus is a complete **server-side economy and commerce engine** for Minecraft Fabric.

Create long-term survival economies without requiring client mods, resource packs, custom assets, or plugin stacks.

### Highlights

* Fully server-side architecture — works with any vanilla client
* Built-in virtual economy with async persistence
* GUI-based server shop (11 categories, 120+ items)
* Player-driven auction house
* Hot-reload configuration — change prices without restart
* Inter-mod API for third-party integration
* Crash-resilient data storage

---

## Features

### Economy

A lightweight virtual economy designed for multiplayer survival servers.

* Configurable starting balance
* Secure player transfers (`/pay`) — online and offline
* Global wealth leaderboard (`/baltop`)
* Full transaction history (`/transactions`)
* Offline notifications on login

### Server Shop (`/shop`)

Virtual shop interface powered entirely by the server.

* 11 categories with 120+ configured items
* Stack trading support
* Item search (`/shop search <query>`)
* Hot-reload configuration
* Display-only GUI protection (no item movement exploits)

### Auction House (`/ah`)

Marketplace for player-to-player trading.

* Item listing directly from inventory (`/ah sell <price>`)
* Listing expiration with automatic item return
* Reclaim expired items (`/ah collect`)
* Cancel own listings (`/ah cancel <uuid>`)
* Sort listings by price, newest, or material (`/ah sort`)
* Listing fee support
* Offline seller notifications

### Economy Protection

Solidus applies sell-price reductions to farmed resources, configured directly in `shop.json` by the server operator. This gives you full control over economic balance — adjust prices to counter inflation from automated farms without needing to restart the server.

---

## Screenshots

> Screenshots and GIF previews coming soon.

---

## Download

| Platform        | Link        |
| --------------- | ----------- |
| GitHub Releases | Releases    |
| Modrinth        | Coming soon |

---

## Installation

> **Requirements:** Minecraft 26.1.x · Java 25 · Fabric Loader · Fabric API

1. Install [Fabric Loader](https://fabricmc.net/use/)
2. Install [Fabric API](https://modrinth.com/mod/fabric-api) on the server
3. Download the latest Solidus release
4. Place both `.jar` files into `mods/`
5. Start the server
6. Configure `config/solidus/shop.json`

No client installation required.

---

## Commands

| Command | Description |
| --- | --- |
| `/balance` | Show balance |
| `/pay <player> <amount>` | Transfer to online player |
| `/pay offline <name> <amount>` | Transfer to offline player |
| `/baltop` | Wealth leaderboard |
| `/shop` | Open shop |
| `/shop search <query>` | Search shop items |
| `/ah` | Open auction |
| `/ah sell <price>` | Create listing |
| `/ah collect` | Reclaim expired items |
| `/ah cancel <uuid>` | Cancel own listing |
| `/ah sort <order>` | Sort listings |
| `/transactions [page]` | Transaction history |

---

## Configuration

Solidus generates configuration automatically on first run.

**Location:** `config/solidus/shop.json`

**Example:**

```json
{
  "startingBalance": 500,
  "currency": "S$",
  "listingFee": 2
}
```

Supports:

* Categories and per-item pricing
* Buy and sell prices per material
* Text formatting
* Hot reload without restart

---

## Compatibility

| Component | Requirement |
| --- | --- |
| Minecraft | 26.1.x |
| Loader | Fabric |
| Fabric API | Required |
| Java | 25 |
| Client | Any (vanilla or modded) |
| Database | SQLite (bundled) |
| Side | Server only |

---

## FAQ

### Does this require client mods?

No. Players join using standard Minecraft clients.

### Works with proxy networks?

Yes. Solidus runs on backend servers.

### Supports offline mode?

Yes, but online-mode servers are recommended.

### Can prices be changed live?

Yes. Configuration supports hot reload — edit `shop.json` and reload without restart.

### Does Solidus integrate with other mods?

Yes. Solidus provides a stable public API (`SolidusAPI`) for other Fabric mods. Integration works via reflection with zero compile-time dependency. See [DEVELOPMENT.md](DEVELOPMENT.md) for details.

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

See [DEVELOPMENT.md](DEVELOPMENT.md) for technical details and contribution guidelines.

---

## License

**Solidus Community & Commercial License (SCCL) v1.0**

| Usage | Status |
| --- | --- |
| Private servers | Allowed |
| Study & modify | Allowed |
| Open redistribution | Allowed |
| Commercial use | License required |

See `LICENSE`.

---

Built with ☕ by MOHD_Gs
