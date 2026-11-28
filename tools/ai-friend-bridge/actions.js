'use strict';

const { normalizeAction } = require('./protocol');

const HOSTILE_MOB_NAMES = new Set([
  'zombie',
  'husk',
  'drowned',
  'skeleton',
  'stray',
  'creeper',
  'spider',
  'cave_spider',
  'witch',
  'pillager',
  'vindicator',
  'enderman',
  'blaze',
  'wither_skeleton',
  'slime',
  'phantom',
]);

const ORE_BLOCK_KEYWORDS = [
  'coal_ore',
  'iron_ore',
  'copper_ore',
  'gold_ore',
  'redstone_ore',
  'lapis_ore',
  'diamond_ore',
  'emerald_ore',
];

/**
 * Stateful execution controller translating Zyrox structured actions
 * (`FOLLOW`, `COME_HERE`, `GUARD`, `GATHER_WOOD`, `MINE_ORES`, `PLACE_TORCHES`, `IDLE`)
 * into real in-world Mineflayer pathfinding, block collection, combat, and block placement.
 */
class BotActionExecutor {
  constructor(bot, plugins, emitEvent) {
    this.bot = bot;
    this.plugins = plugins; // { goals, Movements, Vec3 }
    this.emitEvent = emitEvent;
    this.activeAction = 'IDLE';
    this.ownerUsername = 'Player';
    this.loopTimer = null;
    this.taskBusy = false;
  }

  setOwner(ownerUsername) {
    if (typeof ownerUsername === 'string' && ownerUsername.trim()) {
      this.ownerUsername = ownerUsername.trim();
    }
  }

  stopCurrentTasks() {
    if (this.loopTimer) {
      clearInterval(this.loopTimer);
      this.loopTimer = null;
    }
    this.taskBusy = false;
    try {
      if (this.bot.pathfinder) {
        this.bot.pathfinder.setGoal(null);
      }
      if (this.bot.pvp) {
        this.bot.pvp.stop();
      }
      this.bot.clearControlStates();
    } catch (_) {
      // Safe cleanup if bot disconnected mid-action
    }
  }

  executeAction(rawAction, ownerUsername) {
    if (ownerUsername) {
      this.setOwner(ownerUsername);
    }
    const action = normalizeAction(rawAction, 'IDLE');
    this.stopCurrentTasks();
    this.activeAction = action;

    switch (action) {
      case 'FOLLOW':
        this.startFollow();
        break;
      case 'COME_HERE':
        this.startComeHere();
        break;
      case 'GUARD':
        this.startGuard();
        break;
      case 'GATHER_WOOD':
        this.startGatherWood();
        break;
      case 'MINE_ORES':
        this.startMineOres();
        break;
      case 'PLACE_TORCHES':
        this.startPlaceTorches();
        break;
      case 'IDLE':
      default:
        this.emitActionUpdate('IDLE', 'COMPLETED', 'Standing by at current coordinates');
        break;
    }
    return action;
  }

  findTargetPlayerEntity() {
    const exact = this.bot.players && this.bot.players[this.ownerUsername];
    if (exact && exact.entity) return exact.entity;

    if (this.bot.players) {
      for (const [name, p] of Object.entries(this.bot.players)) {
        if (name.toLowerCase() === this.ownerUsername.toLowerCase() && p && p.entity) {
          return p.entity;
        }
      }
      for (const [name, p] of Object.entries(this.bot.players)) {
        if (name !== this.bot.username && p && p.entity) {
          return p.entity;
        }
      }
    }
    return null;
  }

  ensureMovementsConfigured() {
    if (!this.bot.pathfinder || !this.plugins || !this.plugins.Movements) return;
    const movements = new this.plugins.Movements(this.bot);
    movements.canDig = true;
    movements.allow1by1towers = false;
    this.bot.pathfinder.setMovements(movements);
  }

  startFollow() {
    this.ensureMovementsConfigured();
    const target = this.findTargetPlayerEntity();
    if (!target) {
      this.emitActionUpdate('FOLLOW', 'STARTED', `Waiting for ${this.ownerUsername} to enter render range`);
    } else if (this.bot.pathfinder && this.plugins && this.plugins.goals) {
      this.bot.pathfinder.setGoal(new this.plugins.goals.GoalFollow(target, 2.5), true);
      this.emitActionUpdate('FOLLOW', 'STARTED', `Following ${target.username || this.ownerUsername}`);
    }

    this.loopTimer = setInterval(() => {
      if (this.activeAction !== 'FOLLOW' || !this.bot.entity) return;
      const liveTarget = this.findTargetPlayerEntity();
      if (liveTarget && this.bot.pathfinder && this.plugins && this.plugins.goals && !this.bot.pathfinder.isMoving()) {
        const dist = this.bot.entity.position.distanceTo(liveTarget.position);
        if (dist > 3.2) {
          this.bot.pathfinder.setGoal(new this.plugins.goals.GoalFollow(liveTarget, 2.5), true);
        }
      }
    }, 1500);
  }

