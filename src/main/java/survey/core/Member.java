package survey.core;

import java.time.Instant;

/** A member of the global community. Immutable; carries NO per-survey state. */
public record Member(long id, String name, String username, Instant joinedAt) {

    /** "@danny" or "—" when the user has no Telegram username. */
    public String usernameLabel() {
        return username == null || username.isBlank() ? "—" : "@" + username;
    }
}
