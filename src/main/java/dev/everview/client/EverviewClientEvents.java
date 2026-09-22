package dev.everview.client;

import dev.everview.Everview;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

@EventBusSubscriber(modid = Everview.MOD_ID, value = Dist.CLIENT)
public final class EverviewClientEvents {
    private EverviewClientEvents() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) {
            return;
        }
        EverviewRuntime.render(event);
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.level == null || mc.player == null) {
            return;
        }

        GuiGraphics graphics = event.getGuiGraphics();
        int x = 6;
        int y = 6;
        int lineHeight = mc.font.lineHeight + 2;

        String[] lines = EverviewRuntime.debugLines();
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, mc.font.width(line));
        }

        graphics.fill(x - 3, y - 3, x + width + 4, y + lines.length * lineHeight + 1, 0x90000000);
        for (String line : lines) {
            graphics.drawString(mc.font, line, x, y, 0xFFFFFFFF, true);
            y += lineHeight;
        }
    }
}