  startComeHere() {
    this.ensureMovementsConfigured();
    const target = this.findTargetPlayerEntity();
    if (!target || !this.bot.pathfinder || !this.plugins || !this.plugins.goals) {
      this.emitActionUpdate('COME_HERE', 'FAILED', `Player ${this.ownerUsername} is outside render distance`);
      return;
    }
    const pos = target.position;
    this.emitActionUpdate('COME_HERE', 'STARTED', `Moving to (${Math.round(pos.x)}, ${Math.round(pos.y)}, ${Math.round(pos.z)})`);
    this.bot.pathfinder.setGoal(new this.plugins.goals.GoalNear(pos.x, pos.y, pos.z, 1.5), false);
    this.bot.once('goal_reached', () => {
      if (this.activeAction === 'COME_HERE') {
        this.activeAction = 'IDLE';
        this.emitActionUpdate('COME_HERE', 'COMPLETED', `Arrived beside ${this.ownerUsername}`);
      }
    });
  }

  startGuard() {
    this.ensureMovementsConfigured();
    this.emitActionUpdate('GUARD', 'STARTED', 'Guarding perimeter against hostile mobs');

    this.loopTimer = setInterval(() => {
      if (this.activeAction !== 'GUARD' || !this.bot.entity) return;
      const hostile = this.bot.nearestEntity((e) => {
        if (!e || !e.position) return false;
        const mobName = (e.name || '').toLowerCase();
        if (!HOSTILE_MOB_NAMES.has(mobName)) return false;
        return e.position.distanceTo(this.bot.entity.position) <= 14;
      });

      if (hostile) {
        this.equipBestWeapon();
        if (this.bot.pvp) {
          this.bot.pvp.attack(hostile);
        } else {
          this.bot.attack(hostile);
        }
        return;
      }

      if (this.bot.pvp) {
        this.bot.pvp.stop();
      }
      const owner = this.findTargetPlayerEntity();
      if (owner && this.bot.pathfinder && this.plugins && this.plugins.goals) {
        const dist = this.bot.entity.position.distanceTo(owner.position);
        if (dist > 5.0 && !this.bot.pathfinder.isMoving()) {
          this.bot.pathfinder.setGoal(new this.plugins.goals.GoalNear(owner.position.x, owner.position.y, owner.position.z, 3.0), false);
        }
      }
    }, 900);
  }

  startGatherWood() {
    this.ensureMovementsConfigured();
    this.emitActionUpdate('GATHER_WOOD', 'STARTED', 'Searching nearby trees for logs');

    const stepHarvest = async () => {
      if (this.activeAction !== 'GATHER_WOOD' || this.taskBusy || !this.bot.entity) return;
      this.taskBusy = true;
      try {
        const logBlock = this.bot.findBlock({
          matching: (blk) => blk && typeof blk.name === 'string' && (blk.name.endsWith('_log') || blk.name.endsWith('_stem')),
          maxDistance: 32,
        });
        if (!logBlock) {
          this.emitActionUpdate('GATHER_WOOD', 'FAILED', 'No log blocks found within 32 blocks');
          this.activeAction = 'FOLLOW';
          this.startFollow();
          return;
        }
        await this.equipToolMatching('axe');
        if (this.bot.collectBlock) {
          await this.bot.collectBlock.collect(logBlock);
          this.emitActionUpdate('GATHER_WOOD', 'STARTED', `Collected ${logBlock.name}`);
        } else {
          await this.digBlockWithPathfinder(logBlock);
        }
      } catch (err) {
        this.emitActionUpdate('GATHER_WOOD', 'FAILED', err.message || 'Could not reach log block');
      } finally {
        this.taskBusy = false;
      }
    };

    stepHarvest();
    this.loopTimer = setInterval(stepHarvest, 2500);
  }

