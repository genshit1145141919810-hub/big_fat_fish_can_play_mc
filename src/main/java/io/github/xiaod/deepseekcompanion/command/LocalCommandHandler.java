package io.github.xiaod.deepseekcompanion.command;

import io.github.xiaod.deepseekcompanion.bot.ActionExecutor;
import io.github.xiaod.deepseekcompanion.bot.ActionValidator;
import io.github.xiaod.deepseekcompanion.bot.BaritoneBridge;
import io.github.xiaod.deepseekcompanion.bot.BotAction;
import io.github.xiaod.deepseekcompanion.bot.BotBrain;
import io.github.xiaod.deepseekcompanion.bot.GameSnapshot;
import io.github.xiaod.deepseekcompanion.config.CompanionConfig;
import io.github.xiaod.deepseekcompanion.integrations.Recipes;
import io.github.xiaod.deepseekcompanion.integrations.XaeroWaypoints;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Locale;

/**
 * Local command handling for {@code .xd ...}. These lines are cancelled before
 * they leave the client, so configuration (including the API key) never
 * reaches the server.
 */
public final class LocalCommandHandler {
    private static final String PREFIX = ".xd";

    private LocalCommandHandler() {
    }

    /** Returns true when the line was a local command and must not be sent. */
    public static boolean handle(String raw) {
        if (raw == null) {
            return false;
        }
        String line = raw.strip();
        if (!line.toLowerCase(Locale.ROOT).startsWith(PREFIX)) {
            return false;
        }
        String rest = line.substring(PREFIX.length()).strip();
        String[] parts = rest.isEmpty() ? new String[0] : rest.split("\\s+", 2);
        String cmd = parts.length > 0 ? parts[0].toLowerCase(Locale.ROOT) : "";
        String arg = parts.length > 1 ? parts[1].strip() : "";
        switch (cmd) {
            case "", "help" -> help();
            case "status" -> status();
            case "commands" -> commands();
            case "key" -> setKey(arg);
            case "login" -> login(arg);
            case "model" -> setModel(arg);
            case "owner" -> setOwner(arg);
            case "say" -> BotBrain.say(arg);
            case "follow" -> follow();
            case "come" -> say(BaritoneBridge.come() ? "过去找你" : "动不了（Baritone 没装？）");
            case "stop" -> {
                io.github.xiaod.deepseekcompanion.bot.BotActions.stopAll(MinecraftClient.getInstance());
                say("停下了（计划/挖掘/合成都清了）");
            }
            case "chop" -> chop();
            case "mine" -> mine(arg);
            case "recipe" -> recipe(arg);
            case "craft" -> craft(arg);
            case "inv" -> say(io.github.xiaod.deepseekcompanion.bot.ActionExecutor.execute(
                    MinecraftClient.getInstance(),
                    new io.github.xiaod.deepseekcompanion.chat.Intent("",
                            io.github.xiaod.deepseekcompanion.bot.BotAction.INVENTORY, "")).observation());
            case "cook" -> cook(arg);
            case "give" -> give(arg);
            case "hunt" -> hunt(arg);
            case "store" -> store(arg);
            case "take" -> take(arg);
            case "hold" -> hold(arg);
            case "use" -> useBlock(arg);
            case "brew" -> brew(arg);
            case "station" -> station(arg);
            case "enchant" -> enchant(arg);
            case "enchant_best" -> enchantBest(arg);
            case "biome" -> biome();
            case "waypoints" -> waypoints();
            case "schematic" -> schematic(arg);
            case "structures" -> structures(arg);
            case "seed" -> say("§7" + io.github.xiaod.deepseekcompanion.integrations.SeedcrackerX.seedReport());
            case "mods" -> mods();
            case "sethome" -> setHome();
            case "home" -> home(arg);
            case "protect" -> protect(arg);
            case "auto" -> auto(arg);
            case "plan" -> plan(arg);
            case "portal" -> portal(arg);
            case "place" -> place(arg);
            case "see" -> see();
            case "chat" -> say("§7" + io.github.xiaod.deepseekcompanion.chat.ChatCoordinator.lastSeen());
            case "debug" -> debug(arg);
            default -> help();
        }
        return true;
    }

