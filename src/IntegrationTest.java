import org.junit.jupiter.api.*;
import com.google.gson.*;
import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;

public class IntegrationTest {

  private ContentServer contentServer;
  private AggregationServer aggregationServer;
  private GETClient client;
  private int port = 4567;

  @BeforeEach
  public void setup() {
    // Set up necessary components before each test
    aggregationServer = new AggregationServer(false);
    new Thread(() -> {
      aggregationServer.start(port);
      try {
        Thread.sleep(1000); // Sleep for 1000 milliseconds after starting
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }).start();
    contentServer = new ContentServer(false);
    client = new GETClient(false);
  }

  @AfterEach
  public void teardown() {
    // Clean up resources after each test
    aggregationServer.terminate();
    try {
      Thread.sleep(1000); // Sleep for 1000 milliseconds after starting
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    client.getNetworkHandler().closeResources();
    contentServer.terminateResources();
    try {
      Thread.sleep(1000); // Sleep for 1000 milliseconds after starting
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  // Test that the Lamport clock on the AggregationServer increases after data upload
  @Test
  public void testLamportClockIncreasesAfterUpload() throws InterruptedException {
    int initialClockServer = aggregationServer.getLamportClockTime();

    contentServer.loadWeatherDataFromOutside("src/weather_test.txt");
    contentServer.uploadData("localhost", port);

    Thread.sleep(1000); // Wait for the data to be processed

    int updatedClockServer = aggregationServer.getLamportClockTime();
    
    // Ensure the clock increases
    assertTrue(updatedClockServer > initialClockServer);
  }

  // Test that the client receives a valid Lamport clock value upon initializing
  @Test
  public void testClientReceivesLamportClockOnInitialization() throws IOException {
    int lamportClockFromServer = client.getNetworkHandler().initialiseClientSocket("localhost", port);
    assertTrue(lamportClockFromServer >= 0, "Client should receive a valid LamportClock timestamp");
  }

  // Test that the Lamport clock updates after handling multiple requests
  @Test
  public void testLamportClockUpdatesAfterMultipleRequests() throws InterruptedException {
    int initialClock = aggregationServer.getLamportClockTime();

    client.getData("localhost", port, "testStationID1");
    Thread.sleep(500);
    int afterFirstRequestClock = aggregationServer.getLamportClockTime();

    client.getData("localhost", port, "testStationID2");
    Thread.sleep(500);
    int afterSecondRequestClock = aggregationServer.getLamportClockTime();

    assertTrue(afterFirstRequestClock > initialClock, "Clock should update after first request");
    assertTrue(afterSecondRequestClock > afterFirstRequestClock, "Clock should update after second request");
  }

  // Test that the Lamport clock on the server updates after processing a request
  @Test
  public void testRequestProcessingUpdatesLamportClock() throws IOException, InterruptedException {
    String fileContent = JSONHandler.readFile("src/weather_test.txt");
    JsonObject parsedData = JSONHandler.parseTextToJSON(fileContent);
    contentServer.setWeatherData(parsedData);
    contentServer.uploadData("localhost", port);

    Thread.sleep(1000); // Wait for the data to be processed

    int newClockValue = aggregationServer.getLamportClockTime();
    assertTrue(newClockValue > 0, "Lamport clock should update after processing a request");
  }

  // Test that the Lamport clock synchronizes correctly across multiple threads
  @Test
  public void testLamportClockSynchronizationAcrossMultipleThreads() throws InterruptedException {
    int initialTime = aggregationServer.getLamportClockTime();

    // Simulate multiple client requests in parallel
    new Thread(() -> client.getData("localhost", port, "testStationID1")).start();
    new Thread(() -> client.getData("localhost", port, "testStationID2")).start();
    
    Thread.sleep(1000); // Wait for all requests to complete

    int updatedTime = aggregationServer.getLamportClockTime();
    assertTrue(updatedTime > initialTime, "Lamport clock should increase across multiple threads");
  }

  // Test that the client receives a valid clock value formatted correctly
  @Test
  public void testClientReceivesValidLamportClockFormat() throws IOException {
    int clockValue = client.getNetworkHandler().initialiseClientSocket("localhost", port);
    assertTrue(clockValue >= 0, "Client should receive a valid LamportClock timestamp");
  }

  // Test that the AggregationServer handles multiple requests and updates the clock properly
  @Test
  public void testAggregationServerHandlesAndUpdatesClockAfterMultipleRequests() throws InterruptedException {
    int initialTime = aggregationServer.getLamportClockTime();

    client.getData("localhost", port, "testStationID");
    Thread.sleep(500);

    int intermediateTime = aggregationServer.getLamportClockTime();
    assertTrue(intermediateTime > initialTime);

    client.getData("localhost", port, "anotherStationID");
    Thread.sleep(500);

    int finalTime = aggregationServer.getLamportClockTime();
    assertTrue(finalTime > intermediateTime);
  }
}
