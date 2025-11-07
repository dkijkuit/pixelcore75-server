package nl.ctasoftware.crypto.ticker.server.model.dto;

import jakarta.validation.constraints.Email;
import lombok.Data;
import nl.ctasoftware.crypto.ticker.server.model.Px75Role;

import java.util.Set;

@Data
public class UpdateUserRequest {
    private String username;
    private String password;
    @Email private String email;
    private Set<Px75Role> roles; // full replace if provided
}
