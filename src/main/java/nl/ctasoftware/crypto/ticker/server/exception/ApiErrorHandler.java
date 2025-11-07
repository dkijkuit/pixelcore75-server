// advice/ApiErrorHandler.java
package nl.ctasoftware.crypto.ticker.server.exception;

import org.springframework.http.*;
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
}
