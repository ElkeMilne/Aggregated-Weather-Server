import com.google.gson.*;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ContentServer {
  private final NetworkHandler networkHandler;  // handles network stuff
  private JsonObject weatherData;  // stores the weather data
  private final ScheduledExecutorService dataUploadScheduler;  // scheduler for data uploads
  private final String serverID;  // a unique id for the server
  private final LamportClock lamportClock;  // our lamport clock for synchronizing events
  private static final int DEFAULT_PORT = 4567;  // default port number for the server
  private static final String DEFAULT_HOST = "localhost";  // default hostname
  private static final String DEFAULT_PATH = "src/input.txt";  // default path to weather data

  public ContentServer(boolean isTestMode) {
    this.serverID = UUID.randomUUID().toString();  // generate a unique id for the server
    this.lamportClock = new LamportClock();  // initialize the lamport clock
    this.dataUploadScheduler = Executors.newScheduledThreadPool(1);  // set up a scheduler for uploads
    this.networkHandler = new SocketNetworkHandler(isTestMode);  // create a network handler
  }

  public LamportClock getLamportClock() {
    return this.lamportClock;  // return the lamport clock
  }

  public void setWeatherData(JsonObject data) {
    this.weatherData = data;  // set the weather data for the server
  }

  public NetworkHandler getNetworkHandler() {
    return this.networkHandler;  // return the network handler
  }

  public void uploadData(String host, int port) {
    ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);  // create a new scheduler
    
    Runnable pushTask = () -> processPush(host, port);  // define the task for uploading data
    
    executor.scheduleAtFixedRate(pushTask, 0, 30, TimeUnit.SECONDS);  // schedule it to run every 30 seconds
  }

  private int openConnection(String host, int port) throws Exception {
    return networkHandler.initialiseClientSocket(host, port);  // open a connection to the server
  }

  public void adjustClock(int newTime) {
    lamportClock.receive(newTime);  // adjust the lamport clock with the new time
  }

  public void processPush(String host, int port) {
    try {
      int serverLamportClock = networkHandler.initialiseClientSocket(host, port);  // get the lamport clock from the server
      lamportClock.receive(serverLamportClock);  // update our lamport clock
      int clockValue = openConnection(host, port);  // open the connection again (for some reason)

      if (clockValue == -1) {  // if the clock value is invalid, print an error and stop
        System.out.println("Error 400: Bad Request - No Lamport Clock");
        return;
      }

      adjustClock(clockValue);  // adjust the clock based on the server's value
      String request = buildRequest(host);  // build the request to send to the server
      String serverResponse = transceive(host, port, request);  // send the request and get the response
      
      if (serverResponse != null) {  // if there's a response, process it
        processResponse(serverResponse);
      }

    } catch (Exception exc) {  // catch any errors that happen during the process
      System.out.println("Connection error: " + exc.getMessage());  // print the error message
      System.out.println("Reloading...");
      retryPush(host, port);  // retry after a delay
    }
  }

  public String buildRequest(String host) {
    StringBuilder headers = new StringBuilder();  // building the HTTP request headers

    headers.append("PUT /uploadData HTTP/1.1\r\n")  // the method and resource
           .append("Host: ").append(host).append("\r\n")  // specify the host
           .append("ServerID: ").append(serverID).append("\r\n")  // add the server ID
           .append("LamportClock: ").append(lamportClock.getTime()).append("\r\n")  // add the lamport clock time
           .append("Content-Type: application/json\r\n")  // we're sending JSON data
           .append("Content-Length: ").append(weatherData.toString().length()).append("\r\n\r\n");  // specify the data length

    // now we append the actual weather data to the request body
    headers.append(weatherData);

    return headers.toString();  // return the full request as a string
  }

  private String transceive(String host, int port, String data) throws Exception {
    return networkHandler.sendDataToServer(host, port, data);  // send the data to the server and get the response
  }

  public void processResponse(String res) {
    String[] headerLines = res.split("\r\n");  // split the response headers into lines
    for (String line : headerLines) {  // loop through each header line
      if (line.startsWith("LamportClock: ")) {  // if the response has a lamport clock value
        lamportClock.receive(Integer.parseInt(line.split(": ")[1]));  // update our clock with the value
        break;
      }
    }

    if (res.contains("200") || res.contains("201")) {  // if the response indicates success
      System.out.println("\nData uploaded.");  // print a success message
    } else {
      System.out.println("Failed to push data. Server says: " + res);  // print an error message if the upload failed
    }
  }

  private void retryPush(String host, int port) {
    dataUploadScheduler.schedule(new Runnable() {  // schedule a retry after 15 seconds
      @Override
      public void run() {
        processPush(host, port);  // try to push the data again
      }
    }, 15, TimeUnit.SECONDS);
  }

  public void terminateResources() {
    dataUploadScheduler.shutdown();  // shut down the scheduler
    networkHandler.closeResources();  // close network resources
  }

  public void loadWeatherDataFromOutside(String path) {
    System.out.println("Loading weather data from outside: " + path);  // log that we're loading data from a file
    try {
      String fileContent = JSONHandler.readFile(path);  // read the file content
      JsonObject parsedData = JSONHandler.parseTextToJSON(fileContent);  // parse the content as JSON
      this.setWeatherData(parsedData);  // set the parsed data as the weather data
    } catch (Exception e) {  // catch any errors while loading data
      System.out.println("Error 400: " + e.getMessage());  // log the error message
      return;
    }
  }

  public JsonObject getWeatherData() {
    return this.weatherData;  // return the current weather data
  }

  public static void main(String[] args) {
    try {
      String host = DEFAULT_HOST;
      int port = DEFAULT_PORT;
      String path = DEFAULT_PATH;
      if (args.length == 3) {  // if there are three arguments, use them for host, port, and path
        host = args[0];
        port = Integer.parseInt(args[1]);
        path = args[2];
      } else if (args.length == 2) {  // if two arguments, just host and port
        host = args[0];
        port = Integer.parseInt(args[1]);
      } else if (args.length == 1) {  // if one argument, just host
        host = args[0];
      }
      ContentServer server = new ContentServer(false);  // create a new content server instance

      String fileContent = JSONHandler.readFile(path);  // read the weather data file
      JsonObject parsedData = JSONHandler.parseTextToJSON(fileContent);  // parse the file content into JSON
      server.setWeatherData(parsedData);  // set the parsed data as the weather data
      server.uploadData(host, port);  // start uploading the data

      // Get the current Runtime Environment
      Runtime runtime = Runtime.getRuntime();

      // Define what the new Thread will do
      Thread shutdownHook = new Thread(() -> {
        // This will be executed when the JVM shuts down
        server.terminateResources();  // clean up resources when the JVM shuts down
      });

      // Register the new Thread to run upon JVM shutdown
      runtime.addShutdownHook(shutdownHook);  // register the shutdown hook
    } catch (Exception e) {
      System.out.println("Error 400: " + e.getMessage());  // log any errors that occur
      return;
    }
  }
}
