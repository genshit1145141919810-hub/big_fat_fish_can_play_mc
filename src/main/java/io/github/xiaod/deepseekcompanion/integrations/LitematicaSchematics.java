package io.github.xiaod.deepseekcompanion.integrations;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import io.github.xiaod.deepseekcompanion.config.CompanionConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 读 Litematica 的 {@code .litematic} 投影（gzip + NBT，不需要 Litematica 的 API，
 * 所以 Litematica 没装也能用，这是文件格式级联动）。
 *
 * <p>格式要点（用真实文件逐项验证过）：</p>
 * <ul>
 *   <li>{@code Regions.<名字>.BlockStatePalette}：方块状态调色板，每项 {@code {Name, Properties}}；</li>
 *   <li>{@code Regions.<名字>.BlockStates}：long 数组，按<b>连续位流</b>打包，
 *       第 idx 格占 {@code idx*bits} 起的 bits 位，<b>会跨 long 边界</b>；</li>
 *   <li>{@code bits} 不写在文件里，用 {@code ceil(体积*bits/64) == long 数量} 反解；</li>
 *   <li>越界/全 1 的下标是"该格不属于本区域"的占位，直接跳过。</li>
 * </ul>
 */
public final class LitematicaSchematics {
    private static final int MAX_LIST = 20;
    private static final int MAX_MATERIALS = 12;

    public record Schematic(String name, int sizeX, int sizeY, int sizeZ, int regions,
                            Map<String, Integer> blocks, int skipped) {
        public int total() {
            int sum = 0;
            for (int value : blocks.values()) {
                sum += value;
            }
            return sum;
        }
    }

    private LitematicaSchematics() {
    }

    public static boolean modLoaded() {
        return FabricLoader.getInstance().isModLoaded("litematica");
    }

    /** Litematica 默认读 {gameDir}/schematics，可在配置里用 schematicDir 覆盖。 */
    public static Path dir() {
        CompanionConfig cfg = CompanionConfig.get();
        if (cfg.schematicDir != null && !cfg.schematicDir.isBlank()) {
            return Path.of(cfg.schematicDir.strip());
        }
        return FabricLoader.getInstance().getGameDir().resolve("schematics");
    }

