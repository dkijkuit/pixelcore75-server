package nl.ctasoftware.crypto.ticker.server.converter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenConfig;

import java.util.List;

@Converter
public class ScreenConfigListJsonConverter implements AttributeConverter<List<ScreenConfig>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TypeReference<List<ScreenConfig>> TYPE =
            new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<ScreenConfig> attribute) {
        try {
            return attribute == null ? null : MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize screensConfig to JSON", e);
        }
    }

    @Override
    public List<ScreenConfig> convertToEntityAttribute(String dbData) {
        try {
            return (dbData == null || dbData.isBlank()) ? null : MAPPER.readValue(dbData, TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize screensConfig JSON", e);
        }
    }
}
