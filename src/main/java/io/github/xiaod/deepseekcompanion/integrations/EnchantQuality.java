package io.github.xiaod.deepseekcompanion.integrations;

import java.util.HashMap;
import java.util.Map;

/**
 * Enchantment quality from the real numbers in the wiki:
 *
 * <ul>
 *   <li>every enchantment level only appears when the modified enchantment level
 *       c' sits inside a known [min, max] band, so the band tells us how strong
 *       the roll was;</li>
 *   <li>the pick weight decides rarity (silk touch / mending are weight 1-2,
 *       sharpness / protection are weight 10).</li>
 * </ul>
 *
 * Score = band midpoint * (10 / weight): a rare enchantment on a high band wins.
 * The client only exposes the first enchantment of each offer, so multi-enchant
 * stacks cannot be scored in full - this is the best estimate from the data the
 * client actually has.
 */
public final class EnchantQuality {
    /** min/max modified enchantment level per level (index 0 = level I). */
    private record Info(int weight, int[][] bands) {
    }

    private static final Map<String, Info> TABLE = new HashMap<>();

    private static void put(String id, int weight, int[]... bands) {
        TABLE.put(id, new Info(weight, bands));
    }

    static {
        put("aqua_affinity", 2, new int[]{1, 41});
        put("bane_of_arthropods", 5, new int[]{5, 25}, new int[]{13, 33}, new int[]{21, 41}, new int[]{29, 49}, new int[]{37, 57});
        put("binding_curse", 1, new int[]{25, 50});
        put("blast_protection", 2, new int[]{5, 13}, new int[]{13, 21}, new int[]{21, 29}, new int[]{29, 37});
        put("breach", 4, new int[]{15, 65}, new int[]{24, 74}, new int[]{33, 83}, new int[]{42, 92});
        put("channeling", 1, new int[]{25, 50});
        put("density", 5, new int[]{5, 25}, new int[]{13, 33}, new int[]{21, 41}, new int[]{29, 49}, new int[]{37, 57});
        put("depth_strider", 2, new int[]{10, 25}, new int[]{20, 35}, new int[]{30, 45});
        put("efficiency", 10, new int[]{1, 51}, new int[]{11, 61}, new int[]{21, 71}, new int[]{31, 81}, new int[]{41, 91});
        put("feather_falling", 5, new int[]{5, 11}, new int[]{11, 17}, new int[]{17, 23}, new int[]{23, 29});
        put("fire_aspect", 2, new int[]{10, 60}, new int[]{30, 80});
        put("fire_protection", 5, new int[]{10, 18}, new int[]{18, 26}, new int[]{26, 34}, new int[]{34, 42});
        put("flame", 2, new int[]{20, 50});
        put("fortune", 2, new int[]{15, 65}, new int[]{24, 74}, new int[]{33, 83});
        put("frost_walker", 2, new int[]{10, 25}, new int[]{20, 35});
        put("impaling", 5, new int[]{1, 21}, new int[]{9, 29}, new int[]{17, 37}, new int[]{25, 45}, new int[]{33, 53});
        put("infinity", 1, new int[]{20, 50});
        put("knockback", 5, new int[]{5, 55}, new int[]{25, 75});
        put("looting", 2, new int[]{15, 65}, new int[]{24, 74}, new int[]{33, 83});
        put("loyalty", 5, new int[]{12, 50}, new int[]{19, 50}, new int[]{26, 50});
        put("luck_of_the_sea", 2, new int[]{15, 65}, new int[]{24, 74}, new int[]{33, 83});
        put("lure", 2, new int[]{15, 65}, new int[]{24, 74}, new int[]{33, 83});
        put("mending", 2, new int[]{25, 75});
        put("multishot", 2, new int[]{20, 50});
        put("piercing", 10, new int[]{1, 50}, new int[]{11, 50}, new int[]{21, 50}, new int[]{31, 50});
        put("power", 10, new int[]{1, 16}, new int[]{11, 26}, new int[]{21, 36}, new int[]{31, 46}, new int[]{41, 56});
        put("projectile_protection", 5, new int[]{3, 9}, new int[]{9, 15}, new int[]{15, 21}, new int[]{21, 27});
        put("protection", 10, new int[]{1, 12}, new int[]{12, 23}, new int[]{23, 34}, new int[]{34, 45});
        put("punch", 2, new int[]{12, 37}, new int[]{32, 57});
        put("quick_charge", 5, new int[]{12, 50}, new int[]{32, 50}, new int[]{52, 50});
        put("respiration", 2, new int[]{10, 40}, new int[]{20, 50}, new int[]{30, 60});
        put("riptide", 2, new int[]{17, 50}, new int[]{24, 50}, new int[]{31, 50});
        put("sharpness", 10, new int[]{1, 21}, new int[]{12, 32}, new int[]{23, 43}, new int[]{34, 54}, new int[]{45, 65});
        put("silk_touch", 1, new int[]{15, 65});
        put("smite", 5, new int[]{5, 25}, new int[]{13, 33}, new int[]{21, 41}, new int[]{29, 49}, new int[]{37, 57});
        put("soul_speed", 8, new int[]{10, 25}, new int[]{20, 35}, new int[]{30, 45});
        put("sweeping_edge", 2, new int[]{5, 20}, new int[]{14, 29}, new int[]{23, 38});
        put("swift_sneak", 1, new int[]{25, 75}, new int[]{50, 100}, new int[]{75, 125});
        put("thorns", 1, new int[]{10, 60}, new int[]{30, 80}, new int[]{50, 100});
        put("unbreaking", 5, new int[]{5, 55}, new int[]{13, 63}, new int[]{21, 71});
        put("vanishing_curse", 1, new int[]{25, 50});
        put("wind_burst", 2, new int[]{15, 65}, new int[]{24, 74}, new int[]{33, 83});
    }

