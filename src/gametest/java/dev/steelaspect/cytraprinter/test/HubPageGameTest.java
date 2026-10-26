package dev.steelaspect.cytraprinter.test;

import dev.steelaspect.cytrahub.client.model.ModEntry;
import dev.steelaspect.cytrahub.client.model.ModRegistry;
import dev.steelaspect.cytrahub.client.screen.HubScreen;
import dev.steelaspect.cytrahub.client.screen.HubView;
import dev.steelaspect.cytrahub.client.screen.detail.ModDetail;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.List;

/** With Alloy Hub loaded (-PwithHub): the mod's own Hub plugin is used, and every tab of its page opens. */
public class HubPageGameTest implements FabricClientGameTest {
    private static final Logger LOG = LoggerFactory.getLogger("CytraPrinterTest");
    private static final String MOD = "cytraprinter";
    private static final String[] TABS = {"status", "settings", "actions"};

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!FabricLoader.getInstance().isModLoaded("cytra-hub")) {
            LOG.info("SKIP: Alloy Hub not loaded (run with -PwithHub)");
            return;
        }
        // The mod page opens as a popup in the grid layout (as in the Hub's own page tests).
        context.runOnClient(mc -> dev.steelaspect.cytrahub.client.HubClientConfig.get().layout = "grid");
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            ModEntry e = context.computeOnClient(mc -> ModRegistry.find(MOD));
            check(e != null && e.hasPlugin, "page registered");
            check(!e.settings.isEmpty(), "page has settings");
            check(e.hasMasterSwitch(), "page has a master switch");
            check(!e.hud.isEmpty(), "page has the HUD element");
            context.setScreen(() -> new HubScreen(null, HubView.Page.MODS));
            context.waitTicks(10);
            context.runOnClient(mc -> ((HubScreen) mc.gui.screen()).openMod(MOD));
            context.waitTicks(15);
            for (String tab : TABS) {
                boolean shown = context.computeOnClient(mc -> select((HubScreen) mc.gui.screen(), tab));
                check(shown, "tab " + tab + " shown");
                context.waitTicks(8);
                context.takeScreenshot("hub_" + tab);
            }
            context.setScreen(() -> null);
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("Check failed: hub " + what);
        LOG.info("PASS: hub {}", what);
    }

    private static boolean select(HubScreen hs, String tabId) {
        ModDetail d = hs.detailPopup();
        if (d == null) return false;
        try {
            Method visible = ModDetail.class.getDeclaredMethod("visibleTabs");
            visible.setAccessible(true);
            List<?> tabs = (List<?>) visible.invoke(d);
            for (int i = 0; i < tabs.size(); i++) {
                if (((ModDetail.Tab) tabs.get(i)).id().equals(tabId)) {
                    Method select = ModDetail.class.getDeclaredMethod("select", int.class);
                    select.setAccessible(true);
                    select.invoke(d, i);
                    return true;
                }
            }
        } catch (ReflectiveOperationException ex) {
            return false;
        }
        return false;
    }
}
