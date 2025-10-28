import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.google.gson.*;

public class JSONHandler {
    private static final Gson gson = new Gson();  // gson instance for parsing json

    public static String parseJSONtoText(JsonObject jsonObject) throws IllegalArgumentException {
        if (jsonObject == null) {
            // throw an error if the jsonObject is null
            throw new IllegalArgumentException("Error 400: jsonObject is null.");
        }

        StringBuilder output = new StringBuilder();
        Set<Map.Entry<String, JsonElement>> entries = jsonObject.entrySet();  // get all entries from the json object

        // loop through each entry in the json
        for (Map.Entry<String, JsonElement> entry : entries) {
            String key = entry.getKey();  // get the key (field name)
            JsonElement valueElement = entry.getValue();  // get the value as a JsonElement

            String valueStr;
            // if it's a primitive, convert it to a string, otherwise use toString
            if (valueElement.isJsonPrimitive()) {
                valueStr = valueElement.getAsJsonPrimitive().getAsString();
            } else {
                valueStr = valueElement.toString();
            }

            // append the key and value to the output string
            output.append(key).append(": ").append(valueStr).append("\n");
        }

        return output.toString();  // return the built string
    }

    public static String readFile(String filePath) throws IOException {
        if (filePath == null) {
            // throw error if the file path is null
            throw new IOException("Error 400: filePath is null.");
        }

        StringBuilder content = new StringBuilder();
        // read the file line by line and append to the content string
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = br.readLine()) != null) {
                content.append(line).append("\n");
            }
        } catch (IOException e) {
            // catch and rethrow the error with more detail if something goes wrong
            throw new IOException("Error reading the file: " + e.getMessage(), e);
        }
        return content.toString();  // return the file content as a string
    }

    public static String extractJSONContent(String data) {
        int startIdx = data.indexOf("{");  // find the first curly brace
        int endIdx = data.lastIndexOf("}");  // find the last curly brace

        // if valid json brackets are found, return the substring between them
        if (startIdx != -1 && endIdx != -1 && startIdx < endIdx) {
            return data.substring(startIdx, endIdx + 1);
        }

        return null;  // return null if no valid json brackets found
    }

    public static JsonObject parseTextToJSON(String inputText) throws IllegalArgumentException {
        if (inputText == null) {
            // throw error if input text is null
            throw new IllegalArgumentException("Input text is null.");
        }

        Map<String, Object> jsonData = new LinkedHashMap<>();  // create a linked hashmap for ordered json data
        // split the input text by lines and process each line
        for (String line : inputText.split("\n")) {
            String[] parts = line.split(":", 2);  // split each line by the first colon

            if (parts.length != 2) {
                // throw an error if the line doesn't contain exactly two parts
                throw new IllegalArgumentException("Invalid line format: " + line);
            }

            jsonData.put(parts[0].trim(), parts[1].trim());  // add key-value pair to the map
        }

        // convert the map to a json object and return it
        return gson.toJsonTree(jsonData).getAsJsonObject();
    }
}