    private EnchantQuality() {
    }

    /** Higher is better; unknown enchantments fall back to their level. */
    public static double score(String path, int level) {
        Info info = TABLE.get(path);
        if (info == null) {
            return level * 10.0;
        }
        int index = Math.max(0, Math.min(level - 1, info.bands().length - 1));
        int[] band = info.bands()[index];
        double mid = (band[0] + band[1]) / 2.0;
        double rarity = 10.0 / Math.max(1, info.weight());
        return mid * rarity;
    }

    /** How strong the roll is, ignoring rarity (used for tie-breaking). */
    public static double bandMidpoint(String path, int level) {
        Info info = TABLE.get(path);
        if (info == null) {
            return level * 10.0;
        }
        int index = Math.max(0, Math.min(level - 1, info.bands().length - 1));
        int[] band = info.bands()[index];
        return (band[0] + band[1]) / 2.0;
    }

    public static int weight(String path) {
        Info info = TABLE.get(path);
        return info == null ? 10 : info.weight();
    }

    /** Enchantments the table can never offer. */
    private static final java.util.Set<String> NOT_FROM_TABLE = java.util.Set.of(
            "mending", "frost_walker", "binding_curse", "vanishing_curse",
            "soul_speed", "swift_sneak", "wind_burst");

    /** c' band for one (enchantment, level), or null when unknown. */
    public static int[] band(String path, int level) {
        Info info = TABLE.get(path);
        if (info == null) {
            return null;
        }
        int index = Math.max(0, Math.min(level - 1, info.bands().length - 1));
        return info.bands()[index];
    }

    private static int weightedPick(java.util.Random random, java.util.List<String> paths, java.util.List<int[]> weights) {
        int sum = 0;
        for (int[] w : weights) {
            sum += w[0];
        }
        if (sum <= 0) {
            return -1;
        }
        int roll = random.nextInt(sum);
        for (int i = 0; i < weights.size(); i++) {
            roll -= weights.get(i)[0];
            if (roll < 0) {
                return i;
            }
        }
        return weights.size() - 1;
    }

    /**
     * Monte Carlo: given the c' band implied by the observed offer, how much more
     * would this item probably gain? Follows the vanilla loop: keep rolling while
     * randInt(0,49) <= c', then halve c' and repeat.
     */
    public static double simulateExtraQuality(int bandMin, int bandMax, int samples) {
        java.util.List<String> paths = new java.util.ArrayList<>();
        java.util.List<int[]> weights = new java.util.ArrayList<>();
        for (var entry : TABLE.entrySet()) {
            if (NOT_FROM_TABLE.contains(entry.getKey())) {
                continue;
            }
            paths.add(entry.getKey());
            weights.add(new int[]{entry.getValue().weight()});
        }
        if (paths.isEmpty()) {
            return 0.0;
        }
        java.util.Random random = new java.util.Random(1234567L);
        double total = 0.0;
        for (int s = 0; s < samples; s++) {
            int c = bandMin + random.nextInt(Math.max(1, bandMax - bandMin + 1));
            int guard = 0;
            while (guard++ < 32) {
                if (random.nextInt(50) > c) {
                    break;
                }
                int pick = weightedPick(random, paths, weights);
                if (pick >= 0) {
                    String path = paths.get(pick);
                    total += bandMidpoint(path, 1) * (10.0 / Math.max(1, weight(path)));
                }
                c = c / 2;
                if (c <= 0) {
                    break;
                }
            }
        }
        return total / Math.max(1, samples);
    }

    /** Band-midpoint quality plus the Monte Carlo estimate of follow-up enchants. */
    public static double expectedScore(String path, int level) {
        int[] band = band(path, level);
        if (band == null) {
            return level * 10.0;
        }
        double base = (band[0] + band[1]) / 2.0 * (10.0 / Math.max(1, weight(path)));
        return base + simulateExtraQuality(band[0], band[1], 60) * 0.25;
    }}
