package dev.steelaspect.cytraprinter.client.gui;

import dev.steelaspect.alloyui.AlloyScreen;
import dev.steelaspect.alloyui.UiContext;
import dev.steelaspect.alloyui.render.Draw;
import dev.steelaspect.alloyui.theme.Theme;
import dev.steelaspect.alloyui.widget.Button;
import dev.steelaspect.alloyui.widget.Column;
import dev.steelaspect.alloyui.widget.Container;
import dev.steelaspect.alloyui.widget.ScrollPanel;
import dev.steelaspect.alloyui.widget.Tabs;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** The Nocturne frame of the settings screen: a centred panel with a title, tabs, a scrolling body and footer buttons. */
final class PrinterFrame extends Container {
    private static final float HEADER_H = 26, FOOTER_H = 26, PAD = 10, GAP = 4;

    private final Component title;
    private @Nullable Tabs tabs;
    private final Column content = new Column(2);
    private final ScrollPanel body;
    private final List<Button> buttons = new ArrayList<>();
    private float px, py, pw, ph;

    PrinterFrame(Component title) {
        this.title = title;
        this.body = add(new ScrollPanel(this.content));
    }

    PrinterFrame tabs(Tabs t) {
        this.tabs = add(t);
        return this;
    }

    Column content() {
        return this.content;
    }

    ScrollPanel body() {
        return this.body;
    }

    /** Footer button; the last one added is the rightmost (the main button). */
    Button button(Button b) {
        this.buttons.add(add(b));
        return b;
    }

    @Override
    public float measure(float width) {
        return 0;
    }

    @Override
    public boolean contains(double mx, double my) {
        return mx >= this.px && mx < this.px + this.pw && my >= this.py && my < this.py + this.ph;
    }

    @Override
    protected void layout() {
        float[] f = AlloyScreen.frameBounds(this.w, this.h);
        this.pw = f[2];
        this.ph = f[3];
        this.px = this.x + f[0];
        this.py = this.y + f[1];

        float cy = this.py + HEADER_H + GAP;
        float cw = this.pw - 2 * PAD;
        if (this.tabs != null) {
            this.tabs.setBounds(this.px + PAD, cy, cw, this.tabs.measure(cw));
            cy += this.tabs.h() + GAP * 2;
        }
        this.body.setBounds(this.px + PAD, cy, cw, this.py + this.ph - FOOTER_H - GAP - cy);
        float bx = this.px + this.pw - PAD;
        for (int i = this.buttons.size() - 1; i >= 0; i--) {
            Button b = this.buttons.get(i);
            float bw = Math.max(80, b.preferredWidth());
            bx -= bw;
            b.setBounds(bx, this.py + this.ph - FOOTER_H + 6, bw, b.measure(bw));
            bx -= GAP * 2;
        }
    }

    @Override
    protected void renderWidget(UiContext ctx) {
        Theme t = ctx.theme;
        Draw.elevation(ctx.g, this.px, this.py, this.pw, this.ph, t.metricF("radiusLarge"), 3);
        Draw.panel(ctx.g, this.px, this.py, this.pw, this.ph, t.metricF("radiusLarge"), t.color("panel"), t.color("border"), t.metricF("borderWidth"));
        Draw.heading(ctx.g, this.title, this.px + 12, this.py + 10, t.color("text"));
        Draw.fadeRule(ctx.g, this.px + PAD, this.py + HEADER_H, this.pw - 2 * PAD, t.color("border"));
        Draw.fadeRule(ctx.g, this.px + PAD, this.py + this.ph - FOOTER_H, this.pw - 2 * PAD, t.color("border"));
        super.renderWidget(ctx);
    }
}
