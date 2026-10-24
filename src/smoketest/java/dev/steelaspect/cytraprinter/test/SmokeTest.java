package dev.steelaspect.cytraprinter.test;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screens.TitleScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 1.20.4 stand-in for the 1.21.11 client game test (fabric-client-gametest-api-v1 starts at 1.21.4): once the title
 * screen is up, {@link PrinterChecks} runs on its own thread, driving the client through {@link Ctx}. Logs
 * {@code [PrinterSmoke] PASS} or {@code FAIL}, then quits. Only active with -Dcytraprinter.smoke=true.
 */
public class SmokeTest implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("PrinterSmoke");
    private boolean started;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("cytraprinter.smoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            Ctx.ticks++;
            if (started || !(mc.screen instanceof TitleScreen)) return;
            started = true;
            Thread thread = new Thread(SmokeTest::run, "Printer smoke test");
            thread.setDaemon(true);
            thread.start();
        });
    }

    private static void run() {
        Ctx t = new Ctx();
        int failures;
        try {
            failures = new PrinterChecks(t).run();
        } catch (Throwable e) {
            LOG.error("[PrinterSmoke] test threw", e);
            failures = -1;
        }
        if (failures == 0) LOG.info("[PrinterSmoke] PASS");
        else LOG.error("[PrinterSmoke] FAIL: {}", failures < 0 ? "exception" : failures + " check(s) failed, see log");
        t.mc.execute(() -> {
            if (t.mc.level != null) t.mc.level.disconnect();
            t.mc.disconnect();
            t.mc.stop();
        });
    }
}
