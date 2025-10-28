import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class AggregationServerTests {

  private AggregationServer aggregationServer;

  @BeforeEach
  public void setUp() {
    aggregationServer = new AggregationServer(true);
  }

  // Test if NetworkHandler instance is correctly initialized
  @Test
  public void testNetworkHandlerInitialization() {
    assertNotNull(aggregationServer.getNetworkHandler());
  }

  // Test the initial server down state
  @Test
  public void testServerDownInitialState() {
    assertFalse(aggregationServer.getisServerDown());
  }

  // Test if server terminates and sets the isServerDown flag
  @Test
  public void testServerTermination() {
    aggregationServer.terminate();
    assertTrue(aggregationServer.getisServerDown());
  }

  // Test if valid weather data with ID is correctly added
  @Test
  public void testAddWeatherDataWithValidID() {
    JsonObject jsonObject = new JsonObject();
    jsonObject.addProperty("id", "station1");
    jsonObject.addProperty("temp", 20.5);
    assertTrue(aggregationServer.addWeatherData(jsonObject, 1, "server1"));
  }

  // Test if weather data without an ID is not added
  @Test
  public void testAddWeatherDataWithMissingID() {
    JsonObject jsonObject = new JsonObject();
    jsonObject.addProperty("temp", 20.5);
    assertFalse(aggregationServer.addWeatherData(jsonObject, 1, "server1"));
  }

  // Test if weather data with an empty ID is not added
  @Test
  public void testAddWeatherDataWithEmptyID() {
    JsonObject jsonObject = new JsonObject();
    jsonObject.addProperty("id", "");
    jsonObject.addProperty("temp", 20.5);
    assertFalse(aggregationServer.addWeatherData(jsonObject, 1, "server1"));
  }

  // Test if the ID can be extracted from valid data
  @Test
  public void testExtractIDFromValidData() {
    JsonObject jsonObject = new JsonObject();
    jsonObject.addProperty("id", "station1");
    assertEquals("station1", aggregationServer.extractID(jsonObject));
  }

  // Test if null is returned when no ID is present in the data
  @Test
  public void testExtractIDFromInvalidData() {
    JsonObject jsonObject = new JsonObject();
    assertNull(aggregationServer.extractID(jsonObject));
  }

  // Test if valid station ID passes validation
  @Test
  public void testValidStationID() {
    assertTrue(aggregationServer.isValidStation("station1"));
  }

  // Test if an empty station ID fails validation
  @Test
  public void testEmptyStationID() {
    assertFalse(aggregationServer.isValidStation(""));
  }

  // Test if a null station ID fails validation
  @Test
  public void testNullStationID() {
    assertFalse(aggregationServer.isValidStation(null));
  }

  // Test if the first PUT request with valid data creates a new entry
  @Test
  public void testFirstPutRequestWithValidData() {
    String putRequest = "PUT /weatherData HTTP/1.1\r\nServerID: server1\r\nLamportClock: 1\r\n\r\n{id:\"station1\", temp:20.5}";
    String response = aggregationServer.processRequest(putRequest);
    assertTrue(response.contains("201 HTTP_CREATED"));
  }

  // Test if the second PUT request with valid data updates an existing entry
  @Test
  public void testSecondPutRequestWithValidData() {
    String putRequest = "PUT /weatherData HTTP/1.1\r\nServerID: server1\r\nLamportClock: 1\r\n\r\n{id:\"station1\", temp:20.5}";
    aggregationServer.processRequest(putRequest); // First request
    String response = aggregationServer.processRequest(putRequest); // Second request
    assertTrue(response.contains("200 OK"));
  }

  // Test if a GET request with valid data returns the correct weather information
  @Test
  public void testGetRequestWithValidData() {
    JsonObject jsonObject = new JsonObject();
    jsonObject.addProperty("id", "station1");
    jsonObject.addProperty("temp", 20.5);
    aggregationServer.addWeatherData(jsonObject, 1, "server1");

    String getRequest = "GET /weatherData HTTP/1.1\r\nStationID: station1\r\nLamportClock: 1\r\n\r\n";
    String response = aggregationServer.processRequest(getRequest);
    assertTrue(response.contains("200 OK"));
  }

  // Test if a GET request without data returns a 204 No Content response
  @Test
  public void testGetRequestWithoutData() {
    String getRequest = "GET /weatherData HTTP/1.1\r\nStationID: unknown\r\nLamportClock: 1\r\n\r\n";
    String response = aggregationServer.processRequest(getRequest);
    assertTrue(response.contains("204 No Content"));
  }

  // Test if an invalid request method returns a 400 Bad Request response
  @Test
  public void testInvalidRequest() {
    String invalidRequest = "POST /weatherData HTTP/1.1\r\nServerID: server1\r\nLamportClock: 1\r\n\r\n{id:\"station1\", temp:20.5}";
    String response = aggregationServer.processRequest(invalidRequest);
    assertTrue(response.contains("400 Bad Request"));
  }

  // Test if data is gone after 30 seconds
  @Test
  public void testDataExpungingAfterThirtySeconds() throws InterruptedException {
    // Add weather data with a valid station ID
    JsonObject jsonObject = new JsonObject();
    jsonObject.addProperty("id", "station1");
    jsonObject.addProperty("temp", 20.5);
    aggregationServer.addWeatherData(jsonObject, 1, "server1");

    // Verify that the data is present immediately after adding
    String getRequest = "GET /weatherData HTTP/1.1\r\nStationID: station1\r\nLamportClock: 1\r\n\r\n";
    String response = aggregationServer.processRequest(getRequest);
    assertTrue(response.contains("200 OK"));
    assertTrue(response.contains("station1"));
    
    // bit more than 30
    Thread.sleep(31000); 

    String responseAfterExpunge = aggregationServer.processRequest(getRequest);
    
    //verify 204
    assertTrue(responseAfterExpunge.contains("204 No Content"));
  }
}