    private static void help() {
        say("§b.xd help");
        say("§7.xd key <API Key> §f- 设置 DeepSeek 密钥（只存本地）");
        say("§7.xd model <名字> §f- 换模型");
        say("§7.xd owner <你的ID> §f- 指定要跟的人");
        say("§7.xd status §f- 状态");
        say("§7.xd commands §f- 模型被允许的全部动作（白名单）");
        say("§7.xd follow|come|stop|chop §f- 直接下指令");
        say("§7.xd mine <方块> §f- 挖矿");
        say("§7.xd recipe <物品> §f- 查配方");
        say("§7.xd cook [物品] §f- 用熔炉烧东西（空=烧吃的）");
        say("§7.xd give <物品> §f- 把东西丢给你");
        say("§7.xd hold <物品> §f- 把物品拿到手上");
        say("§7.xd use <方块> §f- 右键使用（门/床/炼药锅等）");
        say("§7.xd brew <材料> §f- 用酿造台酿药");
        say("§7.xd station <名字>[,物品...] §f- 砂轮/锻造台/制图台/切石机/织布机/铁砧/附魔台");
        say("§7.xd enchant <物品>[,附魔] §f- 附魔，可刷到想要的附魔（如 diamond_sword,sharpness）");
        say("§7.xd hunt [生物] §f- 打猎找吃的（如 cow）");
        say("§7.xd sethome | home [clear] §f- 把床/脚底下设成家（睡一觉也会自动设）");
        say("§7.xd protect [64|on|off] §f- 家的保护区边心距（默认 64 = 128x128，区内只准交互）");
        say("§7.xd biome | waypoints §f- 群系 / 航点");
        say("§7.xd schematic [投影名] §f- 列出投影 / 算材料清单（Litematica 的 .litematic）");
        say("§7.xd seed | structures [距离] §f- SeedcrackerX 的种子 / 附近定位到的结构");
        say("§7.xd mods §f- 看装了哪些辅助模组、哪些已经联动");
        say("§7.xd auto on|off §f- 让它自己决定下一步");
        say("§7.xd plan <目标> §f- 强制走多步计划（平时只有单步做不到/失败才会自动升级）");
        say("§7.xd plan status|stop §f- 看计划进度 / 停掉计划");
        say("§7.xd portal [nether|end] §f- 去走最近的下界门/末地门");
        say("§7.xd chat §f- 看最近一条聊天是怎么被解析的（服务器改了聊天格式先跑这个）");
        say("§7.xd debug on|off §f- 聊天调试日志 + 记住判定结果");
        say("§7.xd say <话> §f- 让它说话");
        say("§8聊天里喊「XiaoD 你好」也会回你");
    }

