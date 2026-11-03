package io.github.xiaod.deepseekcompanion.bot;

/**
 * The complete vocabulary the model may use. Anything outside this enum is
 * rejected before a command ever reaches Baritone or the game. This is
 * deliberate: the model never gets to author free-form commands.
 *
 * <p>PHYSICAL actions change the world; QUERY actions only read state and
 * return an observation string that is fed back to the model.</p>
 */
public enum BotAction {
    FOLLOW("follow", "跟住主人", Kind.PHYSICAL, ArgKind.NONE),
    COME("come", "立刻跑到主人身边", Kind.PHYSICAL, ArgKind.NONE),
    STOP("stop", "停下所有动作", Kind.PHYSICAL, ArgKind.NONE),
    MINE("mine", "挖指定方块", Kind.PHYSICAL, ArgKind.BLOCKS),
    CHOP("chop", "砍树", Kind.PHYSICAL, ArgKind.NONE),
    GOTO("goto", "走到指定坐标", Kind.PHYSICAL, ArgKind.POS),
    GIVE("give", "把背包里的东西丢给主人", Kind.PHYSICAL, ArgKind.ITEM),
    HUNT("hunt", "去打猎（杀动物找吃的）", Kind.PHYSICAL, ArgKind.ENTITY),
    COOK("cook", "用附近的熔炉烧东西（留空=烧吃的，或填物品如 iron_ore）", Kind.PHYSICAL, ArgKind.ITEMS),
    CRAFT("craft", "用附近的工作台合成物品", Kind.PHYSICAL, ArgKind.ITEM),
    STORE("store", "把不用的东西存进最近的箱子", Kind.PHYSICAL, ArgKind.ITEMS),
    TAKE("take", "从最近的箱子里取东西", Kind.PHYSICAL, ArgKind.ITEMS),
    HOLD("hold", "把某个物品拿到手上（为下一步交互准备）", Kind.PHYSICAL, ArgKind.ITEM),
    USE("use", "对着最近的某个方块右键使用（门/按钮/床/炼药锅/堆肥桶等）", Kind.PHYSICAL, ArgKind.BLOCKS),
    BREW("brew", "用酿造台酿药（arg=材料，如 nether_wart）", Kind.PHYSICAL, ArgKind.ITEM),
    STATION("station", "使用工作站点：砂轮/锻造台/制图台/切石机/织布机/铁砧/附魔台（arg=站点名[,物品...]）", Kind.PHYSICAL, ArgKind.STRING),
    ENCHANT("enchant", "附魔并刷附魔（arg=物品[,想要的附魔]，如 diamond_sword,sharpness）", Kind.PHYSICAL, ArgKind.STRING),
    ENCHANT_BEST("enchant_best", "多件同类装备时按 37% 采样挑最好的附魔（arg=物品[,想要的附魔]）", Kind.PHYSICAL, ArgKind.STRING),
    EAT("eat", "吃东西", Kind.PHYSICAL, ArgKind.NONE),
    FIGHT("fight", "攻击附近怪物", Kind.PHYSICAL, ArgKind.NONE),
    PORTAL("portal", "走进传送门跨维度（留空=最近的门，或填 nether/end）", Kind.PHYSICAL, ArgKind.STRING),
    PLACE("place", "把手上的方块放到脚边（工作台之类，arg=方块 ID）", Kind.PHYSICAL, ArgKind.ITEM),
    ACQUIRE("acquire", "弄到某种东西（会自己挖/烧/合/从箱子取/打猎，arg=物品 ID）", Kind.PHYSICAL, ArgKind.ITEM),
    IDLE("idle", "什么也不做", Kind.PHYSICAL, ArgKind.NONE),

