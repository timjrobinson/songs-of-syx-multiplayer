package syxmp.ui;

/**
 * One-line connection status shared by the main-menu screen and the in-game
 * HUD ("Connecting...", "Downloading world 45%", errors). Written from the
 * client state machine, read by UI code every frame.
 */
public final class MpStatus {

    public static volatile String line = "";
    public static volatile boolean error;
    public static volatile long changedAt;

    private MpStatus() {
    }

    public static void set(String s) {
        line = s;
        error = false;
        changedAt = System.currentTimeMillis();
    }

    public static void fail(String s) {
        line = s;
        error = true;
        changedAt = System.currentTimeMillis();
    }

    public static void clear() {
        line = "";
        error = false;
    }
}
