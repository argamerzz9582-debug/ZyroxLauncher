'use strict';

const crypto = require('node:crypto');

const PROTOCOL_VERSION = 1;
const MAX_LINE_BYTES = 16 * 1024;
const MAX_MINECRAFT_CHAT_CHARS = 220;

const VALID_ACTIONS = Object.freeze([
  'FOLLOW',
  'COME_HERE',
  'GUARD',
  'GATHER_WOOD',
  'MINE_ORES',
  'PLACE_TORCHES',
  'IDLE',
]);

/**
 * Normalizes raw action strings or fuzzy aliases into one of the 7 canonical Zyrox actions.
 */
function normalizeAction(raw, fallback = 'IDLE') {
  if (typeof raw !== 'string' || !raw.trim()) {
    return fallback;
  }
  const norm = raw.trim().toUpperCase().replace(/[\s-]+/g, '_');
  if (VALID_ACTIONS.includes(norm)) {
    return norm;
  }
  if (norm.includes('COME') || norm === 'HERE' || norm.includes('RALLY')) return 'COME_HERE';
  if (norm.includes('FOLLOW') || norm.includes('ESCORT')) return 'FOLLOW';
  if (norm.includes('GUARD') || norm.includes('PROTECT') || norm.includes('DEFEND')) return 'GUARD';
  if (norm.includes('WOOD') || norm.includes('TREE') || norm.includes('LOG')) return 'GATHER_WOOD';
  if (norm.includes('MINE') || norm.includes('ORE') || norm.includes('IRON') || norm.includes('DIAMOND')) return 'MINE_ORES';
  if (norm.includes('TORCH') || norm.includes('LIGHT')) return 'PLACE_TORCHES';
  if (norm.includes('IDLE') || norm.includes('STOP') || norm.includes('WAIT')) return 'IDLE';
  return fallback;
}

/**
 * Sanitizes outgoing bot chat lines so control characters, newlines, § codes, and accidental
 * leading slash-commands cannot be injected into Minecraft chat.
 */
function sanitizeChatMessage(raw) {
  if (typeof raw !== 'string') return '';
  let text = raw
    .replace(/[\r\n\t]+/g, ' ')
    .replace(/[\u0000-\u001F\u007F\u00A7]/g, '')
    .trim();
  while (text.startsWith('/')) {
    text = text.slice(1).trim();
  }
  if (text.length > MAX_MINECRAFT_CHAT_CHARS) {
    text = `${text.slice(0, MAX_MINECRAFT_CHAT_CHARS - 3).trim()}...`;
  }
  return text;
}

/**
 * Constant-time token check when `ZYROX_BRIDGE_TOKEN` is configured.
 */
function verifyBridgeToken(expectedToken, providedToken) {
  if (!expectedToken) return true;
  if (typeof providedToken !== 'string' || !providedToken) return false;
  const a = Buffer.from(expectedToken, 'utf8');
  const b = Buffer.from(providedToken, 'utf8');
  if (a.length !== b.length) return false;
  return crypto.timingSafeEqual(a, b);
}

/**
 * Attaches a bounded NDJSON line decoder to a Node.js Duplex/Socket stream.
 */
function attachNdjsonReader(stream, onFrame, onFrameError) {
  let buffer = '';
  stream.on('data', (chunk) => {
    buffer += chunk.toString('utf8');
    if (buffer.length > MAX_LINE_BYTES * 2) {
      buffer = '';
      if (onFrameError) onFrameError(new Error('NDJSON frame exceeded 16 KB safety cap'));
      return;
    }
    let newlineIdx;
    while ((newlineIdx = buffer.indexOf('\n')) !== -1) {
      const line = buffer.slice(0, newlineIdx).replace(/\r$/, '').trim();
      buffer = buffer.slice(newlineIdx + 1);
      if (!line) continue;
      if (Buffer.byteLength(line, 'utf8') > MAX_LINE_BYTES) {
        if (onFrameError) onFrameError(new Error('Oversized NDJSON line ignored'));
        continue;
      }
      try {
        const parsed = JSON.parse(line);
        if (parsed && typeof parsed === 'object') {
          onFrame(parsed);
        }
      } catch (err) {
        if (onFrameError) onFrameError(err);
      }
    }
  });
}

function sendNdjsonFrame(socket, payload) {
  if (!socket || socket.destroyed || !socket.writable) return false;
  socket.write(`${JSON.stringify(payload)}\n`, 'utf8');
  return true;
}

module.exports = {
  PROTOCOL_VERSION,
  MAX_LINE_BYTES,
  MAX_MINECRAFT_CHAT_CHARS,
  VALID_ACTIONS,
  normalizeAction,
  sanitizeChatMessage,
  verifyBridgeToken,
  attachNdjsonReader,
  sendNdjsonFrame,
};
