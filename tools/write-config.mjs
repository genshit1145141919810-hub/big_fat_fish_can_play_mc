// Rewrite the instance config with correct UTF-8 (no BOM). Node is used because
// Windows PowerShell 5.1 mangles UTF-8 files read with Get-Content -Raw.
import { writeFileSync, readFileSync } from 'node:fs';

const path = 'D:/Game/.minecraft/versions/1.21.11-Fabric 0.20.1/config/deepseek-companion.json';

const cfg = {
  apiKey: 'sk-6a1ac2c8f0ce4044981c6aa713e9759c',
  baseUrl: 'https://api.deepseek.com',
  model: 'deepseek-chat',
  temperature: 0.9,
  maxTokens: 200,
  timeoutSeconds: 25,
  botName: 'Minecoinft',
  botAccount: 'Minecoinft',
  onlyRunAsBotAccount: true,
  ownerName: 'Leeskykit',
  persona: '你叫 Minecoinft，大家叫你小D。你是一个正在和玩家一起玩 Minecraft 的真人玩家。你说话像队友：短、口语、有情绪、不啰嗦、不说教，偶尔会开玩笑。',
  enableChat: true,
  chatDebug: true,
  chatTriggerAnywhere: false,
  autoLogin: true,
  loginPassword: 'server1',
  loginCommand: '/login {password}',
  autoLoginDelaySeconds: 4,
  enableAutoFollow: true,
  followDistance: 12,
  abandonTaskDistance: 0,
  fightRange: 16,
  followPriorityRange: 32,
  enableAutoFight: true,
  enableAutoEat: true,
  critEnabled: true,
  critMinDistance: 2.4,
  switchWeapon: true,
  autoArmor: true,
  retreatHealth: 8.0,
  enableProactiveChat: true,
  enableAdvancementReaction: true,
  proactiveUseModel: true,
  proactiveMinSeconds: 90,
  proactiveMaxSeconds: 300,
  enableAutonomous: false,
  autonomousIntervalSeconds: 45,
  planOnFailure: true,
  planMaxSteps: 20,
  planStepTimeoutSeconds: 90,
  planMaxReplans: 2,
  baritonePrefix: '#',
  useBaritoneAvoidance: true,
  mobAvoidanceRadius: 12,
  mobAvoidanceCoefficient: 1.0,
  homeSet: false,
  homeDimension: '',
  homeX: 0,
  homeY: 0,
  homeZ: 0,
  protectApothem: 64,
  protectHeight: 24,
  enableHomeProtection: true,
  schematicDir: '',
  treeBlocks: 'oak_log,birch_log,spruce_log,jungle_log,acacia_log,dark_oak_log,mangrove_log,cherry_log,crimson_stem,warped_stem',
};

writeFileSync(path, JSON.stringify(cfg, null, 2) + '\n', 'utf8');

// verify: parse it back and check the key survived the UTF-8 round trip
const parsed = JSON.parse(readFileSync(path, 'utf8'));
console.log('apiKey set:', parsed.apiKey.length > 10);
console.log('persona ok:', parsed.persona.includes('小D'));
console.log('bytes:', readFileSync(path).length);
