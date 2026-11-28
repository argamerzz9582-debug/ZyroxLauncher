# Zyrox AI Friend — Companion Bot Bridge (`tools/ai-friend-bridge`)

Because the Minecraft Java Edition client launched by `ZyroxLauncher` is the **local player's own game process**, spawning a second physical in-world companion entity alongside the player requires a lightweight companion bot client speaking the Minecraft Java protocol.

`zyrox-bot-bridge.js` is the official companion bot worker for **ZyroxLauncher AI Friend**. It runs either **locally on the same Android phone via Termux** (`127.0.0.1:25580`) or on **any PC / Raspberry Pi / home server on your Wi-Fi** (`<LAN-IP>:25580`).

## Supported World & Multiplayer Modes
1. **Singleplayer Open-to-LAN Worlds**: Connects directly to the player's Open-to-LAN IP & port (auto-detected by ZyroxLauncher from live game logs or Wi-Fi LAN scan).
2. **Zyrox Self-Hosted Worlds / Rooms (`ZYX-XXXX`)**: Includes `zyrox-tunnel-adapter.js`, a native Node.js implementation of `ZyroxRoomBridge` (`ZYROX_DISC_V1` UDP discovery + Base32/CRC-8 room token decoding + `ZYROX_TUN_V1` TCP tunnel handshake).
3. **Saved / Custom Servers (`ServerListManager`)**:
   - **Offline / Cracked SMPs (`online-mode=false`)**: Uses `Offline / LAN Mode` with any custom bot username (e.g. `ZyroxBuddy`).
   - **Online-Mode Servers (`online-mode=true`)**: Uses `Microsoft Account` device-code authentication (`prismarine-auth`) and streams the `microsoft.com/link` code directly to ZyroxLauncher's AI Friend UI. *(Note: Use a separate licensed Minecraft account for the bot, and never run bots on public anti-cheat minigame networks.)*

## Quick Start (Termux on Android or Desktop Node.js 18+)

```bash
cd tools/ai-friend-bridge
npm install
npm start
```

Optional environment variables:
- `ZYROX_BRIDGE_PORT=25580` — TCP control port (default `25580`)
- `ZYROX_BRIDGE_BIND=0.0.0.0` — Bind `127.0.0.1` for local-only Termux use or `0.0.0.0` for LAN access
- `ZYROX_BRIDGE_TOKEN=<secret>` — Shared authentication token matching **Bridge Auth Token** in Zyrox AI Friend Studio

## Wire Protocol (NDJSON-over-TCP v1)
Each frame is a single UTF-8 JSON object terminated by `\n` (`<= 16 KB`):
- **Launcher -> Bridge**: `hello`, `ping`, `status`, `summon`, `execute` (`{say, action, ownerUsername}`), `action`, `chat`, `dismiss`
- **Bridge -> Launcher**: `hello_ack`, `pong`, `bot_state`, `microsoft_auth_prompt`, `world_chat`, `action_update`, `error`
