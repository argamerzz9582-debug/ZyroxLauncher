'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const net = require('node:net');

const {
  normalizeAction,
  sanitizeChatMessage,
  verifyBridgeToken,
  attachNdjsonReader,
  sendNdjsonFrame,
} = require('../protocol');
const {
  parseRoomCode,
  encodeDirectRoomCode,
  openLocalZyroxTunnel,
} = require('../zyrox-tunnel-adapter');
const { createBridgeServer } = require('../zyrox-bot-bridge');

test('protocol.normalizeAction maps exact and alias actions safely', () => {
  assert.equal(normalizeAction('FOLLOW'), 'FOLLOW');
  assert.equal(normalizeAction('chop-wood'), 'GATHER_WOOD');
  assert.equal(normalizeAction('mine_diamond'), 'MINE_ORES');
  assert.equal(normalizeAction('place-torch'), 'PLACE_TORCHES');
  assert.equal(normalizeAction('protect_player'), 'GUARD');
  assert.equal(normalizeAction('come_to_me'), 'COME_HERE');
  assert.equal(normalizeAction('stop'), 'IDLE');
  assert.equal(normalizeAction('unknown_xyz', 'FOLLOW'), 'FOLLOW');
});

test('protocol.sanitizeChatMessage strips control chars, section codes, and leading slash commands', () => {
  assert.equal(sanitizeChatMessage('/op EvilUser'), 'op EvilUser');
  assert.equal(sanitizeChatMessage('§aHello\nWorld\t!'), 'aHello World !');
  const longText = 'x'.repeat(300);
  const capped = sanitizeChatMessage(longText);
  assert.ok(capped.length <= 220);
  assert.ok(capped.endsWith('...'));
});

test('zyrox-tunnel-adapter encodes, validates CRC-8, and tunnels ZYX-XXXX room traffic', async () => {
  const encoded = encodeDirectRoomCode('ZYX-7K9M', '127.0.0.1', 39123);
  const parsed = parseRoomCode(encoded);
  assert.equal(parsed.shortCode, 'ZYX-7K9M');
  assert.equal(parsed.directHost, '127.0.0.1');
  assert.equal(parsed.directPort, 39123);

  // Tampering with checksum character must fail validation
  const tampered = encoded.slice(0, -1) + (encoded.endsWith('A') ? 'B' : 'A');
  assert.throws(() => parseRoomCode(tampered));

  // Spin up a mock ZyroxRoomBridge host speaking ZYROX_TUN_V1:<shortCode>\n -> ZYROX_OK\n
  const mockHost = net.createServer((sock) => {
    let buf = '';
    sock.on('data', (chunk) => {
      buf += chunk.toString('utf8');
      if (buf.includes('ZYROX_TUN_V1:ZYX-7K9M\n')) {
        sock.write('ZYROX_OK\nMC_HANDSHAKE_OK');
      }
    });
  });
  await new Promise((r) => mockHost.listen(0, '127.0.0.1', r));
  const hostPort = mockHost.address().port;

  const liveCode = encodeDirectRoomCode('ZYX-7K9M', '127.0.0.1', hostPort);
  const tunnel = await openLocalZyroxTunnel(liveCode);
  try {
    const payload = await new Promise((resolve, reject) => {
      const client = net.connect({ host: tunnel.localHost, port: tunnel.localPort });
      client.once('data', (d) => {
        client.end();
        resolve(d.toString('utf8'));
      });
      client.once('error', reject);
    });
    assert.equal(payload, 'MC_HANDSHAKE_OK');
  } finally {
    tunnel.close();
    await new Promise((r) => mockHost.close(r));
  }
});

test('zyrox-bot-bridge authenticates token, summons mock bot, executes {say, action}, and dismisses', async () => {
  const spoken = [];
  const actions = [];

  const bridge = createBridgeServer({
    bindHost: '127.0.0.1',
    bindPort: 0,
    bridgeToken: 'secret-test-token',
    botFactory: async (cfg) => {
      let activeAction = cfg.defaultAction;
      setImmediate(() => {
        cfg.emitEvent({
          type: 'bot_state',
          state: 'CONNECTED',
          botUsername: cfg.botUsername,
          endpoint: cfg.displayEndpoint,
          activeAction,
          health: 20,
          food: 20,
          position: { x: 10, y: 64, z: -5 },
          detail: 'Mock bot spawned',
        });
      });
      return {
        isSpawned: () => true,
        say(msg) {
          spoken.push(msg);
        },
        executeAction(act) {
          activeAction = normalizeAction(act);
          actions.push(activeAction);
          return activeAction;
        },
        getSnapshotFrame() {
          return {
            type: 'bot_state',
            state: 'CONNECTED',
            botUsername: cfg.botUsername,
            endpoint: cfg.displayEndpoint,
            activeAction,
            health: 20,
            food: 20,
            detail: 'Active',
          };
        },
        async close() {},
      };
    },
  });

  assert.equal(verifyBridgeToken('abc', 'abc'), true);
  assert.equal(verifyBridgeToken('abc', 'wrong'), false);

  const addr = await bridge.listen();
  try {
    const received = [];
    const client = net.connect({ host: '127.0.0.1', port: addr.port });
    await new Promise((r) => client.once('connect', r));

    attachNdjsonReader(client, (frame) => received.push(frame));

    sendNdjsonFrame(client, { type: 'hello', version: 1, token: 'secret-test-token' });
    sendNdjsonFrame(client, {
      type: 'summon',
      targetMode: 'LAN_WORLD',
      host: '127.0.0.1',
      port: 54321,
      botUsername: 'ZyroxBuddy',
      authMode: 'offline',
      defaultAction: 'FOLLOW',
      ownerUsername: 'Alex',
    });

    await new Promise((r) => setTimeout(r, 80));

    sendNdjsonFrame(client, {
      type: 'execute',
      say: 'Mining iron vein right now!',
      action: 'MINE_ORES',
      ownerUsername: 'Alex',
    });

    await new Promise((r) => setTimeout(r, 60));

    assert.equal(spoken[0], 'Mining iron vein right now!');
    assert.equal(actions[0], 'MINE_ORES');
    assert.ok(received.some((f) => f.type === 'hello_ack'));
    assert.ok(received.some((f) => f.type === 'bot_state' && f.state === 'CONNECTED' && f.activeAction === 'MINE_ORES'));

    sendNdjsonFrame(client, { type: 'dismiss' });
    await new Promise((r) => setTimeout(r, 50));
    assert.ok(received.some((f) => f.type === 'bot_state' && f.state === 'DISCONNECTED'));
    client.destroy();
  } finally {
    await bridge.close();
  }
});
