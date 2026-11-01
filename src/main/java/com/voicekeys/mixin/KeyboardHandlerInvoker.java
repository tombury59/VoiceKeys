package com.voicekeys.mixin;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Accès à la méthode privée {@code keyPress} de {@link KeyboardHandler}, pour injecter
 * un événement clavier identique à une vraie frappe physique. C'est le chemin que
 * Wynntils écoute pour déclencher ses sorts (via son {@code KeyInputEvent}).
 */
@Mixin(KeyboardHandler.class)
public interface KeyboardHandlerInvoker {

    @Invoker("keyPress")
    void voicekeys$keyPress(long window, int action, KeyEvent event);
}
