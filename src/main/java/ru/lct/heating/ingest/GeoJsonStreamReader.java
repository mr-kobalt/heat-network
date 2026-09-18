package ru.lct.heating.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Потоковый читатель GeoJSON {@code FeatureCollection}: разбирает файл
 * по одному объекту, не загружая документ целиком в память (FR-07, ADR-0008).
 */
@Component
public class GeoJsonStreamReader {

    private final ObjectMapper objectMapper;

    public GeoJsonStreamReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void read(InputStream inputStream, Consumer<JsonNode> featureConsumer) throws IOException {
        try (JsonParser parser = objectMapper.getFactory().createParser(inputStream)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Ожидался объект GeoJSON FeatureCollection");
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String fieldName = parser.currentName();
                parser.nextToken();
                if ("features".equals(fieldName)) {
                    if (parser.currentToken() != JsonToken.START_ARRAY) {
                        throw new IOException("Поле 'features' должно быть массивом");
                    }
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        JsonNode feature = objectMapper.readTree(parser);
                        featureConsumer.accept(feature);
                    }
                } else {
                    parser.skipChildren();
                }
            }
        }
    }
}