    public static List<Path> files() {
        Path root = dir();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(root)) {
            return stream
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".litematic"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
        } catch (IOException e) {
            DeepseekCompanionClient.LOGGER.warn("[XiaoD] cannot list schematics in {}", root, e);
            return List.of();
        }
    }

    // ------------------------------------------------------------------ parse

    public static Schematic read(Path file) {
        NbtCompound root;
        try {
            root = NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes());
        } catch (Exception e) {
            DeepseekCompanionClient.LOGGER.warn("[XiaoD] bad schematic {}", file, e);
            return null;
        }
        NbtCompound regions = root.getCompoundOrEmpty("Regions");
        Map<String, Integer> counts = new HashMap<>();
        int skipped = 0;
        int regionsSeen = 0;
        for (String regionName : regions.getKeys()) {
            NbtCompound region = regions.getCompoundOrEmpty(regionName);
            if (region.getKeys().isEmpty()) {
                continue;
            }
            NbtCompound size = region.getCompoundOrEmpty("Size");
            int sx = Math.abs(size.getInt("x", 0));
            int sy = Math.abs(size.getInt("y", 0));
            int sz = Math.abs(size.getInt("z", 0));
            int volume = sx * sy * sz;
            if (volume <= 0) {
                continue;
            }
            regionsSeen++;
            long[] states = region.getLongArray("BlockStates").orElse(null);
            NbtList paletteList = region.getListOrEmpty("BlockStatePalette");
            if (states == null || states.length == 0 || paletteList.size() == 0) {
                skipped += volume;
                continue;
            }
            String[] palette = new String[paletteList.size()];
            for (int i = 0; i < palette.length; i++) {
                palette[i] = paletteList.getCompound(i).flatMap(b -> b.getString("Name")).orElse(null);
            }
            int bits = solveBits(volume, states.length, palette.length);
            if (bits == 0) {
                DeepseekCompanionClient.LOGGER.warn("[XiaoD] cannot solve bitsPerEntry in {}", regionName);
                skipped += volume;
                continue;
            }
            skipped += countRegion(states, palette, volume, bits, counts);
        }
        String name = file.getFileName().toString();
        if (name.toLowerCase(Locale.ROOT).endsWith(".litematic")) {
            name = name.substring(0, name.length() - ".litematic".length());
        }
        // 尺寸优先用元数据里的 EnclosingSize（多区域时是整体外框）
        int sx = 0;
        int sy = 0;
        int sz = 0;
        NbtCompound meta = root.getCompoundOrEmpty("Metadata");
        NbtCompound enclosing = meta.getCompoundOrEmpty("EnclosingSize");
        if (!enclosing.getKeys().isEmpty()) {
            sx = Math.abs(enclosing.getInt("x", 0));
            sy = Math.abs(enclosing.getInt("y", 0));
            sz = Math.abs(enclosing.getInt("z", 0));
        } else {
            for (String regionName : regions.getKeys()) {
                NbtCompound size = regions.getCompoundOrEmpty(regionName).getCompoundOrEmpty("Size");
                sx = Math.max(sx, Math.abs(size.getInt("x", 0)));
                sy = Math.max(sy, Math.abs(size.getInt("y", 0)));
                sz = Math.max(sz, Math.abs(size.getInt("z", 0)));
            }
        }
        return new Schematic(name, sx, sy, sz, regionsSeen, counts, skipped);
    }

    /**
     * litematica 不写 bitsPerEntry，但 long 数量是确定的：位数连续打包时
     * {@code longs == ceil(volume*bits/64)}。从"足以装下调色板"的位数开始试。
     */
    private static int solveBits(int volume, int longs, int paletteSize) {
        int needed = 1;
        while ((1L << needed) < Math.max(2, paletteSize)) {
            needed++;
        }
        for (int bits = Math.min(needed, 32); bits <= 32; bits++) {
            if (((long) volume * bits + 63L) / 64L == longs) {
                return bits;
            }
        }
        for (int bits = 1; bits <= 32; bits++) {
            if (((long) volume * bits + 63L) / 64L == longs) {
                return bits;
            }
        }
        return 0;
    }

    /** 返回被跳过的格子数（越界下标 = 该格不属于本区域）。 */
    private static int countRegion(long[] states, String[] palette, int volume, int bits,
                                   Map<String, Integer> counts) {
        long mask = (1L << bits) - 1L;
        int skipped = 0;
        for (int index = 0; index < volume; index++) {
            long bitIndex = (long) index * bits;
            int longIndex = (int) (bitIndex >>> 6);
            int offset = (int) (bitIndex & 63L);
            if (longIndex >= states.length) {
                skipped++;
                continue;
            }
            long raw = states[longIndex] >>> offset;
            if (offset + bits > 64) {
                if (longIndex + 1 < states.length) {
                    raw |= states[longIndex + 1] << (64 - offset);
                }
            }
            int value = (int) (raw & mask);
            if (value < 0 || value >= palette.length || palette[value] == null) {
                skipped++;
                continue;
            }
            String blockId = palette[value];
            if (isAir(blockId)) {
                continue;
            }
            counts.merge(blockId, 1, Integer::sum);
        }
        return skipped;
    }

    private static boolean isAir(String blockId) {
        return blockId.endsWith(":air") || blockId.equals("air")
                || blockId.endsWith("cave_air") || blockId.endsWith("void_air");
    }

    // ---------------------------------------------------------------- report

    /** 查询入口：空参数列全部投影，否则按名字/序号出材料清单。 */
    public static String query(MinecraftClient mc, String arg) {
        List<Path> all = files();
        if (all.isEmpty()) {
            return "没有投影文件（" + dir() + " 里没有 .litematic）";
        }
        String needle = arg == null ? "" : arg.strip();
        if (needle.isEmpty()) {
            StringBuilder sb = new StringBuilder("投影 " + all.size() + " 个：");
            int shown = 0;
            for (Path path : all) {
                if (shown >= MAX_LIST) {
                    sb.append("…");
                    break;
                }
                if (shown > 0) {
                    sb.append('、');
                }
                sb.append(stripExtension(path.getFileName().toString()));
                shown++;
            }
            return sb.toString();
        }
        Path file = resolve(all, needle);
        if (file == null) {
            return "找不到投影「" + needle + "」";
        }
        Schematic schematic = read(file);
        if (schematic == null) {
            return "投影「" + needle + "」读不出来（文件坏了？）";
        }
        return describe(mc, schematic);
    }

    public static Path resolve(List<Path> all, String needle) {
        String lower = needle.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".litematic")) {
            lower = lower.substring(0, lower.length() - ".litematic".length());
        }
        for (Path path : all) {
            if (stripExtension(path.getFileName().toString()).toLowerCase(Locale.ROOT).equals(lower)) {
                return path;
            }
        }
        for (Path path : all) {
            if (stripExtension(path.getFileName().toString()).toLowerCase(Locale.ROOT).contains(lower)) {
                return path;
            }
        }
        try {
            int index = Integer.parseInt(lower);
            if (index >= 1 && index <= all.size()) {
                return all.get(index - 1);
            }
        } catch (NumberFormatException ignored) {
            // not an index
        }
        return null;
    }

    public static String describe(MinecraftClient mc, Schematic schematic) {
        List<Map.Entry<String, Integer>> materials = new ArrayList<>(schematic.blocks().entrySet());
        materials.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        StringBuilder sb = new StringBuilder();
        sb.append(schematic.name()).append(' ')
                .append(schematic.sizeX()).append('x').append(schematic.sizeY()).append('x')
                .append(schematic.sizeZ()).append("，方块 ").append(schematic.total());
        if (schematic.regions() > 1) {
            sb.append("（").append(schematic.regions()).append(" 个区域）");
        }
        if (schematic.skipped() > 0) {
            sb.append("，跳过 ").append(schematic.skipped()).append(" 格");
        }

        int shown = 0;
        int missingTotal = 0;
        int missingKinds = 0;
        int unmapped = 0;
        for (Map.Entry<String, Integer> entry : materials) {
            int need = entry.getValue();
            Item item = itemOf(entry.getKey());
            if (item == null) {
                unmapped++;
                continue;
            }
            int have = countInInventory(mc, item);
            if (need > have) {
                missingTotal += need - have;
                missingKinds++;
            }
            if (shown < MAX_MATERIALS) {
                sb.append('\n').append(shortName(entry.getKey())).append(" 需").append(need).append(" 有").append(have);
                if (need > have) {
                    sb.append(" §c缺").append(need - have);
                }
                shown++;
            }
        }
        if (unmapped > 0) {
            sb.append('\n').append("另有 ").append(unmapped).append(" 种方块当前没有对应物品（模组方块/已移除）");
        }
        if (missingKinds > 0) {
            sb.append('\n').append("缺口合计 ").append(missingTotal).append(" 个，涉及 ").append(missingKinds).append(" 种");
        } else if (shown > 0) {
            sb.append('\n').append("背包材料够了");
        }
        return sb.toString();
    }

    /** 一次材料清单的完整文本（含前 12 种），给 {@code .xd schematic} 用。 */
    public static String report(MinecraftClient mc, String arg) {
        return query(mc, arg);
    }

    public static String listing() {
        List<Path> all = files();
        if (all.isEmpty()) {
            return "没有投影文件（目录 " + dir() + " 不存在或为空）";
        }
        List<String> lines = new ArrayList<>();
        lines.add("投影目录: " + dir());
        int index = 1;
        for (Path path : all) {
            if (index > MAX_LIST) {
                lines.add("…还有 " + (all.size() - MAX_LIST) + " 个");
                break;
            }
            long bytes = 0;
            try {
                bytes = Files.size(path);
            } catch (IOException ignored) {
                // size is cosmetic
            }
            lines.add(index + ". " + stripExtension(path.getFileName().toString())
                    + "（" + Math.max(1, bytes / 1024) + " KB）");
            index++;
        }
        return String.join("\n", lines);
    }

    private static Item itemOf(String blockId) {
        try {
            Identifier id = Identifier.tryParse(blockId);
            if (id == null) {
                return null;
            }
            Block block = Registries.BLOCK.getOptionalValue(id).orElse(null);
            if (block == null) {
                return null;
            }
            Item item = block.asItem();
            return item == Items.AIR ? null : item;
        } catch (Exception e) {
            return null;
        }
    }

    private static int countInInventory(MinecraftClient mc, Item item) {
        ClientPlayerEntity player = mc.player;
        if (player == null || item == Items.AIR) {
            return 0;
        }
        int total = 0;
        for (ItemStack stack : player.getInventory().getMainStacks()) {
            if (stack.isOf(item)) {
                total += stack.getCount();
            }
        }
        if (player.getOffHandStack().isOf(item)) {
            total += player.getOffHandStack().getCount();
        }
        return total;
    }

    private static String stripExtension(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".litematic")
                ? name.substring(0, name.length() - ".litematic".length())
                : name;
    }

    private static String shortName(String blockId) {
        int colon = blockId.indexOf(':');
        return colon < 0 ? blockId : blockId.substring(colon + 1);
    }

    /** 材料名 -> 需要数量，按数量降序，供其它模块复用。 */
    public static Map<String, Integer> materialsByCount(Schematic schematic) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(schematic.blocks().entrySet());
        list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        Map<String, Integer> sorted = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : list) {
            sorted.put(entry.getKey(), entry.getValue());
        }
        return sorted;
    }
}
