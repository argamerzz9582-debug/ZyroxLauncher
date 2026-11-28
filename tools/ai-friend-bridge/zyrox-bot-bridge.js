#!/usr/bin/env node
'use strict';

const net = require('node:net');
const {
  PROTOCOL_VERSION,
  normalizeAction,
  sanitizeChatMessage,
  verifyBridgeToken,
  attachNdjsonReader,
  sendNdjsonFrame,
} = require('./protocol');
const { openLocalZyroxTunnel } = require('./zyrox-tunnel-adapter');
const { BotActionExecutor } = require('./actions');

/**
 * Zyrox AI Friend — Minecraft Companion Bot Bridge Worker (`zyrox-bot-bridge.js`).
 *
 * Listens on TCP (default `0.0.0.0:25580`) for NDJSON control frames from `ZyroxLauncher`
 * (`CompanionBotBridgeClient.java`) and manages an in-world Mineflayer player entity across:
 *   1. Singleplayer Open-to-LAN Worlds
 *   2. Zyrox Self-Hosted `ZYX-XXXX` Rooms (via `zyrox-tunnel-adapter.js`)
 *   3. Saved Offline (`online-mode=false`) and Online (`online-mode=true` via Microsoft device code) Servers
 */
function createBridgeServer(options = {}) {
  const bindHost = options.bindHost || process.env.ZYROX_BRIDGE_BIND || '0.0.0.0';
  const bindPort = Number.parseInt(options.bindPort ?? process.env.ZYROX_BRIDGE_PORT ?? '25580', 10);
  const expectedToken = options.bridgeToken ?? process.env.ZYROX_BRIDGE_TOKEN ?? '';
  const botFactory = options.botFactory || createMineflayerBotSession;

  const connectedClients = new Set();
  let activeSession = null;

  function broadcastEvent(frame) {
    for (const client of connectedClients) {
      if (client.authenticated) {
        sendNdjsonFrame(client.socket, frame);
      }
    }
  }

  function currentBotStateFrame(detailOverride) {
    if (!activeSession) {
      return {
        type: 'bot_state',
        state: 'DISCONNECTED',
        botUsername: 'ZyroxBuddy',
        endpoint: '',
        activeAction: 'IDLE',
        health: 20,
        food: 20,
        detail: detailOverride || 'No active bot in world',
      };
    }
    return activeSession.getSnapshotFrame(detailOverride);
  }

  async function shutdownActiveBot(reason = 'dismissed') {
    if (!activeSession) return;
    const prev = activeSession;
    activeSession = null;
    await prev.close(reason);
    broadcastEvent(currentBotStateFrame(`Bot disconnected (${reason})`));
  }

  async function handleSummonCommand(cmd) {
    await shutdownActiveBot('replacing_session');

    const botUsername = String(cmd.botUsername || 'ZyroxBuddy').replace(/[^A-Za-z0-9_]/g, '').slice(0, 16) || 'ZyroxBuddy';
    const authMode = cmd.authMode === 'microsoft' ? 'microsoft' : 'offline';
    const ownerUsername = String(cmd.ownerUsername || 'Player').trim();
    const defaultAction = normalizeAction(cmd.defaultAction || 'FOLLOW', 'FOLLOW');
    const mcVersion = cmd.mcVersion && cmd.mcVersion !== 'auto' ? String(cmd.mcVersion) : false;

    let tunnel = null;
    let connectHost = String(cmd.host || '127.0.0.1').trim();
    let connectPort = Number.parseInt(cmd.port || 25565, 10);
    let displayEndpoint = `${connectHost}:${connectPort}`;

    // If a Zyrox Self-Hosted room code is supplied without a local loopback port, open a native ZYX tunnel
    if (cmd.zyroxRoomCode && (!connectHost || connectHost === '' || connectPort <= 0 || cmd.targetMode === 'ZYROX_SELF_HOSTED')) {
      if (connectHost !== '127.0.0.1' || connectPort <= 0) {
        tunnel = await openLocalZyroxTunnel(String(cmd.zyroxRoomCode));
        connectHost = tunnel.localHost;
        connectPort = tunnel.localPort;
        displayEndpoint = `${tunnel.shortCode} (${tunnel.remoteHost}:${tunnel.remotePort})`;
      }
    }

    broadcastEvent({
      type: 'bot_state',
      state: 'CONNECTING',
      botUsername,
      endpoint: displayEndpoint,
      activeAction: defaultAction,
      health: 20,
      food: 20,
      detail: `Joining ${displayEndpoint} (${authMode} auth)...`,
    });

    try {
      activeSession = await botFactory({
        host: connectHost,
        port: connectPort,
        displayEndpoint,
        botUsername,
        authMode,
        mcVersion,
        ownerUsername,
        defaultAction,
        tunnel,
        emitEvent: broadcastEvent,
        onDisconnected: (why) => {
          if (activeSession && activeSession.tunnel) {
            activeSession.tunnel.close();
          }
          activeSession = null;
          broadcastEvent(currentBotStateFrame(why || 'Disconnected from server'));
        },
      });
    } catch (err) {
      if (tunnel) tunnel.close();
      activeSession = null;
      broadcastEvent({
        type: 'error',
        message: err.message || 'Failed creating Mineflayer session',
      });
    }
  }

  const server = net.createServer((socket) => {
    socket.setNoDelay(true);
    const clientState = {
      socket,
      authenticated: !expectedToken,
    };
    connectedClients.add(clientState);

    socket.on('close', () => connectedClients.delete(clientState));
    socket.on('error', () => connectedClients.delete(clientState));

    attachNdjsonReader(
      socket,
      async (frame) => {
        const type = frame.type;

        if (type === 'hello') {
          if (!verifyBridgeToken(expectedToken, frame.token)) {
            sendNdjsonFrame(socket, {
              type: 'error',
              message: 'Invalid bridge authentication token (check ZYROX_BRIDGE_TOKEN)',
            });
            socket.end();
            return;
          }
          clientState.authenticated = true;
          sendNdjsonFrame(socket, {
            type: 'hello_ack',
            version: PROTOCOL_VERSION,
            bridge: 'zyrox-mineflayer-bridge',
            connected: Boolean(activeSession && activeSession.isSpawned()),
          });
          sendNdjsonFrame(socket, currentBotStateFrame());
          return;
        }

        if (!clientState.authenticated) {
          sendNdjsonFrame(socket, {
            type: 'error',
            message: 'Send hello frame with valid token before issuing commands',
          });
          return;
        }

        if (type === 'ping') {
          sendNdjsonFrame(socket, { type: 'pong', ts: frame.ts || Date.now() });
          return;
        }

        if (type === 'status') {
          sendNdjsonFrame(socket, currentBotStateFrame());
          return;
        }

        if (type === 'summon') {
          await handleSummonCommand(frame);
          return;
        }

        if (type === 'dismiss') {
          await shutdownActiveBot(frame.reason || 'user_requested');
          return;
        }

        if (type === 'execute') {
          if (!activeSession) {
            sendNdjsonFrame(socket, { type: 'error', message: 'No bot connected to execute turn' });
            return;
          }
          const cleanSay = sanitizeChatMessage(frame.say || '');
          if (cleanSay) {
            activeSession.say(cleanSay);
          }
          if (frame.action) {
            activeSession.executeAction(frame.action, frame.ownerUsername);
          }
          broadcastEvent(activeSession.getSnapshotFrame());
          return;
        }

        if (type === 'action') {
          if (!activeSession) {
            sendNdjsonFrame(socket, { type: 'error', message: 'No bot connected for action' });
            return;
          }
          activeSession.executeAction(frame.action, frame.ownerUsername);
          broadcastEvent(activeSession.getSnapshotFrame());
          return;
        }

        if (type === 'chat') {
          if (activeSession) {
            const clean = sanitizeChatMessage(frame.message || '');
            if (clean) activeSession.say(clean);
          }
        }
      },
      (err) => {
        sendNdjsonFrame(socket, { type: 'error', message: `Protocol error: ${err.message}` });
      }
    );
  });

  return {
    server,
    bindHost,
    bindPort,
    async listen() {
      await new Promise((resolve, reject) => {
        server.once('error', reject);
        server.listen(bindPort, bindHost, resolve);
      });
      return server.address();
    },
    async close() {
      await shutdownActiveBot('bridge_shutdown');
      for (const c of connectedClients) {
        c.socket.destroy();
      }
      connectedClients.clear();
      await new Promise((resolve) => server.close(resolve));
    },
  };
}

