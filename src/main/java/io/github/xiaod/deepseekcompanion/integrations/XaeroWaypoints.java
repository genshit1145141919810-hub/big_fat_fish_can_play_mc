package io.github.xiaod.deepseekcompanion.integrations;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads Xaero's Minimap waypoints from disk. Xaero has no stable public API, so
 * the file format (which has been stable for years) is the integration point:
 * every waypoint file is a list of {@code waypoint:name:initials:x:y:z:...}.
 * When Xaero is absent this simply returns an empty list.
 */
public final class XaeroWaypoints {
    private static final int MAX = 12;
    private static final Path[] ROOTS = {
            FabricLoader.getInstance().getGameDir().resolve("XaeroWaypoints"),
            FabricLoader.getInstance().getGameDir().resolve("config").resolve("xaero").resolve("minimap"),
            FabricLoader.getInstance().getGameDir().resolve("XaeroWorldMap"),
    };

    private XaeroWaypoints() {
    }

    public record Point(String name, int x, int y, int z) {
        @Override
        public String toString() {
            return name + "(" + x + ", " + y + ", " + z + ")";
        }
    }

    public static boolean available() {
        return FabricLoader.getInstance().isModLoaded("xaerominimap")
                || FabricLoader.getInstance().isModLoaded("xaeroworldmap");
    }

    public static List<Point> list(MinecraftClient mc) {
        Set<String> seen = new LinkedHashSet<>();
        List<Point> points = new ArrayList<>();
        String dimension = currentDimension(mc);
        for (Path root : ROOTS) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(root, 4)) {
                stream.filter(p -> p.getFileName().toString().equalsIgnoreCase("waypoints.txt"))
                        .forEach(file -> readFile(file, dimension, points, seen));
            } catch (IOException e) {
                DeepseekCompanionClient.LOGGER.debug("[XiaoD] cannot walk {}", root, e);
            }
        }
        if (points.size() > MAX) {
            return points.subList(0, MAX);
        }
        return points;
    }

    private static void readFile(Path file, String dimension, List<Point> points, Set<String> seen) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return;
        }
        for (String line : lines) {
            String trimmed = line.strip();
            if (!trimmed.startsWith("waypoint:")) {
                continue;
            }
            // waypoint:name:initials:x:y:z:color:disabled:type:set:...
            String[] parts = trimmed.split(":", -1);
            if (parts.length < 6) {
                continue;
            }
            try {
                String name = parts[1].isBlank() ? "未命名" : parts[1];
                int x = Integer.parseInt(parts[3].strip());
                int y = Integer.parseInt(parts[4].strip());
                int z = Integer.parseInt(parts[5].strip());
                String key = name + "@" + x + ":" + y + ":" + z;
                if (seen.add(key)) {
                    points.add(new Point(name, x, y, z));
                }
            } catch (NumberFormatException ignored) {
                // not a coordinate line
            }
        }
    }

    private static String currentDimension(MinecraftClient mc) {
        if (mc.world == null) {
            return null;
        }
        String key = mc.world.getRegistryKey().getValue().getPath();
        return switch (key) {
            case "overworld" -> "overworld";
            case "the_nether" -> "nether";
            case "the_end" -> "end";
            default -> key;
        };
    }

    /** Nearest waypoint to the player, if any. */
    public static Point nearest(MinecraftClient mc, List<Point> points) {
        if (points.isEmpty() || mc.player == null) {
            return null;
        }
        BlockPos pos = mc.player.getBlockPos();
        Point best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Point p : points) {
            double d = pos.getSquaredDistance(p.x(), p.y(), p.z());
            if (d < bestDistance) {
                bestDistance = d;
                best = p;
            }
        }
        return best;
    }
}
