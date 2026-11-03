package io.github.xiaod.deepseekcompanion.integrations;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 辅助类模组的登记表。
 *
 * <p>这里不硬编码"装了哪些模组"，而是每次启动从 FabricLoader 读真实加载列表，
 * 再按 id 打上能力标签。两个用途：</p>
 * <ul>
 *   <li>{@link #summary()} —— 塞进给模型的实时状态里，让它知道当前环境有什么可用；</li>
 *   <li>{@link #listing()} —— {@code .xd mods} 打印人类可读的联动清单。</li>
 * </ul>
 *
 * <p>{@link Stage#ACTIVE} 表示本模组真的会读/适配它（比如 Baritone 的寻路、
 * Xaero 的航点、Litematica 的投影）；{@link Stage#AWARE} 只代表"我们知道有它"，
 * 会写进模型上下文，但不会去调它的 API。</p>
 */
public final class ModLinks {
    /** ACTIVE = 真读了它；AWARE = 只是让模型知道环境。 */
    public enum Stage {
        ACTIVE, AWARE
    }

    public record Link(String id, String label, Stage stage, String note) {
    }

    /** 辅助类模组：能力相关，会被模型看到。 */
    private static final List<Link> HELPER = List.of(
            new Link("baritone", "Baritone", Stage.ACTIVE, "寻路/挖掘"),
            new Link("xaerominimap", "Xaero小地图", Stage.ACTIVE, "航点"),
            new Link("xaeroworldmap", "Xaero世界地图", Stage.ACTIVE, "航点"),
            new Link("litematica", "Litematica投影", Stage.ACTIVE, "投影材料清单"),
            new Link("seedcrackerx", "SeedcrackerX", Stage.ACTIVE, "种子/结构定位"),
            new Link("jei", "JEI", Stage.AWARE, "物品/配方查询"),
            new Link("jade", "Jade", Stage.AWARE, "方块与生物信息"),
            new Link("wthit", "WTHIT", Stage.AWARE, "方块与生物信息"),
            new Link("modmenu", "ModMenu", Stage.AWARE, "模组菜单（配置入口）"),
            new Link("veinminer", "VeinMiner", Stage.AWARE, "连锁挖矿：挖一个可能连带整条脉"),
            new Link("inventorytotem", "InventoryTotem", Stage.AWARE, "背包图腾会自动生效"),
            new Link("taxfreelevels", "TaxFreeLevels", Stage.AWARE, "附魔/铁砧花费更宽松"),
            new Link("betterthanmending", "BetterThanMending", Stage.AWARE, "经验修补更好用"),
            new Link("attributefix", "AttributeFix", Stage.AWARE, "属性上限被放宽"),
            new Link("appleskin", "AppleSkin", Stage.AWARE, "饥饿/饱和度显示"),
            new Link("chunky", "Chunky", Stage.AWARE, "区块预生成"),
            new Link("bobby", "Bobby", Stage.AWARE, "服务器区块缓存"),
            new Link("sereneseasons", "SereneSeasons", Stage.AWARE, "有季节，作物与天气会变"),
            new Link("carpet", "Carpet", Stage.AWARE, "服务端规则可能改过原版行为"),
            new Link("nochatreports", "NoChatReports", Stage.AWARE, "聊天不签名"),
            new Link("essential", "Essential", Stage.AWARE, "好友联机")
    );

    /** 前置库/性能/视觉：只登记数量，不打扰模型。 */
    private static final Set<String> QUIET = Set.of(
            "fabric-api", "fabricloader", "java", "minecraft",
            "fabric-language-kotlin", "kotlinforforge", "architectury", "cardinal-components-api",
            "cloth-config", "yet_another_config_lib_v3", "collective", "supermartijn642corelib",
            "glitchcore", "puzzleslib", "toadlib", "cicada-lib", "forgeconfigapiport",
            "malilib", "placeholder-api", "silk-all", "prickle", "attributefix-lib",
            "sodium", "sodium-extra", "lithium", "ferritecore", "modernfix", "gpubooster",
            "dynamic-fps", "preloading-tricks", "iris", "sodium-shadowy-path-blocks",
            "indium", "krypton", "memoryleakfix", "immediatelyfast", "entityculling",
            "firstperson", "skinlayers3d", "detailarmorbar", "detailarmorbarreconstructed",
            "elytratrims", "do_a_barrel_roll", "betterf3", "betteradvancements",
            "gamma-utils", "visualworkbench", "healingcampfire", "additionallanterns",
            "deepseekcompanion"
    );

    private ModLinks() {
    }

    public static boolean has(String id) {
        return FabricLoader.getInstance().isModLoaded(id);
    }

    public static String versionOf(String id) {
        return FabricLoader.getInstance().getModContainer(id)
                .map(ModContainer::getMetadata)
                .map(ModMetadata::getVersion)
                .map(version -> version.getFriendlyString())
                .orElse("");
    }

    public static List<Link> loadedHelpers() {
        List<Link> list = new ArrayList<>();
        for (Link link : HELPER) {
            if (has(link.id())) {
                list.add(link);
            }
        }
        return list;
    }

    public static boolean anyActive() {
        for (Link link : loadedHelpers()) {
            if (link.stage() == Stage.ACTIVE) {
                return true;
            }
        }
        return false;
    }

    /** 给模型的紧凑一行：只写会影响判断的那些。 */
    public static String summary() {
        StringBuilder sb = new StringBuilder();
        for (Link link : loadedHelpers()) {
            if (link.stage() != Stage.ACTIVE && link.stage() != Stage.AWARE) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(link.label());
            if (link.stage() == Stage.ACTIVE) {
                sb.append('√');
            }
            sb.append('(').append(link.note()).append(')');
            if (sb.length() > 220) {
                break;
            }
        }
        return sb.toString();
    }

    /** {@code .xd mods} 的输出。 */
    public static List<String> listing() {
        List<String> lines = new ArrayList<>();
        List<Link> active = new ArrayList<>();
        List<Link> aware = new ArrayList<>();
        List<Link> other = new ArrayList<>();
        for (Link link : loadedHelpers()) {
            (link.stage() == Stage.ACTIVE ? active : aware).add(link);
        }
        List<String> unknown = new ArrayList<>();
        for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
            String id = container.getMetadata().getId();
            if (QUIET.contains(id.toLowerCase(Locale.ROOT))) {
                continue;
            }
            boolean known = false;
            for (Link link : HELPER) {
                if (link.id().equals(id)) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                unknown.add(container.getMetadata().getName() + "(" + id + ")");
            }
        }
        unknown.sort(Comparator.naturalOrder());

        lines.add("§b已联动（会真的读它）：");
        for (Link link : active) {
            lines.add("§7- §f" + link.label() + " " + versionOf(link.id()) + " §7" + link.note());
        }
        if (active.isEmpty()) {
            lines.add("§8  （没有：Baritone / Xaero / Litematica 都没装）");
        }
        lines.add("§b感知（会告诉模型，但不调它的 API）：");
        for (Link link : aware) {
            lines.add("§7- §f" + link.label() + " " + versionOf(link.id()) + " §7" + link.note());
        }
        if (aware.isEmpty()) {
            lines.add("§8  （没有）");
        }
        if (!unknown.isEmpty()) {
            int shown = Math.min(unknown.size(), 14);
            lines.add("§b其他模组（" + unknown.size() + "）：§7"
                    + String.join("、", unknown.subList(0, shown))
                    + (unknown.size() > shown ? " …" : ""));
        }
        return lines;
    }

    /** 只留下非库、非性能的模组 id，便于将来加联动时一眼看到候选。 */
    public static Set<String> candidateIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
            String id = container.getMetadata().getId().toLowerCase(Locale.ROOT);
            if (!QUIET.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }
}