    RECIPE("recipe", "查某个物品怎么合出来", Kind.QUERY, ArgKind.ITEM),
    USES("uses", "查某个物品能用来做什么", Kind.QUERY, ArgKind.ITEM),
    NEARBY("nearby", "看看附近有什么玩家和生物", Kind.QUERY, ArgKind.NONE),
    INVENTORY("inventory", "清点自己的背包", Kind.QUERY, ArgKind.NONE),
    STATS("stats", "查看血量、饱食度、经验", Kind.QUERY, ArgKind.NONE),
    BIOME("biome", "查询当前生物群系", Kind.QUERY, ArgKind.NONE),
    SCHEMATIC("schematic", "查 Litematica 投影的材料清单（留空=列出全部投影，或填投影名）", Kind.QUERY, ArgKind.STRING),
    STRUCTURES("structures", "看 SeedcrackerX 定位出的附近结构（留空=200 格内，或填最大距离）", Kind.QUERY, ArgKind.STRING),
    WAYPOINTS("waypoints", "列出小地图里的航点", Kind.QUERY, ArgKind.NONE),
    LOOK("look", "看看自己正对着什么方块或生物", Kind.QUERY, ArgKind.NONE),
    SCAN("scan", "扫附近某种方块的位置和数量（arg=方块 ID）", Kind.QUERY, ArgKind.BLOCKS),
    DANGER("danger", "列出附近的敌对生物、距离和血量", Kind.QUERY, ArgKind.NONE),
    CONTAINERS("containers", "附近有哪些箱子、熔炉、工作台等站点", Kind.QUERY, ArgKind.NONE),
    PATH("path", "看自己当前任务/计划状态，判断是不是卡住了", Kind.QUERY, ArgKind.NONE),
    DROPS("drops", "看脚边掉落的物品（挖了没捡会误判材料）", Kind.QUERY, ArgKind.NONE),
    PLAYERS("players", "看看附近有哪些玩家、拿着什么", Kind.QUERY, ArgKind.NONE);

    public enum Kind {
        PHYSICAL, QUERY
    }

    public enum ArgKind {
        NONE, BLOCKS, POS, ITEM, ITEMS, ENTITY, STRING
    }

    private final String id;
    private final String description;
    private final Kind kind;
    private final ArgKind argKind;

    BotAction(String id, String description, Kind kind, ArgKind argKind) {
        this.id = id;
        this.description = description;
        this.kind = kind;
        this.argKind = argKind;
    }

    public String id() {
        return id;
    }

    public String description() {
        return description;
    }

    public Kind kind() {
        return kind;
    }

    public ArgKind argKind() {
        return argKind;
    }

    public boolean isQuery() {
        return kind == Kind.QUERY;
    }

    public static BotAction byId(String id) {
        if (id == null) {
            return null;
        }
        String needle = id.strip().toLowerCase(java.util.Locale.ROOT);
        for (BotAction action : values()) {
            if (action.id.equals(needle)) {
                return action;
            }
        }
        return null;
    }

    /** Catalogue embedded in the model prompt and shown by /xiaod commands. */
    public static String catalogue() {
        StringBuilder sb = new StringBuilder();
        for (BotAction action : values()) {
            sb.append(describe(action)).append('\n');
        }
        return sb.toString();
    }

    /** 只列物理动作：计划（agent 层）里不允许出现查询动作。 */
    public static String physicalCatalogue() {
        StringBuilder sb = new StringBuilder();
        for (BotAction action : values()) {
            if (action.kind == Kind.QUERY) {
                continue;
            }
            sb.append(describe(action)).append('\n');
        }
        return sb.toString();
    }

    private static String describe(BotAction action) {
        StringBuilder sb = new StringBuilder();
        sb.append("- ").append(action.id).append("：").append(action.description);
        switch (action.argKind) {
            case BLOCKS -> sb.append("（arg=方块 ID，多个用逗号）");
            case POS -> sb.append("（arg=三个整数坐标，如 100 64 -200）");
            case ITEM -> sb.append("（arg=物品 ID，如 diamond_pickaxe）");
            case ITEMS -> sb.append("（arg=物品 ID 列表，逗号分隔；留空=不用的那些）");
            case ENTITY -> sb.append("（arg=生物类型，如 cow；留空=随便找一只）");
            case STRING -> sb.append("（arg=自由文本）");
            default -> {
            }
        }
        return sb.toString();
    }
}
