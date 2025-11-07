package nl.ctasoftware.crypto.ticker.server.model;

import org.springframework.security.core.GrantedAuthority;

public enum Px75Role  implements GrantedAuthority {
    ADMIN, USER, MODERATOR;

    @Override
    public String getAuthority() {
        return "ROLE_" + name();
    }
}
