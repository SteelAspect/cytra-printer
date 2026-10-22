package dev.steelaspect.cytraprinter.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import dev.steelaspect.cytraprinter.CytraPrinter;
import dev.steelaspect.cytraprinter.client.config.Configs;
import dev.steelaspect.cytraprinter.client.printer.Skipped;
import fi.dy.masa.malilib.interfaces.IRenderer;
import fi.dy.masa.malilib.render.MaLiLibPipelines;
import fi.dy.masa.malilib.render.RenderContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.data.Color4f;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
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

    @Override
    public void onRenderWorldLast(Matrix4f posMatrix, Matrix4f projMatrix) {
        Minecraft mc = Minecraft.getInstance();
        if (!Configs.SHOW_SKIPPED.getBooleanValue() || mc.player == null || mc.options.hideGui) return;
        Skipped.checkLevel(mc.level);
        if (Skipped.size() == 0) return;
        List<Skipped.Spot> spots = Skipped.near(mc.player.getEyePosition(), Configs.SKIPPED_RANGE.getIntegerValue());
        if (spots.isEmpty()) return;
        BlockPos target = Skipped.target();
        try (RenderContext lines = new RenderContext(() -> CytraPrinter.MOD_ID + ":skipped/lines",
                MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_NO_DEPTH_NO_CULL)) {
            BufferBuilder buffer = lines.getBuilder();
            for (Skipped.Spot spot : spots) {
                if (spot.pos().equals(target)) continue;
                RenderUtils.drawBlockBoundingBoxOutlinesBatchedLinesSimple(spot.pos(), color(spot.kind()), 0.003, 2.0f, buffer);
            }
            if (target != null) {
                Color4f c = color(spots.stream().filter(s -> s.pos().equals(target)).map(Skipped.Spot::kind).findFirst().orElse(Skipped.Kind.STUCK));
                float pulse = (float) (0.55 + 0.45 * Math.sin(System.currentTimeMillis() / 150.0));
                RenderUtils.drawBlockBoundingBoxOutlinesBatchedLinesSimple(target, new Color4f(c.r, c.g, c.b, pulse), 0.03, 4.0f, buffer);
            }
            MeshData mesh = buffer.build();
            if (mesh != null) {
                lines.draw(mesh, false, true);
                mesh.close();
            }
        } catch (Exception e) {
            if (!loggedError) {
                loggedError = true;
                CytraPrinter.LOGGER.warn("Skipped block outlines failed", e);
            }
        }
    }
}
