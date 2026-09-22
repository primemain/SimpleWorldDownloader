package com.j0ker2j0ker.swd.client.mixin;

import com.j0ker2j0ker.swd.client.util.ChunkFilter;
import com.j0ker2j0ker.swd.client.util.SaveManager;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Small "Saved: X chunks" counter in the top right corner while downloading. */
@Mixin(Gui.class)
public abstract class GuiMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void swd$drawChunkCounter(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (!SaveManager.isSaving) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;

        String text = "Saved: " + ChunkFilter.savedCount() + " chunks";
        int x = graphics.guiWidth() - mc.font.width(text) - 4;
        graphics.drawString(mc.font, text, x, 4, 0xFF55FF55);
    }
}
