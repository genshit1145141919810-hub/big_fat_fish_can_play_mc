// Wire the plugin action registry into the intent pipeline.
import { readFileSync, writeFileSync } from 'node:fs';

const base = 'D:/Chat/deepseek-companion/src/main/java/io/github/xiaod/deepseekcompanion/';
const out = [];
function patch(file, from, to, label) {
  const p = base + file;
  let s = readFileSync(p, 'utf8');
  if (!s.includes(from)) { out.push('MISS ' + label); return; }
  writeFileSync(p, s.replace(from, to), 'utf8');
  out.push('ok   ' + label);
}

// 1) config flag
patch('config/CompanionConfig.java',
  '    public boolean enableChat = true;',
  '    public boolean enableChat = true;\n    /** 允许第三方模组通过 entrypoint 注册新动作（插件动作只会执行它自己写的行为）。 */\n    public boolean enablePluginActions = true;',
  'config flag');

// 2) Intent gains a plugin action id
patch('chat/Intent.java',
  'public record Intent(String say, BotAction action, String arg) {',
  `public record Intent(String say, BotAction action, String arg, String pluginAction) {

    public Intent(String say, BotAction action, String arg) {
        this(say, action, arg, null);
    }`,
  'Intent component');

patch('chat/Intent.java',
  `        BotAction action = BotAction.byId(act);
        if (action == null) {
            throw new ActionValidator.FormatException("未知动作: " + actRaw);
        }`,
  `        BotAction action = BotAction.byId(act);
        if (action == null) {
            io.github.xiaod.deepseekcompanion.api.ActionProvider provider =
                    io.github.xiaod.deepseekcompanion.bot.ActionRegistry.find(act);
            if (provider == null) {
                throw new ActionValidator.FormatException("未知动作: " + actRaw);
            }
            String validated;
            try {
                validated = provider.validate(argRaw);
            } catch (IllegalArgumentException bad) {
                throw new ActionValidator.FormatException(bad.getMessage());
            }
            return new Intent(ActionValidator.sanitizeSay(say), null, validated, provider.id());
        }`,
  'Intent plugin branch');

// 3) executor dispatch
patch('bot/ActionExecutor.java',
  `        BotAction action = intent.action();
        if (action == null) {
            return Result.physical(false);
        }`,
  `        if (intent.pluginAction() != null) {
            return Result.physical(ActionRegistry.run(ActionRegistry.find(intent.pluginAction()), intent.arg()));
        }
        BotAction action = intent.action();
        if (action == null) {
            return Result.physical(false);
        }`,
  'executor dispatch');

// 4) prompt catalogue
patch('chat/Prompts.java',
  '        sb.append(BotAction.catalogue()).append(\'\\n\');',
  '        sb.append(BotAction.catalogue());\n        sb.append(io.github.xiaod.deepseekcompanion.bot.ActionRegistry.catalogueSuffix()).append(\'\\n\');',
  'prompt catalogue');

// 5) tick plugin actions + execute plugin intents
patch('bot/BotBrain.java',
  'PlanRunner.tick(mc);',
  'PlanRunner.tick(mc); ActionRegistry.tickAll(PlanRunner.anyBusy());',
  'tick plugin actions');

patch('bot/BotBrain.java',
  '        if (intent.action() != null && !intent.action().isQuery()) {',
  '        if ((intent.action() != null && !intent.action().isQuery()) || intent.pluginAction() != null) {',
  'apply plugin intents');

patch('bot/BotBrain.java',
  '            DeepseekCompanionClient.LOGGER.info("[XiaoD] act={} arg={} ok={}", intent.action().id(), intent.arg(), ok);',
  '            DeepseekCompanionClient.LOGGER.info("[XiaoD] act={} arg={} ok={}",\n                    intent.pluginAction() != null ? "plugin:" + intent.pluginAction() : intent.action().id(),\n                    intent.arg(), ok);',
  'log plugin acts');

console.log(out.join('\n'));
