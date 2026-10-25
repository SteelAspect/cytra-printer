package dev.steelaspect.cytraprinter.client;

import dev.steelaspect.cytraprinter.CytraPrinter;
import dev.steelaspect.cytraprinter.client.config.Configs;
import dev.steelaspect.cytraprinter.client.printer.Stats;
import fi.dy.masa.malilib.interfaces.IRenderer;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Small status box while printing: rate, what's left, what's missing or held back. */
public final class HudRenderer implements IRenderer {
    public static final HudRenderer INSTANCE = new HudRenderer();
    private static final String P = CytraPrinter.MOD_ID + ".hud.";
    /** Size of the box last drawn, for the Alloy Hub HUD layout editor. */
    public static int lastWidth = 150, lastHeight = 20;

    private HudRenderer() {
    }

    @Override
    public void onExtractGuiOverlayPost(GuiContext ctx, float partialTicks, net.minecraft.util.profiling.ProfilerFiller profiler) {
        Minecraft mc = Minecraft.getInstance();
        if (!Configs.PRINTING.getBooleanValue() || !Configs.SHOW_HUD.getBooleanValue() || mc.gui.hud.isHidden() || mc.player == null) return;
        List<String> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        add(lines, colors, StringUtils.translate(P + "status", Stats.perSecond(), Stats.remaining, Stats.placedTotal), 0xFFFFFFFF);
        add(lines, colors, StringUtils.translate(P + "helper." + ServerHelper.statusKey()), 0xFFA0A0A0);
        if (Stats.lagging) add(lines, colors, StringUtils.translate(P + "lagging"), 0xFFFF5050);
        String bedrock = dev.steelaspect.cytraprinter.client.printer.Printer.bedrockStatus();
        if (bedrock != null) add(lines, colors, StringUtils.translate(P + "bedrock." + bedrock), 0xFFFFD040);
        if (!Stats.missing.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (Map.Entry<Item, Integer> e : Stats.missing.entrySet()) {
                if (n++ == 3) {
                    sb.append(", +").append(Stats.missing.size() - 3);
                    break;
                }
                if (sb.length() > 0) sb.append(", ");
                sb.append(e.getKey().getName(e.getKey().getDefaultInstance()).getString()).append(" x").append(e.getValue());
            }
            add(lines, colors, StringUtils.translate(P + "missing", sb.toString()), 0xFFFF8040);
        }
        if (Stats.held > 0) add(lines, colors, StringUtils.translate(P + "held", Stats.held), 0xFFFFD040);
        if (Stats.wrong > 0) add(lines, colors, StringUtils.translate(P + "wrong", Stats.wrong), 0xFFFF5050);
        if (Stats.unsolvable > 0) add(lines, colors, StringUtils.translate(P + "unsolvable", Stats.unsolvable), 0xFFFF5050);
        if (Stats.stuck > 0) add(lines, colors, StringUtils.translate(P + "stuck", Stats.stuck), 0xFFFF5050);

        int x = Configs.HUD_X.getIntegerValue(), y = Configs.HUD_Y.getIntegerValue();
        int width = 0;
        for (String s : lines) width = Math.max(width, mc.font.width(s));
        lastWidth = width + 4;
        lastHeight = lines.size() * 10 + 2;
        ctx.fill(x - 2, y - 2, x + width + 2, y + lines.size() * 10, 0x90000000);
        for (int i = 0; i < lines.size(); i++) ctx.drawString(mc.font, lines.get(i), x, y + i * 10, colors.get(i));
    }

    private static void add(List<String> lines, List<Integer> colors, String s, int color) {
        lines.add(s);
        colors.add(color);
    }
}
