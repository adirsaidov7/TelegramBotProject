package survey.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The GLOBAL community. Independent of any survey: members are never removed
 * and carry no survey-related status. Thread safe.
 */
public class CommunityService {

    public interface Listener {
        /** Called (on an arbitrary thread) after a new member joined. */
        void onMemberJoined(Member member, int communitySize);
    }

    private final Map<Long, Member> members = new LinkedHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Path storeFile; // may be null = in-memory only

    public CommunityService(Path storeFile) {
        this.storeFile = storeFile;
        load();
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    /**
     * Adds a member unless already present.
     *
     * @return the new member, or empty if the user was already in the community
     */
    public Optional<Member> join(long id, String name, String username) {
        Member created;
        int size;
        synchronized (this) {
            if (members.containsKey(id)) return Optional.empty();
            created = new Member(id, name, username, Instant.now());
            members.put(id, created);
            size = members.size();
            save();
        }
        for (Listener l : listeners) l.onMemberJoined(created, size);
        return Optional.of(created);
    }

    public synchronized boolean isMember(long id) {
        return members.containsKey(id);
    }

    public synchronized int size() {
        return members.size();
    }

    /** Copy of the members in join order. */
    public synchronized List<Member> members() {
        return new ArrayList<>(members.values());
    }

    // ---- optional persistence (so the community survives a restart) ----
    // Plain text, one member per line:  id <TAB> joinedAtMillis <TAB> username <TAB> name

    private void load() {
        if (storeFile == null || !Files.exists(storeFile)) return;
        try {
            for (String line : Files.readAllLines(storeFile, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t", 4);
                if (f.length < 4) continue;
                long id = Long.parseLong(f[0]);
                members.put(id, new Member(id, f[3], f[2].isEmpty() ? null : f[2], Instant.ofEpochMilli(Long.parseLong(f[1]))));
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not read " + storeFile + ": " + e.getMessage());
        }
    }

    private void save() {
        if (storeFile == null) return;
        List<String> lines = new ArrayList<>();
        for (Member m : members.values()) {
            lines.add(m.id() + "\t" + m.joinedAt().toEpochMilli() + "\t"
                    + (m.username() == null ? "" : m.username()) + "\t" + m.name().replaceAll("[\\t\\r\\n]", " "));
        }
        try {
            Files.write(storeFile, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Could not write " + storeFile + ": " + e.getMessage());
        }
    }
}