    private static void status() {
        CompanionConfig cfg = CompanionConfig.get();
        say("§b小D 状态");
        say("§7模型: §f" + cfg.model + "  §7密钥: §f" + (cfg.hasKey() ? "已配置(" + mask(cfg.apiKey) + ")" : "§c未配置"));
        say("§7本账号: §f" + (BotBrain.isActiveAccount(MinecraftClient.getInstance()) ? "§a工作状态" : "§7休眠（不是小号，模组不接管）"));
        say("§7小号: §f" + (cfg.botAccount == null || cfg.botAccount.isBlank() ? cfg.botName : cfg.botAccount)
                + "  §7主人: §f" + (cfg.ownerName == null || cfg.ownerName.isBlank() ? "未设置（不会跟随任何人）" : cfg.ownerName));
        say("§7Baritone: §f" + (BaritoneBridge.available() ? "已加载" : "§c未安装，移动动作不可用")
                + "  §7Xaero: §f" + (XaeroWaypoints.available() ? "已加载" : "未装")
                + "  §7JEI: §f" + (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("jei") ? "已装" : "未装"));
        boolean via = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("viafabric")
                || net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("viafabricplus");
        say("§7Via: §f" + (via ? "已装（可跨版本进服）" : "未装"));
        int protocol = io.github.xiaod.deepseekcompanion.bot.ViaBridge
                .protocol(MinecraftClient.getInstance());
        say("§7服务器协议: §f" + io.github.xiaod.deepseekcompanion.bot.ViaBridge.describe(protocol)
                + (io.github.xiaod.deepseekcompanion.bot.ViaBridge.isLegacyCombat(protocol)
                ? "  §7（无攻击冷却，已按手速打）" : ""));
        say("§7自动 跟随/战斗/进食: §f" + onOff(cfg.enableAutoFollow) + "/" + onOff(cfg.enableAutoFight) + "/" + onOff(cfg.enableAutoEat));
        say("§7自动决策: §f" + (cfg.enableAutonomous ? "开（" + cfg.autonomousIntervalSeconds + "s）" : "关"));
        say("§7" + io.github.xiaod.deepseekcompanion.bot.HomeZone.describe());
        say("§7投影: §f" + io.github.xiaod.deepseekcompanion.integrations.LitematicaSchematics.files().size()
                + " 个 §7/ 辅助模组 §f" + io.github.xiaod.deepseekcompanion.integrations.ModLinks
                .loadedHelpers().size() + " 个");
        say("§7" + io.github.xiaod.deepseekcompanion.integrations.SeedcrackerX.statusLine());
        say("§7聊天调试: §f" + onOff(cfg.chatDebug) + " §7宽松触发: §f" + onOff(cfg.chatTriggerAnywhere));
        say("§8" + BotBrain.statusLine());
    }

    private static void commands() {
        say("§b模型只能从这些动作里选：");
        for (String line : BotAction.catalogue().split("\n")) {
            say("§7" + line);
        }
        say("§b真正下发给 Baritone 的只有（走路 + 挖矿）：");
        say("§7#stop / #come / #follow player <ID> / #follow entity <类型> / #goto <x> <y> <z> / #mine <单个方块>");
        say("§8  #mine 的渲染线程死锁已由模组里的 Mixin 补丁绕开；没装 Baritone 时自动退回本地挖掘");
    }

    private static void setKey(String arg) {
        if (arg.isBlank()) {
            say("§c用法: .xd key sk-xxx");
            return;
        }
        CompanionConfig cfg = CompanionConfig.get();
        cfg.apiKey = arg;
        CompanionConfig.save();
        say("§a密钥已保存 (" + mask(cfg.apiKey) + ")");
    }

    private static void setModel(String arg) {
        if (arg.isBlank()) {
            say("§c用法: .xd model deepseek-chat");
            return;
        }
        CompanionConfig cfg = CompanionConfig.get();
        cfg.model = arg;
        CompanionConfig.save();
        say("§a模型切到 " + cfg.model);
    }

    private static void setOwner(String arg) {
        if (arg.isBlank()) {
            say("§c用法: .xd owner 你的ID");
            return;
        }
        CompanionConfig cfg = CompanionConfig.get();
        cfg.ownerName = arg;
        CompanionConfig.save();
        say("§a现在跟着 " + cfg.ownerName);
    }

    private static void follow() {
        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerEntity owner = GameSnapshot.findOwner(mc, CompanionConfig.get());
        if (owner == null) {
            say("§c附近没找到人，先 .xd owner <你的ID>");
            return;
        }
        say(BaritoneBridge.followPlayer(owner.getName().getString())
                ? "去跟 " + owner.getName().getString() + " 了"
                : "跟不了（Baritone 没装？）");
    }

    private static void chop() {
        MinecraftClient mc = MinecraftClient.getInstance();
        String log = ActionExecutor.nearestLog(mc);
        if (log.isBlank()) {
            say("§7附近没有能砍的树（家保护区里的不算）");
            return;
        }
        boolean ok = false;
        if (BaritoneBridge.available()) {
            ok = BaritoneBridge.mine(log);
        }
        if (!ok) {
            ok = io.github.xiaod.deepseekcompanion.bot.MiningController.start(mc, null, true, 8);
        }
        if (ok) {
            io.github.xiaod.deepseekcompanion.bot.TaskTracker.mark("chop", 240_000L);
        }
        say(ok ? "去砍 " + log + " 了" : "附近没树或者动不了");
    }