/**
 * Default production Mineflayer bot session factory.
 */
async function createMineflayerBotSession(cfg) {
  let mineflayer;
  try {
    mineflayer = require('mineflayer');
  } catch (_) {
    throw new Error(
      'Mineflayer dependencies are not installed yet. Run `cd tools/ai-friend-bridge && npm install` first.'
    );
  }

  const { pathfinder, Movements, goals } = require('mineflayer-pathfinder');
  const collectBlock = require('mineflayer-collectblock').plugin;
  const pvp = require('mineflayer-pvp').plugin;
  const Vec3 = require('vec3');

  let spawned = false;
  const botOptions = {
    host: cfg.host,
    port: cfg.port,
    username: cfg.botUsername,
    auth: cfg.authMode === 'microsoft' ? 'microsoft' : 'offline',
    version: cfg.mcVersion || false,
    onMsaCode: (data) => {
      const uri = data.verification_uri || 'https://www.microsoft.com/link';
      const code = data.user_code || '';
      cfg.emitEvent({
        type: 'microsoft_auth_prompt',
        verificationUri: uri,
        userCode: code,
        message: `Authenticate the bot's Microsoft account at ${uri} using code ${code}`,
      });
    },
  };

  const bot = mineflayer.createBot(botOptions);
  bot.loadPlugin(pathfinder);
  bot.loadPlugin(collectBlock);
  bot.loadPlugin(pvp);

  const executor = new BotActionExecutor(bot, { goals, Movements, Vec3 }, cfg.emitEvent);
  executor.setOwner(cfg.ownerUsername);

  function buildStateFrame(detailOverride) {
    const pos = bot.entity && bot.entity.position
      ? {
          x: Math.round(bot.entity.position.x),
          y: Math.round(bot.entity.position.y),
          z: Math.round(bot.entity.position.z),
        }
      : undefined;
    return {
      type: 'bot_state',
      state: spawned ? 'CONNECTED' : 'CONNECTING',
      botUsername: bot.username || cfg.botUsername,
      endpoint: cfg.displayEndpoint,
      activeAction: executor.activeAction,
      health: typeof bot.health === 'number' ? bot.health : 20,
      food: typeof bot.food === 'number' ? bot.food : 20,
      position: pos,
      detail: detailOverride || (spawned ? `Active in ${cfg.displayEndpoint}` : 'Connecting...'),
    };
  }

  bot.once('spawn', () => {
    spawned = true;
    executor.executeAction(cfg.defaultAction, cfg.ownerUsername);
    cfg.emitEvent(buildStateFrame(`Spawned in ${cfg.displayEndpoint}`));
  });

  bot.on('health', () => {
    if (spawned) {
      cfg.emitEvent(buildStateFrame());
    }
  });

  bot.on('chat', (username, message) => {
    if (!username || username === bot.username) return;
    cfg.emitEvent({
      type: 'world_chat',
      username,
      message: String(message || ''),
      isWhisper: false,
    });
  });

  bot.on('whisper', (username, message) => {
    if (!username || username === bot.username) return;
    cfg.emitEvent({
      type: 'world_chat',
      username,
      message: String(message || ''),
      isWhisper: true,
    });
  });

  bot.on('kicked', (reason) => {
    const reasonStr = typeof reason === 'string' ? reason : JSON.stringify(reason);
    const authHint =
      cfg.authMode === 'offline' && /verify username|unauthenticated|encryption|multiplayer/i.test(reasonStr)
        ? ' (Server requires online-mode Microsoft authentication — switch Bot Auth to Microsoft Account in Zyrox AI Friend)'
        : '';
    executor.stopCurrentTasks();
    cfg.onDisconnected(`Kicked: ${reasonStr}${authHint}`);
  });

  bot.on('error', (err) => {
    cfg.emitEvent({
      type: 'error',
      message: `Minecraft connection error: ${err.message}`,
    });
  });

  bot.on('end', (reason) => {
    if (spawned) {
      spawned = false;
      executor.stopCurrentTasks();
      cfg.onDisconnected(`Disconnected (${reason || 'end'})`);
    }
  });

  return {
    tunnel: cfg.tunnel,
    isSpawned: () => spawned,
    say(text) {
      if (spawned && text) {
        bot.chat(text);
      }
    },
    executeAction(action, owner) {
      return executor.executeAction(action, owner);
    },
    getSnapshotFrame(detail) {
      return buildStateFrame(detail);
    },
    async close(reason) {
      spawned = false;
      executor.stopCurrentTasks();
      try {
        bot.quit(reason || 'Zyrox AI Friend dismissed');
      } catch (_) {
        // ignore
      }
      if (cfg.tunnel) {
        cfg.tunnel.close();
      }
    },
  };
}

if (require.main === module) {
  const bridge = createBridgeServer();
  bridge
    .listen()
    .then((addr) => {
      process.stdout.write(
        `[Zyrox AI Friend Bridge] Listening on ${addr.address}:${addr.port} (NDJSON v${PROTOCOL_VERSION})\n`
      );
    })
    .catch((err) => {
      process.stderr.write(`[Zyrox AI Friend Bridge] Startup failed: ${err.message}\n`);
      process.exit(1);
    });

  const shutdown = () => {
    bridge.close().finally(() => process.exit(0));
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

module.exports = {
  createBridgeServer,
};
