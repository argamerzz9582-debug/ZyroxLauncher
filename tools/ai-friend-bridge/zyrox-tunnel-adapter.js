'use strict';

const dgram = require('node:dgram');
const net = require('node:net');

/**
 * Native Node.js client implementation of `ZyroxRoomBridge` (`ZYX-XXXX` / `ZYX-XXXX-XXXXXXXXXXXX`)
 * so the Mineflayer Companion Bot Bridge can join Zyrox Self-Hosted Worlds/Rooms directly from
 * either the same Android device (Termux) or another machine on Wi-Fi / Hotspot / Mesh VPN.
 */

const ROOM_PREFIX = 'ZYX-';
const DISCOVERY_UDP_PORT = 44456;
const DISCOVER_MAGIC = 'ZYROX_DISC_V1:';
const OFFER_MAGIC = 'ZYROX_OFFER_V1:';
const TUNNEL_MAGIC = 'ZYROX_TUN_V1:';
const TUNNEL_OK = 'ZYROX_OK';
const ALPHABET = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';

function stepCrc8(crc, byteVal) {
  let c = (crc ^ (byteVal & 0xff)) & 0xff;
  for (let bit = 0; bit < 8; bit += 1) {
    c = (c & 0x80) !== 0 ? ((c << 1) ^ 0x07) & 0xff : (c << 1) & 0xff;
  }
  return c;
}

function computeCrc8(shortCode, payloadBytes, len) {
  let crc = 0x5a;
  for (let i = 0; i < shortCode.length; i += 1) {
    crc = stepCrc8(crc, shortCode.charCodeAt(i));
  }
  for (let i = 0; i < len; i += 1) {
    crc = stepCrc8(crc, payloadBytes[i]);
  }
  return crc & 0xff;
}

function base32Encode(bytes) {
  let out = '';
  let buffer = 0;
  let bitsLeft = 0;
  for (let i = 0; i < bytes.length; i += 1) {
    buffer = (buffer << 8) | (bytes[i] & 0xff);
    bitsLeft += 8;
    while (bitsLeft >= 5) {
      bitsLeft -= 5;
      out += ALPHABET[(buffer >>> bitsLeft) & 0x1f];
    }
  }
  if (bitsLeft > 0) {
    out += ALPHABET[(buffer << (5 - bitsLeft)) & 0x1f];
  }
  return out;
}

function base32Decode(encoded, expectedBytes) {
  const out = Buffer.alloc(expectedBytes);
  let buffer = 0;
  let bitsLeft = 0;
  let idx = 0;
  for (let i = 0; i < encoded.length; i += 1) {
    const val = ALPHABET.indexOf(encoded[i]);
    if (val < 0) {
      throw new Error(`Invalid character '${encoded[i]}' in Zyrox room token`);
    }
    buffer = (buffer << 5) | val;
    bitsLeft += 5;
    if (bitsLeft >= 8 && idx < expectedBytes) {
      bitsLeft -= 8;
      out[idx] = (buffer >>> bitsLeft) & 0xff;
      idx += 1;
    }
  }
  if (idx !== expectedBytes) {
    throw new Error('Truncated Zyrox room endpoint token');
  }
  return out;
}

function isValidAlphabet(str) {
  for (let i = 0; i < str.length; i += 1) {
    if (ALPHABET.indexOf(str[i]) < 0) return false;
  }
  return true;
}

function encodeDirectRoomCode(shortCodeRaw, ipv4, port) {
  const parsedShort = parseRoomCode(shortCodeRaw).shortCode;
  const parts = ipv4.trim().split('.').map((p) => Number.parseInt(p, 10));
  if (parts.length !== 4 || parts.some((n) => Number.isNaN(n) || n < 0 || n > 255)) {
    throw new Error(`Invalid IPv4 address: ${ipv4}`);
  }
  if (port < 1 || port > 65535) {
    throw new Error(`Invalid port: ${port}`);
  }
  const payload = Buffer.alloc(7);
  payload[0] = parts[0];
  payload[1] = parts[1];
  payload[2] = parts[2];
  payload[3] = parts[3];
  payload[4] = (port >>> 8) & 0xff;
  payload[5] = port & 0xff;
  payload[6] = computeCrc8(parsedShort, payload, 6);
  return `${parsedShort}-${base32Encode(payload)}`;
}

/**
 * Parses `ZYX-XXXX` or `ZYX-XXXX-XXXXXXXXXXXX` matching `ZyroxRoomBridge.parseRoomCode`.
 */
function parseRoomCode(rawInput) {
  if (typeof rawInput !== 'string' || !rawInput.trim()) {
    throw new Error('Empty Zyrox room code');
  }
  let cleaned = rawInput.trim().toUpperCase().replace(/\s+/g, '').replace(/0/g, 'O').replace(/1/g, 'I');
  if (!cleaned.startsWith(ROOM_PREFIX)) {
    if (cleaned.length === 4) {
      cleaned = ROOM_PREFIX + cleaned;
    } else {
      throw new Error('Room codes must start with ZYX- (example: ZYX-7K9M)');
    }
  }
  const body = cleaned.slice(ROOM_PREFIX.length);
  const dash = body.indexOf('-');
  const keyPart = dash >= 0 ? body.slice(0, dash) : body;
  if (keyPart.length !== 4 || !isValidAlphabet(keyPart)) {
    throw new Error('Invalid Zyrox room code format (expected ZYX-XXXX)');
  }
  const shortCode = ROOM_PREFIX + keyPart;
  if (dash < 0) {
    return { shortCode, directHost: null, directPort: 0 };
  }

  const endpointToken = body.slice(dash + 1);
  if (endpointToken.length !== 12 || !isValidAlphabet(endpointToken)) {
    throw new Error('Malformed extended Zyrox room token');
  }
  const decoded = base32Decode(endpointToken, 7);
  const expectedCrc = computeCrc8(shortCode, decoded, 6);
  if (decoded[6] !== expectedCrc) {
    throw new Error('Zyrox room code checksum mismatch');
  }
  const ip = `${decoded[0]}.${decoded[1]}.${decoded[2]}.${decoded[3]}`;
  const port = (decoded[4] << 8) | decoded[5];
  if (port <= 0 || port > 65535) {
    throw new Error('Invalid bridge port inside Zyrox room code');
  }
  return { shortCode, directHost: ip, directPort: port };
}

