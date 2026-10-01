package com.panzer.mods.celeris.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Decides at Mixin-config-load time (before any class is woven) whether
 * {@code FriendlyByteBufMixin} should be applied. That mixin implements
 * {@code SegmentAccess} and references {@code MemorySegment} in its own
 * signature, so its class file is preview-marked -- it can only be woven
 * on a JVM running with {@code --enable-preview}. This plugin itself must
 * never reference any {@code java.lang.foreign} type, or it would carry
 * the same preview marking and defeat the point.
 */
public final class CelerisMixinPlugin implements IMixinConfigPlugin {

    private static final String FRIENDLY_BYTE_BUF_MIXIN = "network.FriendlyByteBufMixin";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }


    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith(FRIENDLY_BYTE_BUF_MIXIN)) {
            return isFfmClassLoadable();
        }
        return true;
    }

    /**
     * Checks, via reflection only, whether {@code FfmMemoryBackend} (and
     * therefore the FFM API it depends on) can actually be loaded on this
     * JVM. Deliberately never references {@code java.lang.foreign} or
     * {@code FfmMemoryBackend} by static type -- only {@link Class#forName}
     * -- so this class itself stays free of the preview marking.
     */
    @SuppressWarnings("JavadocReference")
    private boolean isFfmClassLoadable() {
        try {
            Class.forName("com.panzer.mods.celeris.core.memory.backend.FfmMemoryBackend");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return isFfmClassLoadable()
                ? List.of(FRIENDLY_BYTE_BUF_MIXIN)
                : List.of();
    }
    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