  startMineOres() {
    this.ensureMovementsConfigured();
    this.emitActionUpdate('MINE_ORES', 'STARTED', 'Scanning nearby stone faces for exposed ore veins');

    const stepMine = async () => {
      if (this.activeAction !== 'MINE_ORES' || this.taskBusy || !this.bot.entity) return;
      this.taskBusy = true;
      try {
        const oreBlock = this.bot.findBlock({
          matching: (blk) => {
            if (!blk || typeof blk.name !== 'string') return false;
            return ORE_BLOCK_KEYWORDS.some((kw) => blk.name.includes(kw));
          },
          maxDistance: 28,
        });
        if (!oreBlock) {
          this.emitActionUpdate('MINE_ORES', 'FAILED', 'No exposed ore blocks within 28 blocks');
          this.activeAction = 'FOLLOW';
          this.startFollow();
          return;
        }
        await this.equipToolMatching('pickaxe');
        if (this.bot.collectBlock) {
          await this.bot.collectBlock.collect(oreBlock);
          this.emitActionUpdate('MINE_ORES', 'STARTED', `Mined ${oreBlock.name}`);
        } else {
          await this.digBlockWithPathfinder(oreBlock);
        }
      } catch (err) {
        this.emitActionUpdate('MINE_ORES', 'FAILED', err.message || 'Could not mine ore block');
      } finally {
        this.taskBusy = false;
      }
    };

    stepMine();
    this.loopTimer = setInterval(stepMine, 2600);
  }

  startPlaceTorches() {
    this.ensureMovementsConfigured();
    this.emitActionUpdate('PLACE_TORCHES', 'STARTED', 'Checking inventory for torches and dark floor blocks');

    const attemptTorch = async () => {
      if (this.activeAction !== 'PLACE_TORCHES' || this.taskBusy || !this.bot.entity) return;
      this.taskBusy = true;
      try {
        const torchItem = this.bot.inventory.items().find(
          (it) => it && typeof it.name === 'string' && (it.name === 'torch' || it.name === 'soul_torch')
        );
        if (!torchItem) {
          this.emitActionUpdate('PLACE_TORCHES', 'FAILED', 'Out of torches in bot inventory — drop me some torches!');
          this.activeAction = 'FOLLOW';
          this.startFollow();
          return;
        }

        const Vec3 = this.plugins && this.plugins.Vec3;
        if (!Vec3) return;
        const feetPos = this.bot.entity.position.floored();
        const below = this.bot.blockAt(feetPos.offset(0, -1, 0));
        const atFeet = this.bot.blockAt(feetPos);

        const lightLevel = atFeet && typeof atFeet.light === 'number' ? atFeet.light : 0;
        if (below && below.boundingBox === 'block' && atFeet && atFeet.name === 'air' && lightLevel <= 7) {
          await this.bot.equip(torchItem, 'hand');
          await this.bot.placeBlock(below, new Vec3(0, 1, 0));
          this.emitActionUpdate('PLACE_TORCHES', 'COMPLETED', `Placed torch at (${feetPos.x}, ${feetPos.y}, ${feetPos.z})`);
        }

        // Keep escorting player so torches are placed along dark tunnels
        const owner = this.findTargetPlayerEntity();
        if (owner && this.bot.pathfinder && this.plugins && this.plugins.goals) {
          const dist = this.bot.entity.position.distanceTo(owner.position);
          if (dist > 3.5 && !this.bot.pathfinder.isMoving()) {
            this.bot.pathfinder.setGoal(new this.plugins.goals.GoalNear(owner.position.x, owner.position.y, owner.position.z, 2.5), false);
          }
        }
      } catch (_) {
        // Non-fatal placement collision; retry on next tick
      } finally {
        this.taskBusy = false;
      }
    };

    attemptTorch();
    this.loopTimer = setInterval(attemptTorch, 3000);
  }

  async digBlockWithPathfinder(block) {
    if (this.bot.pathfinder && this.plugins && this.plugins.goals) {
      await this.bot.pathfinder.goto(new this.plugins.goals.GoalNear(block.position.x, block.position.y, block.position.z, 3));
    }
    await this.bot.dig(block);
  }

  equipBestWeapon() {
    if (!this.bot.inventory) return;
    const ranks = ['netherite_sword', 'diamond_sword', 'iron_sword', 'stone_sword', 'wooden_sword', 'iron_axe', 'stone_axe'];
    const items = this.bot.inventory.items();
    for (const r of ranks) {
      const found = items.find((i) => i && i.name === r);
      if (found) {
        this.bot.equip(found, 'hand').catch(() => {});
        return;
      }
    }
  }

  async equipToolMatching(suffix) {
    if (!this.bot.inventory) return;
    const tiers = ['netherite', 'diamond', 'iron', 'stone', 'golden', 'wooden'];
    const items = this.bot.inventory.items();
    for (const t of tiers) {
      const found = items.find((i) => i && i.name === `${t}_${suffix}`);
      if (found) {
        await this.bot.equip(found, 'hand');
        return;
      }
    }
  }

  emitActionUpdate(action, status, detail) {
    if (this.emitEvent) {
      this.emitEvent({
        type: 'action_update',
        action,
        status,
        detail: detail || '',
      });
    }
  }
}

module.exports = {
  BotActionExecutor,
};
