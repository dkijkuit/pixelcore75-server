// advice/ApiErrorHandler.java
package nl.ctasoftware.crypto.ticker.server.exception;

import io.jsonwebtoken.JwtException;
import org.springframework.http.*;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestControllerAdvice
public class ApiErrorHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe ->
                fieldErrors.put(fe.getField(), fe.getDefaultMessage()));
        Map<String, Object> body = Map.of(
                "status", 400,
                "error", "Bad Request",
                "message", "Validation failed",
                "fields", fieldErrors
        );
        return ResponseEntity.badRequest().body(body);
    }

    // (Optional) map IllegalArgumentException from enum parsing, etc.
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArg(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "status", 400, "error", "Bad Request", "message", ex.getMessage()
        ));
    }

    // Bad login (BadCredentialsException) and refresh-token lookups of a meanwhile-
    // deleted user (UsernameNotFoundException) must be 401, not a 500.
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthentication(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "status", 401, "error", "Unauthorized", "message",
                ex.getMessage() == null ? "Authentication failed" : ex.getMessage()
        ));
    }

    // Expired/malformed/tampered refresh JWTs (POST /v1/auth/refresh reads the cookie
    // value directly) must be 401 so clients clear the session instead of retrying.
    @ExceptionHandler(JwtException.class)
    public ResponseEntity<Map<String, Object>> handleJwt(JwtException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "status", 401, "error", "Unauthorized", "message",
                ex.getMessage() == null ? "Invalid or expired token" : ex.getMessage()
        ));
    }

    // Upstream (ESPN etc.) fetch failed without an HTTP status of its own
    @ExceptionHandler(Px75ClientException.class)
    public ResponseEntity<Map<String, Object>> handleClientException(Px75ClientException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                "status", 502, "error", "Bad Gateway", "message", String.valueOf(ex.getMessage())
        ));
    }

    // Propagate upstream HTTP errors (e.g. ESPN 404 for an unknown competition) as a clean response
    @ExceptionHandler(HttpClientErrorException.class)
    public ResponseEntity<Map<String, Object>> handleUpstreamStatus(HttpClientErrorException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of(
                "status", ex.getStatusCode().value(), "error", ex.getStatusText(), "message", ex.getStatusText()
        ));
    }
}