/**
 * Discovers a `ZYX-XXXX` host on loopback / LAN broadcast via UDP port 44456.
 */
function discoverRoomViaUdp(shortCode, timeoutMs = 1500) {
  return new Promise((resolve, reject) => {
    const sock = dgram.createSocket('udp4');
    let settled = false;

    const finish = (err, res) => {
      if (settled) return;
      settled = true;
      try {
        sock.close();
      } catch (_) {
        // ignore
      }
      if (err) reject(err);
      else resolve(res);
    };

    const timer = setTimeout(() => {
      finish(new Error(`Timed out discovering Zyrox Room ${shortCode} on LAN UDP ${DISCOVERY_UDP_PORT}`));
    }, timeoutMs);

    sock.on('error', (err) => {
      clearTimeout(timer);
      finish(err);
    });

    sock.on('message', (msg, rinfo) => {
      const text = msg.toString('utf8').trim();
      const prefix = `${OFFER_MAGIC}${shortCode}:`;
      if (!text.startsWith(prefix)) return;
      const rest = text.slice(prefix.length);
      const colon = rest.indexOf(':');
      const portStr = colon >= 0 ? rest.slice(0, colon) : rest;
      const port = Number.parseInt(portStr, 10);
      if (port > 0 && port <= 65535) {
        clearTimeout(timer);
        finish(null, { host: rinfo.address, port });
      }
    });

    sock.bind(0, () => {
      try {
        sock.setBroadcast(true);
      } catch (_) {
        // ignore
      }
      const pkt = Buffer.from(`${DISCOVER_MAGIC}${shortCode}`, 'utf8');
      sock.send(pkt, DISCOVERY_UDP_PORT, '127.0.0.1');
      sock.send(pkt, DISCOVERY_UDP_PORT, '255.255.255.255');
    });
  });
}

/**
 * Opens a local `127.0.0.1:<ephemeralPort>` TCP relay that performs the `ZYROX_TUN_V1:<shortCode>\n`
 * -> `ZYROX_OK\n` handshake against a remote `ZyroxRoomBridge` host so Mineflayer can join transparently.
 */
async function openLocalZyroxTunnel(rawRoomCode) {
  const parsed = parseRoomCode(rawRoomCode);
  let remoteHost = parsed.directHost;
  let remotePort = parsed.directPort;

  if (!remoteHost || !remotePort) {
    const discovered = await discoverRoomViaUdp(parsed.shortCode);
    remoteHost = discovered.host;
    remotePort = discovered.port;
  }

  const sockets = new Set();
  const server = net.createServer((mcClientSocket) => {
    mcClientSocket.setNoDelay(true);
    sockets.add(mcClientSocket);
    mcClientSocket.on('close', () => sockets.delete(mcClientSocket));

    const upstream = net.connect({ host: remoteHost, port: remotePort });
    upstream.setNoDelay(true);
    sockets.add(upstream);
    upstream.on('close', () => sockets.delete(upstream));

    let handshakeDone = false;
    let headerBuf = Buffer.alloc(0);

    upstream.on('connect', () => {
      upstream.write(`${TUNNEL_MAGIC}${parsed.shortCode}\n`, 'ascii');
    });

    upstream.on('data', (chunk) => {
      if (handshakeDone) {
        if (!mcClientSocket.destroyed) mcClientSocket.write(chunk);
        return;
      }
      headerBuf = Buffer.concat([headerBuf, chunk]);
      const nl = headerBuf.indexOf(0x0a);
      if (nl === -1) {
        if (headerBuf.length > 64) {
          upstream.destroy();
          mcClientSocket.destroy();
        }
        return;
      }
      const line = headerBuf.slice(0, nl).toString('ascii').replace(/\r$/, '');
      const remainder = headerBuf.slice(nl + 1);
      headerBuf = null;

      if (line !== TUNNEL_OK) {
        upstream.destroy();
        mcClientSocket.destroy();
        return;
      }
      handshakeDone = true;
      if (remainder.length > 0 && !mcClientSocket.destroyed) {
        mcClientSocket.write(remainder);
      }
      mcClientSocket.pipe(upstream);
    });

    const cleanupPair = () => {
      if (!upstream.destroyed) upstream.destroy();
      if (!mcClientSocket.destroyed) mcClientSocket.destroy();
    };
    upstream.on('error', cleanupPair);
    mcClientSocket.on('error', cleanupPair);
    upstream.on('end', cleanupPair);
    mcClientSocket.on('end', cleanupPair);
  });

  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });

  const addr = server.address();
  return {
    shortCode: parsed.shortCode,
    localHost: '127.0.0.1',
    localPort: addr.port,
    remoteHost,
    remotePort,
    close() {
      for (const s of sockets) {
        s.destroy();
      }
      sockets.clear();
      server.close();
    },
  };
}

module.exports = {
  parseRoomCode,
  encodeDirectRoomCode,
  discoverRoomViaUdp,
  openLocalZyroxTunnel,
};
