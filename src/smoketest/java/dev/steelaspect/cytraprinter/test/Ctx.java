package dev.steelaspect.cytraprinter.test;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The bits of 1.21.11's ClientGameTestContext the printer checks use, for a test thread next to the 1.20.4 client:
 * run or compute on the client thread, wait client ticks, take screenshots.
 */
final class Ctx {
    static volatile long ticks;

    final Minecraft mc = Minecraft.getInstance();

    void client(Consumer<Minecraft> f) {
        mc.submit(() -> f.accept(mc)).join();
    }

    <R> R get(Function<Minecraft, R> f) {
        return mc.submit(() -> f.apply(mc)).join();
    }

    void waitTick() {
        waitTicks(1);
    }

    void waitTicks(int n) {
        long end = ticks + n;
        while (ticks < end) sleep();
    }

    /** Waits (checking on the client thread each tick) until the condition holds; false after {@code maxTicks}. */
    boolean waitFor(int maxTicks, BooleanSupplier condition) {
        for (int i = 0; i < maxTicks; i++) {
            if (get(c -> condition.getAsBoolean())) return true;
            waitTick();
        }
        return get(c -> condition.getAsBoolean());
    }

    String shot(String name) {
        client(c -> Screenshot.grab(c.gameDirectory, name + ".png", c.getMainRenderTarget(), m -> {
        }));
        return "screenshots/" + name + ".png";
    }

    private static void sleep() {
        try {
            Thread.sleep(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
