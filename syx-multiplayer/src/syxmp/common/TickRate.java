package syxmp.common;

/**
 * The lockstep clock model, ported from RimWorld Multiplayer's
 * TickPatch/ITickable time math.
 *
 * The server advances a Timer at a constant nominal TIMER_HZ, independent of
 * game speed. Each Timer unit, a client accumulates 1.0 into a tick-through
 * budget and runs one game sub-step (GAME.update at ds = 1/16, i.e. one
 * updateI increment) each time the budget crosses timePerTick(speed).
 * Speed changes are synced commands executed at exact Timer values, so the
 * accumulator stays identical on every client.
 *
 * Songs of Syx speeds (game/GameSpeed.java): 0, 0.25, 1, 5, 25, 250 — each
 * multiplying the base 16 sub-steps per game-second.
 */
public final class TickRate {

    /** Timer units per real second (nominal; the server is the authority). */
    public static final int TIMER_HZ = 60;

    /** Game sub-steps per second at speed 1 (GAME.update splits ds into 1/16s). */
    public static final int SUBSTEPS_PER_SECOND = 16;

    /** Exact sub-step size in game seconds; 0.0625 is float/double exact. */
    public static final double SUBSTEP_DS = 1.0 / 16.0;

    private TickRate() {
    }

    /**
     * Timer units per game sub-step at the given speed multiplier.
     * Returns 0 for paused (no ticking; commands still execute on Timer).
     */
    public static float timePerTick(double speed) {
        if (speed <= 0)
            return 0f;
        return (float) (TIMER_HZ / (SUBSTEPS_PER_SECOND * speed));
    }
}
