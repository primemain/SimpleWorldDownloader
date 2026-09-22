package com.j0ker2j0ker.swd.client.mixin;

import com.j0ker2j0ker.swd.client.screen.FlashbackConvertScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void swd$addFlashbackButton(CallbackInfo ci) {
        this.addRenderableWidget(Button.builder(Component.literal("Flashback → World"),
                        b -> this.minecraft.setScreen(new FlashbackConvertScreen(this)))
                .bounds(this.width / 2 + 104, this.height / 4 + 48, 90, 20).build());
    }
}
