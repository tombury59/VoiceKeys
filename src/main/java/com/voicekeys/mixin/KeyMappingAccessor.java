package com.voicekeys.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accès au champ privé {@code clickCount} de {@link KeyMapping}.
 * <p>
 * {@code KeyMapping.consumeClick()} ne renvoie vrai que tant que {@code clickCount > 0}.
 * Certains mods (dont Wynntils pour ses sorts) détectent une pression via ce compteur
 * plutôt que via {@code isDown()}. On l'incrémente donc à la main en plus de
 * {@code setDown(true)}, pour couvrir les deux styles de lecture.
 */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {

    @Accessor("clickCount")
    int getClickCount();

    @Accessor("clickCount")
    void setClickCount(int clickCount);

    /** Touche physique actuellement liée à ce keybind (pour simuler un vrai appui). */
    @Accessor("key")
    InputConstants.Key getBoundKey();
}
