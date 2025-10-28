import com.google.gson.*;

public class WeatherData implements Comparable<WeatherData> {
  // these are the fields for time, weather data, server ID
  private final int time;
  private final JsonObject data;
  private final String serverID;

  // constructor to initialise weather data; 
  // throws error if data or serverID is null
  public WeatherData(JsonObject data, int time, String serverID) {
    if (data == null || serverID == null) {
      throw new IllegalArgumentException("Error 400: data or serverID is null.");
    }

    // set the fields
    this.time = time;
    this.data = data;
    this.serverID = serverID;
  }

  // getter method to access the actual weather data
  public JsonObject getData() {
    return data;
  }

  // getter method for the lamport clock time
  public int getTime() {
    return time;
  }

  // getter for the server ID (note the 'getserverID' uses lowercase 's')
  public String getserverID() {
    return serverID;
  }

  // comparing weather data based on the lamport time; this is needed for sorting
  @Override
  public int compareTo(WeatherData other) {
    return Integer.compare(this.time, other.time);
  }

  // overriding toString to give a nice string format of weather data (including time and serverID)
  @Override
  public String toString() {
    return "LamportTime: " + time + ", serverID: " + serverID + ", Data: " + data.toString();
  }
}
