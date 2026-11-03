package io.github.xiaod.deepseekcompanion.integrations;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 与 SeedcrackerX 联动：它负责破解种子和按种子定位结构，我们只读它已经算出来的结果。
 *
 * <p>为什么只能这样：1.21.11 的客户端<b>没有 ChunkGenerator / NoiseConfig</b>（结构数据
 * 早就不下发给客户端），所以没法在客户端按种子重算结构。SeedcrackerX 自带 seedfinding
 * 数学库，它算完会把结构框放进 {@code FinderControl}，我们就从那儿读。</p>
 *
 * <p>全部走反射：它自己的类名/方法名是稳定的（不像 Minecraft 会被 remap），
 * 但 MC 类型会变成 intermediary，跟我们这边一致，所以 {@code Cuboid#getCenterPos()}
 * 拿到的就是 {@link BlockPos}。任何一步失败都只是"读不到"，不会拖垮游戏。</p>
 */
public final class SeedcrackerX {
    private static final String MOD_ID = "seedcrackerx";
    private static final int MAX_RESULTS = 12;

    public record Found(String kind, BlockPos center) {
    }

    private static boolean resolved;
    private static boolean functional;

    private static Method seedCrackerGet;
    private static Method getDataStorage;
    private static Method getTimeMachine;
    private static Field worldSeeds;
    private static Method finderQueueGet;
    private static Field finderControl;
    private static Method getActiveFinders;
    private static Field cuboids;
    private static Method cuboidCenter;

    private SeedcrackerX() {
    }

    public static boolean available() {
        return FabricLoader.getInstance().isModLoaded(MOD_ID);
    }

    public static boolean functional() {
        ensure();
        return functional;
    }

    private static synchronized void ensure() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            Class<?> cracker = Class.forName("kaptainwutax.seedcrackerX.SeedCracker");
            seedCrackerGet = cracker.getMethod("get");
            Class<?> storage = Class.forName("kaptainwutax.seedcrackerX.cracker.storage.DataStorage");
            getDataStorage = cracker.getMethod("getDataStorage");
            Class<?> timeMachine = Class.forName("kaptainwutax.seedcrackerX.cracker.storage.TimeMachine");
            getTimeMachine = storage.getMethod("getTimeMachine");
            worldSeeds = timeMachine.getField("worldSeeds");

            Class<?> queue = Class.forName("kaptainwutax.seedcrackerX.finder.FinderQueue");
            finderQueueGet = queue.getMethod("get");
            finderControl = queue.getField("finderControl");
            Class<?> control = Class.forName("kaptainwutax.seedcrackerX.finder.FinderControl");
            getActiveFinders = control.getMethod("getActiveFinders");
            Class<?> finder = Class.forName("kaptainwutax.seedcrackerX.finder.Finder");
            cuboids = finder.getDeclaredField("cuboids");
            cuboids.setAccessible(true);
            Class<?> cuboid = Class.forName("kaptainwutax.seedcrackerX.render.Cuboid");
            cuboidCenter = cuboid.getMethod("getCenterPos");
            functional = true;
            DeepseekCompanionClient.LOGGER.info("[XiaoD] SeedcrackerX bridge ready");
        } catch (Throwable error) {
            functional = false;
            DeepseekCompanionClient.LOGGER.warn("[XiaoD] SeedcrackerX present but bridge failed: {}", error.toString());
        }
    }

    /** 破解出来的世界种子；还没收敛或没有就返回 null。 */
    public static Long seed() {
        if (!available() || !functional()) {
            return null;
        }
        try {
            Object cracker = seedCrackerGet.invoke(null);
            Object storage = getDataStorage.invoke(cracker);
            Object timeMachine = getTimeMachine.invoke(storage);
            Object value = worldSeeds.get(timeMachine);
            if (value instanceof Set<?> set) {
                if (set.size() == 1) {
                    Object only = set.iterator().next();
                    return only instanceof Long longSeed ? longSeed : null;
                }
                return null;
            }
        } catch (Throwable error) {
            DeepseekCompanionClient.LOGGER.debug("[XiaoD] cannot read SeedcrackerX seed", error);
        }
        return null;
    }

    /** 种子候选数量（>1 说明还在收敛）。 */
    public static int seedCandidates() {
        if (!available() || !functional()) {
            return 0;
        }
        try {
            Object cracker = seedCrackerGet.invoke(null);
            Object storage = getDataStorage.invoke(cracker);
            Object timeMachine = getTimeMachine.invoke(storage);
            Object value = worldSeeds.get(timeMachine);
            return value instanceof Set<?> set ? set.size() : 0;
        } catch (Throwable error) {
            return 0;
        }
    }

    /** SeedcrackerX 已经定位出来的结构（只覆盖它扫过的区块）。 */
    public static List<Found> structures(MinecraftClient mc, int maxDistance) {
        List<Found> found = new ArrayList<>();
        if (!available() || !functional() || mc.player == null || mc.world == null) {
            return found;
        }
        try {
            Object queue = finderQueueGet.invoke(null);
            Object control = finderControl.get(queue);
            Object value = getActiveFinders.invoke(control);
            if (!(value instanceof List<?> finders)) {
                return found;
            }
            int limit = maxDistance <= 0 ? 200 : maxDistance;
            long limitSq = (long) limit * limit;
            BlockPos origin = mc.player.getBlockPos();
            for (Object finder : finders) {
                Object boxes = cuboids.get(finder);
                if (!(boxes instanceof List<?> list) || list.isEmpty()) {
                    continue;
                }
                String kind = kindOf(finder.getClass().getSimpleName());
                for (Object box : list) {
                    Object center = cuboidCenter.invoke(box);
                    if (!(center instanceof BlockPos pos)) {
                        continue;
                    }
                    if (origin.getSquaredDistance(pos) > limitSq) {
                        continue;
                    }
                    found.add(new Found(kind, pos.toImmutable()));
                }
            }
        } catch (Throwable error) {
            DeepseekCompanionClient.LOGGER.debug("[XiaoD] cannot read SeedcrackerX finders", error);
        }
        BlockPos origin = mc.player == null ? BlockPos.ORIGIN : mc.player.getBlockPos();
        found.sort(Comparator.comparingDouble(f -> origin.getSquaredDistance(f.center())));
        return found.size() > MAX_RESULTS ? new ArrayList<>(found.subList(0, MAX_RESULTS)) : found;
    }

    public static String seedReport() {
        if (!available()) {
            return "没装 SeedcrackerX";
        }
        if (!functional()) {
            return "SeedcrackerX 在，但读不到它的数据（版本不兼容？）";
        }
        Long seed = seed();
        if (seed != null) {
            return "种子 " + seed + "（SeedcrackerX 已破解）";
        }
        int candidates = seedCandidates();
        if (candidates > 1) {
            return "种子还没收敛（还有 " + candidates + " 个候选）";
        }
        return "种子还没破解出来（让它多收集点证据）";
    }

    public static String report(MinecraftClient mc, int maxDistance) {
        if (!available()) {
            return "没装 SeedcrackerX，定位不了结构";
        }
        if (!functional()) {
            return "SeedcrackerX 在，但读不到它的数据（版本不兼容？）";
        }
        if (mc.player == null || mc.world == null) {
            return "还没进世界";
        }
        int limit = maxDistance <= 0 ? 200 : maxDistance;
        List<Found> list = structures(mc, limit);
        StringBuilder sb = new StringBuilder();
        sb.append(seedReport());
        if (list.isEmpty()) {
            sb.append("；").append(limit).append(" 格内没有它定位出的结构")
                    .append("（它只在你走过/加载过的区块里找，先在游戏里让它跑一会儿）");
            return sb.toString();
        }
        BlockPos origin = mc.player.getBlockPos();
        sb.append("；").append(limit).append(" 格内：");
        for (Found item : list) {
            sb.append('\n').append(item.kind()).append(" (")
                    .append(item.center().getX()).append(", ")
                    .append(item.center().getY()).append(", ")
                    .append(item.center().getZ()).append(") 距离 ")
                    .append(Math.round(Math.sqrt(origin.getSquaredDistance(item.center())))).append(" 格");
        }
        return sb.toString();
    }

    /** 类名 -> 人类可读的类型（SeedcrackerX 的 finder 类名是稳定的）。 */
    private static String kindOf(String simpleName) {
        String base = simpleName.endsWith("Finder")
                ? simpleName.substring(0, simpleName.length() - "Finder".length())
                : simpleName;
        return switch (base) {
            case "Village" -> "村庄";
            case "Monument" -> "海底神殿";
            case "Mansion" -> "林地府邸";
            case "Outpost" -> "掠夺者前哨";
            case "Shipwreck" -> "沉船";
            case "BuriedTreasure" -> "埋藏的宝藏";
            case "DesertPyramid" -> "沙漠神殿";
            case "JunglePyramid" -> "丛林神庙";
            case "Igloo" -> "雪屋";
            case "WitchHut" -> "女巫小屋";
            case "EndCity" -> "末地城";
            case "TrialChambers" -> "试炼密室";
            case "Stronghold" -> "要塞";
            case "DesertWell" -> "沙漠水井";
            case "Dungeon" -> "地牢";
            case "EndGateway" -> "末地门";
            case "EndPillars" -> "末地柱";
            case "DeepDungeon" -> "深层地牢";
            default -> base;
        };
    }

    /** 给 .xd status 用的一行。 */
    public static String statusLine() {
        if (!available()) {
            return "SeedcrackerX: 未装";
        }
        if (!functional()) {
            return "SeedcrackerX: 已装（读不到数据）";
        }
        Long seed = seed();
        return "SeedcrackerX: 已装，种子 " + (seed == null ? "未破解" : seed.longValue());
    }

    /** 类型名小写比较，给 arg 过滤用（预留）。 */
    public static boolean matches(Found found, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        String needle = filter.strip().toLowerCase(Locale.ROOT);
        return found.kind().toLowerCase(Locale.ROOT).contains(needle)
                || found.kind().equals(filter.strip());
    }
}
