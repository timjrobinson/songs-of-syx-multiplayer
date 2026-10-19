import com.codedisaster.steamworks.SteamAPI;

/** Runs steamworks4j's shared loader (what the game's SteamAchieve helper does): rewrites
 *  <java.io.tmpdir>/steamworks4j/1.9.0/ in place. Spawned by HeadlessTest. */
public class SteamRewriteChild {
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 3; i++) {
            SteamAPI.loadLibraries();
            Thread.sleep(300);
        }
        System.out.println("rewrote " + System.getProperty("java.io.tmpdir") + "/steamworks4j/1.9.0");
    }
}
