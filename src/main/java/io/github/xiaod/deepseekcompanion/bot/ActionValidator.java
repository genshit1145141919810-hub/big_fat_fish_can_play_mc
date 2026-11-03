package io.github.xiaod.deepseekcompanion.bot;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Hard validation for model output. Every argument is checked against the real
 * game registry, so a hallucinated block or item name can never be turned into
 * a command string.
 */
public final class ActionValidator {
    private static final Set<String> FORBIDDEN_BLOCKS = Set.of(
            "bedrock", "barrier", "command_block", "chain_command_block", "repeating_command_block",
            "structure_block", "structure_void", "jigsaw", "light", "end_portal", "end_gateway",
            "nether_portal", "moving_piston"
    );
    private static final int MAX_BLOCKS = 6;
    private static final int MAX_SAY_LENGTH = 120;

    private ActionValidator() {
    }

    public static final class FormatException extends Exception {
        public FormatException(String message) {
            super(message);
        }
    }

    public static String validateBlocks(String arg) throws FormatException {
        if (arg == null || arg.isBlank()) {
            throw new FormatException("挖矿动作必须给出方块 ID，例如 iron_ore");
        }
        List<String> canonical = new ArrayList<>();
        for (String raw : arg.split(",")) {
            String name = normalize(raw);
            if (!name.matches("[a-z0-9_]{1,48}")) {
                throw new FormatException("方块名不合法: " + raw);
            }
            if (FORBIDDEN_BLOCKS.contains(name)) {
                throw new FormatException("这个方块不能挖: " + name);
            }
            Block block = Registries.BLOCK.get(Identifier.of("minecraft", name));
            if (block == net.minecraft.block.Blocks.AIR) {
                throw new FormatException("不存在的方块: " + name);
            }
            if (!canonical.contains(name)) {
                canonical.add(name);
            }
            if (canonical.size() >= MAX_BLOCKS) {
                break;
            }
        }
        if (canonical.isEmpty()) {
            throw new FormatException("方块列表为空");
        }
        return String.join(",", canonical);
    }

    /** Accepts "100 64 -200" and returns the canonical form. */
    public static String validatePosition(String arg) throws FormatException {
        if (arg == null || arg.isBlank()) {
            throw new FormatException("需要坐标，例如 100 64 -200");
        }
        String[] parts = arg.strip().split("[\\s,]+");
        if (parts.length != 3) {
            throw new FormatException("坐标要三个数字");
        }
        int x = parseInt(parts[0], "x");
        int y = parseInt(parts[1], "y");
        int z = parseInt(parts[2], "z");
        if (y < -128 || y > 384) {
            throw new FormatException("那个高度去不了");
        }
        return x + " " + y + " " + z;
    }

    private static int parseInt(String raw, String axis) throws FormatException {
        try {
            int value = Integer.parseInt(raw.trim());
            if (Math.abs(value) > 30_000_000) {
                throw new FormatException(axis + " 超出范围");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new FormatException(axis + " 不是整数: " + raw);
        }
    }

    /** Accepts "diamond_pickaxe" or "minecraft:diamond_pickaxe". */
    public static String validateItem(String arg) throws FormatException {
        if (arg == null || arg.isBlank()) {
            throw new FormatException("需要物品 ID，例如 diamond_pickaxe");
        }
        String name = normalize(arg);
        if (!name.matches("[a-z0-9_]{1,64}")) {
            throw new FormatException("物品名不合法: " + arg);
        }
        Item item = Registries.ITEM.get(Identifier.of("minecraft", name));
        if (item == net.minecraft.item.Items.AIR) {
            throw new FormatException("不存在的物品: " + name);
        }
        return name;
    }

    /** Optional animal id for hunting; empty means "any food animal nearby". */
    public static String validateEntity(String arg) throws FormatException {
        if (arg == null || arg.isBlank()) {
            return "";
        }
        String name = normalize(arg);
        if (!name.matches("[a-z0-9_]{1,48}")) {
            throw new FormatException("生物名不合法: " + arg);
        }
        if (!Registries.ENTITY_TYPE.containsId(Identifier.of("minecraft", name))) {
            throw new FormatException("不存在的生物: " + name);
        }
        return name;
    }

    /** Optional comma list of item ids, used by store/take. */
    public static String validateItems(String arg) throws FormatException {
        if (arg == null || arg.isBlank()) {
            return "";
        }
        List<String> ids = new ArrayList<>();
        for (String raw : arg.split(",")) {
            String name = normalize(raw);
            if (!name.matches("[a-z0-9_]{1,64}")) {
                throw new FormatException("物品名不合法: " + raw);
            }
            Item item = Registries.ITEM.get(Identifier.of("minecraft", name));
            if (item == net.minecraft.item.Items.AIR) {
                throw new FormatException("不存在的物品: " + name);
            }
            if (!ids.contains(name)) {
                ids.add(name);
            }
            if (ids.size() >= 8) {
                break;
            }
        }
        return String.join(",", ids);
    }

    /** Loose free text used by the station action (station name plus item ids). */
    public static String validateString(String arg) throws FormatException {
        if (arg == null || arg.isBlank()) {
            throw new FormatException("这条指令需要一个参数");
        }
        String text = arg.strip();
        if (!text.matches("[A-Za-z0-9_:, \\-]{1,128}")) {
            throw new FormatException("参数里有非法字符");
        }
        return text;
    }

    /**
     * 投影名/文件名这类自由文本：可以为空（=列出全部），允许中文与空格。
     * 只用于纯本地查表，不会拼进任何命令，所以宽松是安全的。
     */
    public static String validateName(String arg) {
        if (arg == null) {
            return "";
        }
        String text = arg.replaceAll("[\\r\\n\\t]+", " ").strip();
        return text.length() > 128 ? text.substring(0, 128) : text;
    }

    /**
     * 按动作声明的参数类型统一校验。单步决策（Intent）和计划器（Plan）共用这一份，
     * 所以"计划里塞了不存在的方块/物品"同样在校验层就被挡掉。
     */
    public static String validateArg(BotAction action, String argRaw) throws FormatException {
        if (action == null) {
            return "";
        }
        return switch (action.argKind()) {
            case BLOCKS -> validateBlocks(argRaw);
            case POS -> validatePosition(argRaw);
            case ITEM -> validateItem(argRaw);
            case ITEMS -> validateItems(argRaw);
            case ENTITY -> validateEntity(argRaw);
            case STRING -> action == BotAction.SCHEMATIC || action == BotAction.STRUCTURES
                    || action == BotAction.PORTAL
                    ? validateName(argRaw)
                    : validateString(argRaw);
            case NONE -> "";
        };
    }

    private static String normalize(String raw) {
        String name = raw.strip().toLowerCase(Locale.ROOT);
        return name.startsWith("minecraft:") ? name.substring("minecraft:".length()) : name;
    }

    public static String sanitizeSay(String say) {
        if (say == null) {
            return "";
        }
        String text = say.replaceAll("[\\r\\n\\t]+", " ").strip();
        if (text.length() > MAX_SAY_LENGTH) {
            text = text.substring(0, MAX_SAY_LENGTH);
        }
        return text;
    }

    public static boolean isSafePlayerName(String name) {
        return name != null && name.matches("[A-Za-z0-9_]{1,16}");
    }

    public static boolean isSafeEntityId(String name) {
        if (name == null || !name.matches("[a-z0-9_]{1,48}")) {
            return false;
        }
        return Registries.ENTITY_TYPE.containsId(Identifier.of("minecraft", name));
    }
}
