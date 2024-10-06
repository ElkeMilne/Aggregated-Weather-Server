import com.google.gson.*;

import java.util.UUID;

public class GETClient {
  private NetworkHandler networkHandler;  // handles network connections
  private final String serverID;  // unique id for this client
  private static final Gson gson = new Gson();  // gson for json handling
  private final LamportClock lamportClock;  // lamport clock for sync
  private static final String DEFAULT_HOST = "localhost";  // default server address
  private static final int DEFAULT_PORT = 4567;  // default port number

  public GETClient(boolean isTestMode) {
    this.networkHandler = new SocketNetworkHandler(isTestMode);  // create network handler
    this.serverID = UUID.randomUUID().toString();  // generate a unique id
    this.lamportClock = new LamportClock();  // initialize lamport clock
  }

  public void interpretResponse(JsonObject response) {
    // check if the response from the server is null
    if (response == null) {
      System.out.println("Error 400: No response from server.");  // print error if there's no response
      return;
    }
    printWeatherData(response);  // print the weather data if response is valid
  }

  public NetworkHandler getNetworkHandler() {
    return this.networkHandler;  // return the network handler
  }

  public LamportClock getLamportClock() {
    return this.lamportClock;  // return the lamport clock
  }

  public void printWeatherData(JsonObject response) {
    try {
      String weatherDataText = JSONHandler.parseJSONtoText(response);  // convert json response to text
      for (String line : weatherDataText.split("\n")) {  // split text into lines and print each
        System.out.println(line);
      }
    } catch (Exception e) {
      throw new RuntimeException("Error while converting JSON to text.", e);  // throw error if parsing fails
    }
  }

  public JsonObject getData(String serverName, int port, String stationID) {
    int currentTime = lamportClock.send();  // get current lamport clock time
    networkHandler.initialiseClientSocket(serverName, port);  // initialize connection to server
    String getRequest = generateRequestString(currentTime, stationID);  // build the request string
    try {
      String response = networkHandler.receiveDataFromServer(serverName, port, getRequest);  // send request and get response
      System.out.println("Response: " + getRequest);  // log the response
      return handleServerResponse(response);  // process and return the response
    } catch (Exception e) {
      System.out.println("Error 400: " + e.getMessage());  // catch and log errors
      e.printStackTrace();
      return null;
    }
  }

  public String generateRequestString(int currentTime, String stationID) {
    // generate a GET request string with headers and optional station id
    return "GET /weather.json HTTP/1.1\r\n" +
        "ServerID: " + serverID + "\r\n" +
        "LamportClock: " + currentTime + "\r\n" +
        (stationID != null ? "StationID: " + stationID + "\r\n" : "") +
        "\r\n";
  }

  public JsonObject handleServerResponse(String responseStr) {
    // check for a 500 error in the response
    if (responseStr.startsWith("500")) {
      System.out.println("Error 500: Incorrect format response");  // print error if response format is bad
      return null;
    }
    // extract the json content from the response
    JsonObject jsonObject = gson.fromJson(JSONHandler.extractJSONContent(responseStr), JsonObject.class);
    if (jsonObject == null) {
      System.out.println("Error 400: No JSON object in response.");  // print error if no json found
      return null;
    }
    return jsonObject;  // return the parsed json object
  }

  public static void main(String[] args) {

    String serverName;
    int port;
    String stationID = null;

    if (args.length >= 1) {
      String[] parts = args[0].split(":");

      // split the input to get host and port, if formatted correctly
      if (parts.length == 2) {
        serverName = parts[0].replace("http://", "");  // remove "http://" if present
        port = Integer.parseInt(parts[1].split("/")[0]);  // extract port number

        if (args.length == 2) {
          stationID = args[1];  // if there's a second argument, treat it as station id
        }
      } else {
        System.err.println("Invalid argument format.");  // if formatting is bad, print an error
        return;
      }
    } else {
      // use default values if no arguments provided
      serverName = DEFAULT_HOST;
      port = DEFAULT_PORT;
    }

    // initialise network handler and client
    GETClient client = new GETClient(false);

    // get and interpret the data from the server
    JsonObject response = client.getData(serverName, port, stationID);
    client.interpretResponse(response);  // print the weather data
    client.networkHandler.closeResources();  // close the network resources
  }
}
