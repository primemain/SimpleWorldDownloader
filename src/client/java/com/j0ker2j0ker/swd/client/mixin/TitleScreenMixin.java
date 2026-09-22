package com.j0ker2j0ker.swd.client.mixin;

import com.j0ker2j0ker.swd.client.screen.FlashbackConvertScreen;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    @Unique
    private static final Identifier FLASHBACK_WORLD = Identifier.fromNamespaceAndPath("swd", "icon/flashback_world");

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void swd$addFlashbackButton(CallbackInfo ci) {
        SpriteIconButton button = this.addRenderableWidget(SpriteIconButton.builder(Component.literal("Flashback → World"),
                        b -> this.minecraft.setScreen(new FlashbackConvertScreen(this)), true)
                .width(20).sprite(FLASHBACK_WORLD, 16, 16).build());
        button.setTooltip(Tooltip.create(Component.literal("Flashback → World")));
        // Next to the "Minecraft Realms" button, above Flashback's button
        button.setPosition(this.width / 2 + 104, this.height / 4 + 72);
    }
}