    private static void mine(String arg) {
        try {
            String blocks = ActionValidator.validateBlocks(arg);
            String first = ActionExecutor.firstBlockId(blocks);
            MinecraftClient mc = MinecraftClient.getInstance();
            boolean started = false;
            if (BaritoneBridge.available()) {
                started = BaritoneBridge.mine(first);
            }
            if (!started) {
                started = io.github.xiaod.deepseekcompanion.bot.MiningController.start(mc, first, false, 3);
            }
            if (started) {
                io.github.xiaod.deepseekcompanion.bot.TaskTracker.mark("mine", 240_000L);
            }
            say(started ? "去挖 " + first + " 了" : "动不了（附近也没找到保护区外的 " + first + "）");
        } catch (ActionValidator.FormatException e) {
            say("§c" + e.getMessage());
        }
    }

    // ------------------------------------------------------------ 家 / 保护区

    private static void setHome() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) {
            say("§c还没进世界");
            return;
        }
        BlockPos bed = io.github.xiaod.deepseekcompanion.bot.HomeZone
                .nearestBed(mc, mc.player.getBlockPos(), 8);
        io.github.xiaod.deepseekcompanion.bot.HomeZone
                .setHome(mc, bed != null ? bed : mc.player.getBlockPos());
        say("§b" + io.github.xiaod.deepseekcompanion.bot.HomeZone.describe());
    }

    private static void home(String arg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (arg.equalsIgnoreCase("clear") || arg.equalsIgnoreCase("off")) {
            io.github.xiaod.deepseekcompanion.bot.HomeZone.clear(mc);
            return;
        }
        CompanionConfig cfg = CompanionConfig.get();
        if (!cfg.homeSet) {
            say("§7还没设家。在床上睡一次就会自动设，或者 .xd sethome 用脚底下/附近的床");
            return;
        }
        say("§b" + io.github.xiaod.deepseekcompanion.bot.HomeZone.describe());
        if (mc.player == null || mc.world == null) {
            return;
        }
        if (!io.github.xiaod.deepseekcompanion.bot.HomeZone.dimensionOf(mc).equals(cfg.homeDimension)) {
            say("§7家不在当前维度（家在 " + io.github.xiaod.deepseekcompanion.bot.HomeZone
                    .shortDimension(cfg.homeDimension) + "）");
            return;
        }
        int dx = cfg.homeX - mc.player.getBlockPos().getX();
        int dz = cfg.homeZ - mc.player.getBlockPos().getZ();
        say("§7离家 §f" + Math.round(Math.sqrt((double) dx * dx + (double) dz * dz)) + " 格"
                + (io.github.xiaod.deepseekcompanion.bot.HomeZone.protectedHere()
                ? "  §c（你在保护区里：这儿只能交互，不能挖）" : ""));
    }

    private static void protect(String arg) {
        CompanionConfig cfg = CompanionConfig.get();
        if (arg.isBlank()) {
            say("§7" + io.github.xiaod.deepseekcompanion.bot.HomeZone.describe());
            say("§8用法: .xd protect 64 / .xd protect on / .xd protect off");
            return;
        }
        if (arg.equalsIgnoreCase("on")) {
            cfg.enableHomeProtection = true;
            CompanionConfig.save();
            say("§a保护区已开");
            return;
        }
        if (arg.equalsIgnoreCase("off")) {
            cfg.enableHomeProtection = false;
            CompanionConfig.save();
            say("§7保护区关了（小D 又能拆家了）");
            return;
        }
        try {
            int value = Integer.parseInt(arg.strip());
            if (value < 4 || value > 512) {
                say("§c边心距要在 4~512 之间");
                return;
            }
            cfg.protectApothem = value;
            CompanionConfig.save();
            say("§a保护区改成 " + (value * 2) + "x" + (value * 2) + "（边心距 " + value + "）");
        } catch (NumberFormatException e) {
            say("§c用法: .xd protect 64");
        }
    }

    private static void enchant(String arg) {
        boolean ok = io.github.xiaod.deepseekcompanion.bot.EnchantController
                .start(MinecraftClient.getInstance(), arg);
        say(ok ? "去附魔 " + arg : "附魔不了（附近没附魔台？）");
    }

    private static void enchantBest(String arg) {
        boolean ok = io.github.xiaod.deepseekcompanion.bot.EnchantPicker
                .start(MinecraftClient.getInstance(), arg);
        say(ok ? "按 37% 规则挑一件附魔（" + arg + "）" : "挑不了（没附魔台或没这件装备）");
    }

    private static void station(String arg) {
        boolean ok = io.github.xiaod.deepseekcompanion.bot.StationController
                .start(MinecraftClient.getInstance(), arg);
        say(ok ? "去用站点 " + arg : "站点用不了（名字或位置不对）");
    }
    private static void hold(String arg) {
        try {
            String item = ActionValidator.validateItem(arg);
            boolean ok = io.github.xiaod.deepseekcompanion.bot.InteractController
                    .hold(MinecraftClient.getInstance(), item);
            say(ok ? "手上换成 " + item : "拿不到 " + item);
        } catch (ActionValidator.FormatException e) {
            say("§c" + e.getMessage());
        }
    }

    private static void useBlock(String arg) {
        try {
            String blocks = ActionValidator.validateBlocks(arg);
            String first = ActionExecutor.firstBlockId(blocks);
            boolean ok = io.github.xiaod.deepseekcompanion.bot.InteractController
                    .use(MinecraftClient.getInstance(), first);
            say(ok ? "去用 " + first : "用不了 " + first);
        } catch (ActionValidator.FormatException e) {
            say("§c" + e.getMessage());
        }
    }

    private static void brew(String arg) {
        try {
            String item = ActionValidator.validateItem(arg);
            boolean ok = io.github.xiaod.deepseekcompanion.bot.InteractController
                    .brew(MinecraftClient.getInstance(), item);
            say(ok ? "去酿造 " + item : "酿不了");
        } catch (ActionValidator.FormatException e) {
            say("§c" + e.getMessage());
        }
    }
    private static void cook(String arg) {
        try {
            String items = ActionValidator.validateItems(arg);
            String first = items.isEmpty() ? "" : ActionExecutor.firstBlockId(items);
            boolean ok = io.github.xiaod.deepseekcompanion.bot.ContainerController
                    .cook(MinecraftClient.getInstance(), first);
            say(ok ? (first.isEmpty() ? "去烧东西了" : "去烧 " + first) : "烧不了（附近没熔炉或没材料）");
        } catch (ActionValidator.FormatException e) {
            say("§c" + e.getMessage());
        }
    }

    private static void recipe(String arg) {
        try {
            String item = ActionValidator.validateItem(arg);
            say("§b" + Recipes.crafting(MinecraftClient.getInstance(), item));
        } catch (ActionValidator.FormatException e) {
            say("§c" + e.getMessage());
        }
    }

    private static void login(String arg) {
        if (arg.isBlank()) {
            say("§c用法: .xd login 你的密码");
            return;
        }
        CompanionConfig cfg = CompanionConfig.get();
        cfg.loginPassword = arg.strip();
        CompanionConfig.save();
        io.github.xiaod.deepseekcompanion.bot.LoginController.sendNow(MinecraftClient.getInstance());
        say("§a密码已保存，并已发送 /login");
    }

    private static void store(String arg) {
        try {
            String items = ActionValidator.validateItems(arg);
            boolean ok = io.github.xiaod.deepseekcompanion.bot.StorageController
                    .store(MinecraftClient.getInstance(), items);
            say(ok ? (items.isEmpty() ? "去存不用的东西" : "去存 " + items) : "存不了（附近没箱子？）");
        } catch (ActionValidator.FormatException e) {
            say("§c" + e.getMessage());
        }
    }

    private static void take(String arg) {
        try {
            String items = ActionValidator.validateItems(arg);
            if (items.isEmpty()) {
                say("§c要说取什么，比如 .xd take iron_ingot");
                return;
            }
            boolean ok = io.github.xiaod.deepseekcompanion.bot.StorageController
                    .take(MinecraftClient.getInstance(), items);
            say(ok ? "去箱子里取 " + items : "取不了（附近没箱子？）");
        } catch (ActionValidator.FormatException e) {
            say("§c" + e.getMessage());
        }
    }

    private static void hunt(String arg) {
        try {
            String type = ActionValidator.validateEntity(arg);
            boolean ok = io.github.xiaod.deepseekcompanion.bot.CombatController
                    .startHunt(MinecraftClient.getInstance(), type);
            say(ok ? (type.isEmpty() ? "去打猎了" : "去找 " + type + " 了") : "打不了猎（还没进世界）");
        } catch (ActionValidator.FormatException e) {
            say("§c" + e.getMessage());
        }
    }

    private static void give(String arg) {
        try {
            String item = ActionValidator.validateItem(arg);
            boolean ok = ActionExecutor.execute(MinecraftClient.getInstance(),
                    new io.github.xiaod.deepseekcompanion.chat.Intent("", BotAction.GIVE, item)).ok();
            say(ok ? "过去给你 " + item : "身上没有 " + item);
        } catch (ActionValidator.FormatException e) {
            say("§c" + e.getMessage());
        }
    }

    private static void biome() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) {
            say("§c还没进世界");
            return;
        }
        String name = mc.world.getBiome(mc.player.getBlockPos()).getKey()
                .map(key -> key.getValue().getPath())
                .orElse("未知");
        say("§b当前群系：§f" + name);
    }

    private static void waypoints() {
        List<XaeroWaypoints.Point> points = XaeroWaypoints.list(MinecraftClient.getInstance());
        if (points.isEmpty()) {
            say(XaeroWaypoints.available() ? "§7小地图里还没有航点" : "§c没装 Xaero 小地图，读不到航点");
            return;
        }
        for (XaeroWaypoints.Point point : points) {
            say("§7- §f" + point);
        }
    }

    private static void schematic(String arg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) {
            say("§c还没进世界");
            return;
        }
        String text = arg.isBlank()
                ? io.github.xiaod.deepseekcompanion.integrations.LitematicaSchematics.listing()
                : io.github.xiaod.deepseekcompanion.integrations.LitematicaSchematics.report(mc, arg);
        for (String line : text.split("\n")) {
            say("§7" + line);
        }
    }

    private static void mods() {
        for (String line : io.github.xiaod.deepseekcompanion.integrations.ModLinks.listing()) {
            say(line);
        }
    }

    private static void structures(String arg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) {
            say("§c还没进世界");
            return;
        }
        int limit = ActionExecutor.distance(arg, 200);
        String text = io.github.xiaod.deepseekcompanion.integrations.SeedcrackerX.report(mc, limit);
        for (String line : text.split("\n")) {
            say("§7" + line);
        }
    }

    /** agent 层：强制规划 / 看进度 / 停掉。 */
    private static void plan(String arg) {
        if (arg.isBlank()) {
            say("§7用法: .xd plan <目标>  |  .xd plan status  |  .xd plan stop");
            say("§8" + io.github.xiaod.deepseekcompanion.bot.PlanRunner.status());
            return;
        }
        if (arg.equalsIgnoreCase("stop")) {
            io.github.xiaod.deepseekcompanion.bot.PlanRunner.stop("命令停止");
            say("§7计划停了");
            return;
        }
        if (arg.equalsIgnoreCase("status")) {
            say("§7" + io.github.xiaod.deepseekcompanion.bot.PlanRunner.status());
            return;
        }
        if (io.github.xiaod.deepseekcompanion.bot.PlanRunner.isPlanning()) {
            say("§c已经在计划里了：" + io.github.xiaod.deepseekcompanion.bot.PlanRunner.goalText());
            return;
        }
        say("§7给「" + arg.strip() + "」拆个计划，稍等");
        BotBrain.requestPlan(arg.strip(), null);
    }

    private static void portal(String arg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) {
            say("§c还没进世界");
            return;
        }
        boolean ok = io.github.xiaod.deepseekcompanion.bot.PortalController.start(mc, arg);
        say(ok ? "§7去走门了（走到方块里等传送）"
                : "§c附近 24 格里没找到" + (arg.isBlank() ? "传送门" : " " + arg + " 门"));
    }

    /** 感知层自检：一次打印模型会看到的那些感知信息。 */
    private static void see() {
        MinecraftClient mc = MinecraftClient.getInstance();
        String[] lines = {
                io.github.xiaod.deepseekcompanion.bot.Perception.look(mc),
                io.github.xiaod.deepseekcompanion.bot.Perception.danger(mc),
                io.github.xiaod.deepseekcompanion.bot.Perception.path(mc),
                io.github.xiaod.deepseekcompanion.bot.Perception.containers(mc),
                io.github.xiaod.deepseekcompanion.bot.Perception.drops(mc),
                io.github.xiaod.deepseekcompanion.bot.Perception.players(mc)
        };
        for (String line : lines) {
            for (String part : line.split("\n")) {
                say("§7" + part);
            }
        }
    }

    private static void debug(String arg) {
        CompanionConfig cfg = CompanionConfig.get();
        if (arg.equalsIgnoreCase("on")) {
            cfg.chatDebug = true;
            CompanionConfig.save();
            say("§a聊天调试开：叫它一次，然后 .xd chat 看它到底收到什么");
            return;
        }
        if (arg.equalsIgnoreCase("off")) {
            cfg.chatDebug = false;
            CompanionConfig.save();
            say("§7聊天调试关");
            return;
        }
        if (arg.equalsIgnoreCase("loose") || arg.equalsIgnoreCase("anywhere")) {
            cfg.chatTriggerAnywhere = true;
            CompanionConfig.save();
            say("§a宽松触发开：句子里出现独立的「" + cfg.botName + "」就回");
            return;
        }
        if (arg.equalsIgnoreCase("strict")) {
            cfg.chatTriggerAnywhere = false;
            CompanionConfig.save();
            say("§7恢复正常触发（@名字 或句首叫它）");
            return;
        }
        say("§7聊天调试: " + onOff(cfg.chatDebug) + "  宽松触发: " + onOff(cfg.chatTriggerAnywhere));
        say("§8.xd debug on|off  |  .xd debug loose|strict");
    }

    /** 放方块：工作台/熔炉/高炉/烟熏炉……任何方块物品都能放。 */
    /** 合成（会自动展开原料树：缺木板/木棍会先合出来）。 */
    private static void craft(String arg) {
        try {
            String item = ActionValidator.validateItem(arg);
            boolean ok = io.github.xiaod.deepseekcompanion.bot.ContainerController
                    .craft(MinecraftClient.getInstance(), item);
            say(ok ? "§7开始合成 " + item : "§c合成不了 " + item);
        } catch (ActionValidator.FormatException e) {
            say("§c" + e.getMessage());
        }
    }

    private static void place(String arg) {
        try {
            String item = ActionValidator.validateItem(arg);
            boolean ok = io.github.xiaod.deepseekcompanion.bot.PlacementController
                    .place(MinecraftClient.getInstance(), item);
            say(ok ? "放了 " + item : "放不了 " + item);
        } catch (ActionValidator.FormatException e) {
            say("§c" + e.getMessage());
        }
    }

    private static void auto(String arg) {
        CompanionConfig cfg = CompanionConfig.get();
        if (arg.equalsIgnoreCase("on")) {
            cfg.enableAutonomous = true;
            CompanionConfig.save();
            say("§a自动决策开启，每 " + cfg.autonomousIntervalSeconds + " 秒自己找事做");
        } else if (arg.equalsIgnoreCase("off")) {
            cfg.enableAutonomous = false;
            CompanionConfig.save();
            say("§a自动决策已关闭");
        } else {
            say("§c用法: .xd auto on|off");
        }
    }

    private static String onOff(boolean value) {
        return value ? "开" : "关";
    }

    private static void say(String message) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("[小D] " + message), false);
        }
    }

    private static String mask(String key) {
        if (key == null || key.length() < 8) {
            return "****";
        }
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }
}
