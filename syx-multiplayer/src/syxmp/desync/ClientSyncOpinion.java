package syxmp.desync;

import java.util.ArrayList;
import java.util.List;

import syxmp.net.ByteReader;
import syxmp.net.ByteWriter;

/**
 * One window of desync-detection evidence, port of RW-MP's ClientSyncOpinion.
 *
 * We reseed RND per sub-step, so instead of RW's accumulated Rand state we
 * fingerprint each sub-step with one post-step RND draw: it hashes
 * (sessionSeed, updateI, number of draws the sub-step consumed) and diverges
 * the moment any client's simulation consumes the RNG differently. Commands
 * are fingerprinted the same way after execution. A full-state raw-save hash
 * (join points) rides along when available.
 */
public final class ClientSyncOpinion {

    /** Timer units covered per opinion (RW uses 30). */
    public static final int WINDOW = 30;

    public final int startTimer;
    public final List<Integer> subStepStates = new ArrayList<>();
    /** Parallel to subStepStates: GAME.updateI and Timer of each sub-step (diagnostics). */
    public final List<Integer> subStepUpdateI = new ArrayList<>();
    public final List<Integer> subStepTimer = new ArrayList<>();
    public final List<Integer> commandStates = new ArrayList<>();
    public boolean simulating;
    public long joinPointHash;
    public int joinPointTimer = -1;

    public ClientSyncOpinion(int startTimer) {
        this.startTimer = startTimer;
    }

    public void serialize(ByteWriter w) {
        w.writeInt(startTimer);
        w.writeBool(simulating);
        w.writeInt(subStepStates.size());
        for (int i = 0; i < subStepStates.size(); i++) {
            w.writeInt(subStepStates.get(i));
            w.writeInt(subStepUpdateI.get(i));
            w.writeInt(subStepTimer.get(i));
        }
        w.writeInt(commandStates.size());
        for (int s : commandStates)
            w.writeInt(s);
        w.writeInt(joinPointTimer);
        w.writeLong(joinPointHash);
    }

    public static ClientSyncOpinion deserialize(ByteReader r) {
        ClientSyncOpinion o = new ClientSyncOpinion(r.readInt());
        o.simulating = r.readBool();
        int n = r.readInt();
        for (int i = 0; i < n; i++) {
            o.subStepStates.add(r.readInt());
            o.subStepUpdateI.add(r.readInt());
            o.subStepTimer.add(r.readInt());
        }
        n = r.readInt();
        for (int i = 0; i < n; i++)
            o.commandStates.add(r.readInt());
        o.joinPointTimer = r.readInt();
        o.joinPointHash = r.readLong();
        return o;
    }

    /** @return a description of the first difference, or null if equivalent. */
    public String compareTo(ClientSyncOpinion other) {
        if (startTimer != other.startTimer)
            return "window mismatch " + startTimer + " vs " + other.startTimer;
        if (joinPointTimer >= 0 && joinPointTimer == other.joinPointTimer
                && joinPointHash != other.joinPointHash)
            return "STATE HASH mismatch at join point " + joinPointTimer;
        String r = compareLists(subStepStates, other.subStepStates, "sub-step");
        if (r != null)
            return r + describeSubSteps(other);
        return compareLists(commandStates, other.commandStates, "command");
    }

    private String describeSubSteps(ClientSyncOpinion other) {
        StringBuilder sb = new StringBuilder(" [local: ");
        for (int i = 0; i < subStepStates.size(); i++)
            sb.append(subStepTimer.get(i)).append('/').append(subStepUpdateI.get(i)).append(' ');
        sb.append("| remote: ");
        for (int i = 0; i < other.subStepStates.size(); i++)
            sb.append(other.subStepTimer.get(i)).append('/').append(other.subStepUpdateI.get(i)).append(' ');
        return sb.append(']').toString();
    }

    private String compareLists(List<Integer> a, List<Integer> b, String what) {
        if (a.size() != b.size())
            return what + " count " + a.size() + " vs " + b.size()
                    + " in window " + startTimer;
        for (int i = 0; i < a.size(); i++)
            if (!a.get(i).equals(b.get(i)))
                return what + " state #" + i + " differs in window " + startTimer;
        return null;
    }
}
