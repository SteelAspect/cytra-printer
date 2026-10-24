package dev.steelaspect.cytraprinter.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.steelaspect.cytraprinter.CytraPrinter;
import dev.steelaspect.cytraprinter.client.config.Configs;
import dev.steelaspect.cytraprinter.client.printer.Skipped;
import fi.dy.masa.malilib.interfaces.IRenderer;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.Color4f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Outlines the spots the printer skipped, through walls so they can be found in a big build: red = wrong block it
 * won't fix, orange = can't place it right from where you stood, yellow = gave up for now. The spot picked by the
 * "next skipped block" key pulses.
 */
public final class SkippedRenderer implements IRenderer {
    public static final SkippedRenderer INSTANCE = new SkippedRenderer();
    private static final Color4f WRONG = new Color4f(0.94f, 0.38f, 0.35f, 0.9f);
    private static final Color4f UNSOLVABLE = new Color4f(1.0f, 0.55f, 0.2f, 0.9f);
    private static final Color4f STUCK = new Color4f(0.95f, 0.8f, 0.25f, 0.9f);

    private boolean loggedError;

    private SkippedRenderer() {
    }

    public static Color4f color(Skipped.Kind kind) {
        return switch (kind) {
            case WRONG -> WRONG;
            case UNSOLVABLE -> UNSOLVABLE;
            case STUCK -> STUCK;
        };
    }

    /**
     * 1.20.4 has no render pipelines: the lines are drawn with the position-colour shader, no depth test, in the
     * model-view MaLiLib's world-last hook already set up (as Litematica 0.17 draws its own overlays). MaLiLib 0.18's
     * outline helper only subtracts the camera when given it.
     */
    @Override
    public void onRenderWorldLast(PoseStack matrices, Matrix4f projMatrix) {
        Minecraft mc = Minecraft.getInstance();
        if (!Configs.SHOW_SKIPPED.getBooleanValue() || mc.player == null || mc.options.hideGui) return;
        Skipped.checkLevel(mc.level);
        if (Skipped.size() == 0) return;
        List<Skipped.Spot> spots = Skipped.near(mc.player.getEyePosition(), Configs.SKIPPED_RANGE.getIntegerValue());
        if (spots.isEmpty()) return;
        BlockPos target = Skipped.target();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        try {
            BufferBuilder buffer = begin(2.0f);
            for (Skipped.Spot spot : spots) {
                if (spot.pos().equals(target)) continue;
                RenderUtils.drawBlockBoundingBoxOutlinesBatchedLines(spot.pos(), camera, color(spot.kind()), 0.003, buffer);
            }
            Tesselator.getInstance().end();
            if (target != null) {
                Color4f c = color(spots.stream().filter(s -> s.pos().equals(target)).map(Skipped.Spot::kind).findFirst().orElse(Skipped.Kind.STUCK));
                float pulse = (float) (0.55 + 0.45 * Math.sin(System.currentTimeMillis() / 150.0));
                buffer = begin(4.0f);
                RenderUtils.drawBlockBoundingBoxOutlinesBatchedLines(target, camera, new Color4f(c.r, c.g, c.b, pulse), 0.03, buffer);
                Tesselator.getInstance().end();
            }
        } catch (Exception e) {
            if (!loggedError) {
                loggedError = true;
                CytraPrinter.LOGGER.warn("Skipped block outlines failed", e);
            }
        } finally {
            RenderSystem.lineWidth(1.0f);
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        }
    }

    private static BufferBuilder begin(float lineWidth) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.lineWidth(lineWidth);
        RenderSystem.applyModelViewMatrix();
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        return buffer;
    }
}
