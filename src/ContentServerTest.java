import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;

public class ContentServerTest {

  @Mock
  private NetworkHandler networkHandler;

  @Mock
  private LamportClock lamportClock;

  private ContentServer server;

  @BeforeEach
  public void initialise() {
    MockitoAnnotations.initMocks(this);
    server = new ContentServer(true);
    networkHandler = server.getNetworkHandler();
    lamportClock = server.getLamportClock();
  }

  // Tests that weather data is correctly set and retrieved from the server
  @Test
  public void testSetAndRetrieveWeatherData() {
    JsonObject fakeData = new JsonObject();
    fakeData.addProperty("temperature", "20C");
    server.setWeatherData(fakeData);
    assertEquals(fakeData, server.getWeatherData(), "Weather data should be set and retrieved correctly.");
  }

  // Tests that the Lamport Clock increments correctly after adjusting its time
  @Test
  public void testAdjustLamportClock() {
    server.adjustClock(5);
    assertEquals(6, server.getLamportClock().getTime(), "Lamport Clock should be adjusted correctly.");
  }

  // Tests that the weather data is correctly built and sent to the specified host and port
  @Test
  public void testBuildAndSendWeatherDataRequest() {
    String host = "localhost";
    int port = 4567;
    String path = "src/weather_test.txt";

    server.loadWeatherDataFromOutside(path);
    server.uploadData(host, port);
    server.getLamportClock().send();
    assertEquals(1, server.getLamportClock().getTime(), "Lamport Clock should be incremented after sending request.");
  }

  // Tests that the Lamport Clock is updated when processing a response with a LamportClock header
  @Test
  public void testProcessResponseWithLamportClock() {
    server.processResponse("LamportClock: 10\r\nHTTP/1.1 200 OK");
    assertEquals(11, server.getLamportClock().getTime(), "Lamport Clock should be updated from response header.");
  }

  // Tests that the server handles successful data upload responses correctly
  @Test
  public void testProcessResponseDataUploaded() {
    server.processResponse("HTTP/1.1 200 OK");
    // Check if "Data uploaded." was printed.
    // Placeholder for log verification if needed.
  }

  // Tests that the server handles failed data upload responses correctly
  @Test
  public void testProcessResponseFailedToPushData() {
    server.processResponse("HTTP/1.1 404 Not Found");
    // Check if "Failed to push data." was printed.
    // Placeholder for log verification if needed.
  }

  // Tests that loading weather data with an invalid path results in null weather data
  @Test
  public void testLoadWeatherDataWithInvalidPath() {
    String path = "src/weather_test_wrong.txt";

    server.loadWeatherDataFromOutside(path);
    JsonObject weatherData = server.getWeatherData();
    assertNull(weatherData, "Weather data should be null for invalid path.");
  }

  // Tests that the server's resources (such as sockets) are terminated and closed properly
  @Test
  public void testTerminateResourcesClosesSocket() {
    server.terminateResources();
    assertTrue(networkHandler.checkClientSocketIsClosed(), "Socket should be closed after terminating resources.");
  }

  // Tests that the correct NetworkHandler instance is returned by the server
  @Test
  public void testGetNetworkHandler() {
    assertEquals(networkHandler, server.getNetworkHandler(), "Should retrieve correct NetworkHandler instance.");
  }

  // Tests that weather data is uploaded and correctly parsed from an external source without a connected socket
  @Test
  public void testUploadWeatherDataWithoutSocket() {
    String host = "localhost";
    int port = 4567;
    String path = "src/weather_test.txt";

    server.loadWeatherDataFromOutside(path);
    server.uploadData(host, port);

    // Simulate wait time to mimic real data upload
    try {
      Thread.sleep(10000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    JsonObject weatherData = server.getWeatherData();
    assertEquals("IDS60901", weatherData.get("id").getAsString(), "Weather data should have correct ID after upload.");
  }

  // Tests that loading weather data with an invalid file path results in null weather data
  @Test
  public void testUploadWeatherDataWithInvalidFilePath() {
    String path = "src/weather_test_wrong.txt";

    server.loadWeatherDataFromOutside(path);
    JsonObject weatherData = server.getWeatherData();
    assertNull(weatherData, "Weather data should be null for invalid file path.");
  }

  // Tests that loading weather data with the wrong file format results in null weather data
  @Test
  public void testUploadWeatherDataWithWrongFormat() {
    String path = "src/weather_test_wrong_format.txt";

    server.loadWeatherDataFromOutside(path);
    JsonObject weatherData = server.getWeatherData();
    assertNull(weatherData, "Weather data should be null for wrong format file.");
  }
}
