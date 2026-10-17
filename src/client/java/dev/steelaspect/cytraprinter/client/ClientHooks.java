package dev.steelaspect.cytraprinter.client;

import net.minecraft.world.level.block.entity.SignBlockEntity;

import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;

/** Vanilla-only entry points for the mixins; the printer (which needs Litematica) plugs in when it loads. */
public final class ClientHooks {
    public static BiPredicate<SignBlockEntity, Boolean> signEditor = (sign, front) -> false;
    public static BooleanSupplier rotationLock = () -> false;
    public static Runnable teleport = () -> {
    };
    public static Runnable useItem = () -> {
    };
    /** Head yaw LocalPlayer.getViewYRot reports while the printer simulates a placement; NaN = vanilla. */
    public static float simulatedHeadYaw = Float.NaN;
    /** When the server last sent the time (it does every second): an old value means the server is lagging. */
    public static volatile long lastServerTimeMillis = System.currentTimeMillis();

    private ClientHooks() {
    }

    public static void onUseItem() {
        useItem.run();
    }

    public static void onTeleport() {
        teleport.run();
    }

    public static boolean rotationLocked() {
        return rotationLock.getAsBoolean();
    }

    public static boolean onSignEditor(SignBlockEntity sign, boolean front) {
        return signEditor.test(sign, front);
    }
}
