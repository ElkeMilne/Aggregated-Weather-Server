import com.google.gson.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;

public class JSONHandlerTest {

    // Test that reading a file with a null file path throws an IOException
    @Test
    public void testReadFileThrowsIOExceptionWhenFilePathIsNull() {
        assertThrows(IOException.class, () -> JSONHandler.readFile(null));
    }

    // Test that reading a file with an invalid file path throws an IOException
    @Test
    public void testReadFileThrowsIOExceptionWhenFilePathIsInvalid() {
        assertThrows(IOException.class, () -> JSONHandler.readFile("invalidPath"));
    }

    // Test that reading a valid file returns the correct content
    @Test
    public void testReadFileReturnsCorrectContentForValidFile() throws IOException {
        String result = JSONHandler.readFile("src/weather_test.txt");
        assertEquals("id:IDS60901\n" + 
                     "local_date_time_full:20230715160000\n" + 
                     "air_temp:13.3\n" + 
                     "cloud:Partly cloudy\n" + 
                     "", result);
    }

    // Test that parsing a null input string throws an IllegalArgumentException
    @Test
    public void testParseTextToJSONThrowsExceptionWhenInputIsNull() {
        assertThrows(IllegalArgumentException.class, () -> JSONHandler.parseTextToJSON(null));
    }

    // Test that parsing an invalid input line format throws an IllegalArgumentException
    @Test
    public void testParseTextToJSONThrowsExceptionForInvalidLineFormat() {
        assertThrows(IllegalArgumentException.class, () -> JSONHandler.parseTextToJSON("invalidLineWithoutColon"));
    }

    // Test that parsing a valid input string returns the correct JsonObject
    @Test
    public void testParseTextToJSONReturnsCorrectJsonObjectForValidInput() {
        JsonObject result = JSONHandler.parseTextToJSON("key1: value1\nkey2: value2");
        assertEquals("value1", result.get("key1").getAsString());
        assertEquals("value2", result.get("key2").getAsString());
    }

    // Test that converting a null JsonObject to text throws an IllegalArgumentException
    @Test
    public void testParseJSONtoTextThrowsExceptionWhenJsonObjectIsNull() {
        assertThrows(IllegalArgumentException.class, () -> JSONHandler.parseJSONtoText(null));
    }

    // Test that converting a valid JsonObject returns the correct text format
    @Test
    public void testParseJSONtoTextReturnsCorrectTextForValidJsonObject() {
        JsonObject obj = new JsonObject();
        obj.addProperty("key1", "value1");
        obj.addProperty("key2", "value2");
        String result = JSONHandler.parseJSONtoText(obj);
        assertTrue(result.contains("key1: value1"));
        assertTrue(result.contains("key2: value2"));
    }

    // Test that extracting JSON content from a non-JSON string returns null
    @Test
    public void testExtractJSONContentReturnsNullForNoJsonObject() {
        assertNull(JSONHandler.extractJSONContent("This is a sample text without a JSON object."));
    }

    // Test that extracting JSON content from a string with valid JSON returns the correct JSON object
    @Test
    public void testExtractJSONContentReturnsValidJsonForStringWithJsonObject() {
        String input = "Some text before {\"key\":\"value\"} Some text after";
        String expected = "{\"key\":\"value\"}";
        assertEquals(expected, JSONHandler.extractJSONContent(input));
    }
}
