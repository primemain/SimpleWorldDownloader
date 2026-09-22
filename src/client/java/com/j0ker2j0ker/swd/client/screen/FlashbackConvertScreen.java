package com.j0ker2j0ker.swd.client.screen;

import com.j0ker2j0ker.swd.client.SwdClient;
import com.j0ker2j0ker.swd.client.util.FlashbackConverter;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.List;

/** Drag and drop a Flashback recording here to turn it into a void world. */
public class FlashbackConvertScreen extends Screen {

    private final Screen parent;
    private volatile String status = "Drag and drop a Flashback recording (.zip) onto this window";
    private volatile boolean working = false;

    public FlashbackConvertScreen(Screen parent) {
        super(Component.literal("Flashback to World"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(this.width / 2 - 100, this.height - 40, 200, 20).build());
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        if (working) return;
        Path zip = null;
        for (Path f : files) if (f.toString().toLowerCase().endsWith(".zip")) zip = f;
        if (zip == null) {
            status = "That's not a .zip file!";
            return;
        }
        Path file = zip;
        Path saves = this.minecraft.getLevelSource().getBaseDir();
        working = true;
        Thread t = new Thread(() -> {
            try {
                FlashbackConverter.convert(file, saves, s -> status = s);
            } catch (Exception e) {
                SwdClient.LOGGER.error("Flashback convert failed", e);
                status = "Failed: " + e.getMessage();
            }
            working = false;
        }, "SWD Flashback Converter");
        t.start();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        g.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFFFF);
        g.drawCenteredString(this.font, Component.literal(status), this.width / 2, this.height / 2, 0xFFFFFF55);
    }

    @Override
    public void onClose() {
        if (!working) this.minecraft.setScreen(parent);
    }
}
