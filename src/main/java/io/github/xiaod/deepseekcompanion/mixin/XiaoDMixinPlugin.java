package io.github.xiaod.deepseekcompanion.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Applies the Baritone patch only when Baritone is actually installed, so the
 * mod keeps working on setups without it.
 */
public class XiaoDMixinPlugin implements IMixinConfigPlugin {
    private static final String BARITONE_MIXIN = "BaritoneBlockOptionalMetaMixin";
    private boolean baritoneLoaded;

    @Override
    public void onLoad(String mixinPackage) {
        baritoneLoaded = FabricLoader.getInstance().isModLoaded("baritone");
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith(BARITONE_MIXIN)) {
            return baritoneLoaded;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
