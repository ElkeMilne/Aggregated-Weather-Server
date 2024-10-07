import java.io.File;
import java.io.PrintWriter;
import java.lang.reflect.Type;

import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;

public class AggregationServer {
    private NetworkHandler networkHandler;
    private static final Gson gson = new Gson(); // json library to convert data
    private LinkedBlockingQueue<Socket> clientRequestQueue = new LinkedBlockingQueue<>(); // queue to hold client requests
    private Map<String, PriorityQueue<WeatherData>> weatherDataMap = new ConcurrentHashMap<>(); // store weather data
    private Map<String, Long> timeMap = new ConcurrentHashMap<>(); // track timestamps
    private volatile boolean isServerDown = false; // flag to stop the server
    private LamportClock lamportClock = new LamportClock(); // clock for synchronization
    private Thread clientAcceptThread; // thread to accept new client connections
    private ScheduledExecutorService dataSaveScheduler; // periodic task to save data
    private ScheduledExecutorService oldDataCleanupScheduler; // periodic task to clean old data
    private static final int DEFAULT_PORT = 4567; // default port number for the server

    public AggregationServer(boolean isTestMode) {
        this.networkHandler = new SocketNetworkHandler(isTestMode); // init the network handler
    }

    public void start(int portNumber) {
        System.out.println("server start");
        networkHandler.initialiseServer(portNumber); // start the server at the specified port

        // schedule periodic task to save data every 60 seconds
        dataSaveScheduler = Executors.newScheduledThreadPool(1);
        dataSaveScheduler.scheduleAtFixedRate(this::saveDataToFile, 0, 60, TimeUnit.SECONDS);

        loadDataFromFile(); // load any existing data

        // schedule periodic task to clean up old entries every 31 seconds
        oldDataCleanupScheduler = Executors.newScheduledThreadPool(1);
        oldDataCleanupScheduler.scheduleAtFixedRate(this::cleanupOldEntries, 0, 31, TimeUnit.SECONDS);

        initialiseClientAcceptThread(); // start accepting client connections
        processClientRequests(); // process incoming client requests
    }

    public JsonObject getWeatherData(String stationID) {
        // return the latest weather data for a station, or null if none available
        return Optional.ofNullable(weatherDataMap.get(stationID))
                .filter(queue -> !queue.isEmpty())
                .map(PriorityQueue::peek)
                .map(WeatherData::getData)
                .orElse(null);
    }

    public int getLamportClockTime() {
        return lamportClock.getTime(); // return current Lamport clock time
    }

    public void loadDataFromFile() {
        // load weather and time data from files
        Map<String, PriorityQueue<WeatherData>> loadedQueue = readDataFile(
                "src" + File.separator + "data.json",
                "src" + File.separator + "initData.json",
                new TypeToken<ConcurrentHashMap<String, PriorityQueue<WeatherData>>>() {}.getType());

        Map<String, Long> loadedTimeService = readDataFile(
                "src" + File.separator + "timeData.json",
                "src" + File.separator + "initTimeData.json",
                new TypeToken<ConcurrentHashMap<String, Long>>() {}.getType());

        this.weatherDataMap = loadedQueue; // update map with loaded data
        this.timeMap = loadedTimeService;  // update map with loaded timestamps
    }

    private void cleanupOldEntries() {
        long currentTime = System.currentTimeMillis(); // get current time

        // find and remove server IDs that are older than 31 seconds
        Set<String> oldServerIDs = findOldServerIDs(currentTime);
        removeOldServerEntries(oldServerIDs);

        if (timeMap.isEmpty()) {
            weatherDataMap.clear(); // if no timestamps left, clear weather data
            return;
        }

        cleanUpWeatherData(oldServerIDs); // clean up the weather data for the old servers
    }

    private Set<String> findOldServerIDs(long currentTime) {
        // find server IDs that haven't sent data in the last 31 seconds
        return timeMap.keySet().stream()
                .filter(entry -> currentTime - timeMap.get(entry) > 31000)
                .collect(Collectors.toSet());
    }

    private void removeOldServerEntries(Set<String> oldServerIDs) {
        oldServerIDs.forEach(timeMap::remove); // remove old server entries
    }

    private synchronized void cleanUpWeatherData(Set<String> oldServerIDs) {
        // clean up old weather data related to the removed server IDs
        weatherDataMap.forEach((stationID, queue) -> {
            queue.removeIf(weatherData -> oldServerIDs.contains(weatherData.getserverID()));
            if (queue.isEmpty()) {
                weatherDataMap.remove(stationID); // remove station if no data is left
            }
        });
    }

    private void processClientRequests() {
        System.out.println("Processing client requests...\n");

        try {
            processRequestsLoop(); // loop to process requests
        } catch (Exception e) {
            logException(e); // log any exception
        } finally {
            closeServerResources(); // close resources after stopping
        }
    }

    private void processRequestsLoop() throws Exception {
        // loop to wait for and handle client connections
        while (!isServerDown) {
            Socket clientSocket = waitForClient();
            if (clientSocket != null) {
                handleNewConnection(clientSocket);
            }
        }
    }

    private void handleNewConnection(Socket clientSocket) {
        System.out.println("New connection\n");
        handleClientSocket(clientSocket); // process the new connection
    }

    private void logException(Exception e) {
        System.err.println("An error occurred while processing client requests:");
        e.printStackTrace(); // log the stack trace
    }

    private void closeServerResources() {
        System.out.println("Closing server resources...\n");
        networkHandler.closeResources(); // close network resources
    }

    private Socket waitForClient() throws InterruptedException {
        // wait for client requests (timeout after 10ms)
        return isServerDown ? null : clientRequestQueue.poll(10, TimeUnit.MILLISECONDS);
    }

    private void handleClientSocket(Socket clientSocket) {
        try {
            // receive and process the client's request
            String requestData = receiveDataFromClient(clientSocket);
            if (requestData != null) {
                String responseData = processRequest(requestData);
                sendResponseToClient(responseData, clientSocket);
            }
        } catch (IOException e) {
            logError("Error handling client socket: " + e.getMessage(), e);
        } finally {
            closeSocket(clientSocket); // close the socket when done
        }
    }

    private String receiveDataFromClient(Socket clientSocket) throws IOException {
        // wait for the client to send data
        return networkHandler.waitForDataFromClient(clientSocket);
    }

    private void sendResponseToClient(String responseData, Socket clientSocket) throws IOException {
        // send the server's response back to the client
        networkHandler.sendResponseToClient(responseData, clientSocket);
    }

    private void closeSocket(Socket clientSocket) {
        // close the client socket if it's still open
        if (clientSocket != null && !clientSocket.isClosed()) {
            try {
                clientSocket.close();
            } catch (IOException e) {
                logError("Error closing client socket: " + e.getMessage(), e);
            }
        }
    }

    private void logError(String message, Exception e) {
        // log an error message with the stack trace
        System.err.println(message);
        e.printStackTrace();
    }

    private <T> T readDataFile(String file, String initialDataFilePath, Type type) {
        // try to read data from the main file, or fall back to the initial file
        T result = readFile(file, type);
        if (result == null) {
            result = readFile(initialDataFilePath, type);
        }
        return result;
    }

    private <T> T readFile(String filePath, Type type) {
        try {
            // read JSON data from the file
            String jsonData = Files.readString(Paths.get(filePath));
            return gson.fromJson(jsonData, type);
        } catch (IOException e) {
            System.err.println("Error reading file: " + filePath);
            e.printStackTrace();
            return null;
        }
    }

    public String processRequest(String inputData) {
        // process the client's request data
        List<String> lineList = parseInputData(inputData);
        String requestMethod = extractRequestMethod(lineList);

        Map<String, String> headers = extractHeaders(lineList);
        String bodyContent = extractContent(lineList);

        return handleRequestMethod(requestMethod, headers, bodyContent);
    }

    private String extractContent(List<String> lineList) {
        // extract the content part of the request
        int startIndex = lineList.indexOf("") + 1;
        if (startIndex <= 0 || startIndex >= lineList.size()) {
            return "";
        }
        return String.join("", lineList.subList(startIndex, lineList.size()));
    }

    private List<String> parseInputData(String inputData) {
        return Arrays.asList(inputData.split("\r\n")); // split the request into lines
    }

    private String extractRequestMethod(List<String> lineList) {
        return lineList.isEmpty() ? "" : lineList.get(0).split(" ")[0].toUpperCase(); // extract the HTTP method
    }

    private String handleRequestMethod(String requestMethod, Map<String, String> headers, String bodyContent) {
        // handle GET or PUT requests
        switch (requestMethod) {
            case "PUT":
                return processPut(headers, bodyContent);
            case "GET":
                return processGet(headers, bodyContent);
            default:
                return constructResponse("400 Bad Request", null);
        }
    }

    private String processGet(Map<String, String> headers, String content) {
        // process a GET request for weather data
        String stationKey = headers.get("StationID");
        if (stationKey == null || stationKey.isEmpty()) {
            return constructResponse("204 No Content", null);
        }

        JsonObject weatherData = getWeatherData(stationKey);
        if (weatherData == null) {
            return constructResponse("204 No Content", null);
        }

        return constructResponse("200 OK", weatherData.toString());
    }

    private synchronized void saveDataToFile() {
        // save weather data and timestamps to files
        String dataFilePath = getFilePath("data.json");
        String initDataFilePath = getFilePath("initData.json");
        saveWeatherData(weatherDataMap, dataFilePath, initDataFilePath);

        String timeFilePath = getFilePath("timeData.json");
        String initTimeFilePath = getFilePath("initTimeData.json");
        saveWeatherData(weatherDataMap, timeFilePath, initTimeFilePath);
    }

    private String getFilePath(String fileName) {
        return "src" + File.separator + fileName; // generate the file path
    }

    private synchronized void saveWeatherData(
            Map<String, PriorityQueue<WeatherData>> data,
            String filePath,
            String tempFilePath) {

        // write the data to a temp file, then move it to the final location
        writeJsonToFile(data, tempFilePath);
        moveTempFile(tempFilePath, filePath);
    }

    private void writeJsonToFile(Map<String, PriorityQueue<WeatherData>> data, String filePath) {
        try {
            // convert the data to JSON and write it to a file
            String jsonData = gson.toJson(data);
            Files.write(Paths.get(filePath), jsonData.getBytes());
        } catch (IOException e) {
            System.err.println("Failed to write JSON to file: " + filePath);
            e.printStackTrace();
        }
    }

    private void moveTempFile(String tempFilePath, String finalFilePath) {
        try {
            // move the temp file to the final location atomically
            Files.move(Paths.get(tempFilePath),
                    Paths.get(finalFilePath),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("Failed to move file from " + tempFilePath + " to " + finalFilePath);
            e.printStackTrace();
        }
    }

    private Map<String, String> extractHeaders(List<String> lineList) {
        // extract headers from the request
        return lineList.stream()
                .filter(line -> line.contains(": "))
                .map(line -> line.split(": ", 2))
                .collect(Collectors.toMap(parts -> parts[0], parts -> parts[1]));
    }

    private String processPut(Map<String, String> headers, String content) {
        String serverKey = headers.get("ServerID");

        if (!isValidSource(serverKey)) {
            return constructBadRequestResponse(); // invalid server ID
        }

        int lamportTimestamp = extractLamportTime(headers);

        if (!processData(content, lamportTimestamp, serverKey)) {
            return constructBadRequestResponse(); // failed to process the data
        }

        return generateResponse(serverKey);
    }

    private String constructBadRequestResponse() {
        return constructResponse("400 Bad Request", null); // return a 400 response
    }

    private void initialiseClientAcceptThread() {
        System.out.println("Initializing accept thread...\n");

        clientAcceptThread = new Thread(this::acceptClientConnections);
        clientAcceptThread.start(); // start the thread to accept clients
    }

    private void acceptClientConnections() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                handleIncomingClient(); // wait for new client connections
            } catch (IOException e) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                logIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
    }

    private void handleIncomingClient() throws IOException, InterruptedException {
        Socket clientSocket = networkHandler.acceptIncomingClient(); // accept a new client

        if (clientSocket != null) {
            sendLamportClockToClient(clientSocket); // send the current clock time to the client
            clientRequestQueue.put(clientSocket); // add the client to the request queue
            System.out.println("Added connection to request queue\n");
        }
    }

    private void sendLamportClockToClient(Socket clientSocket) throws IOException {
        // send the Lamport clock value to the client
        String clockValue = "LamportClock: " + lamportClock.getTime() + "\r\n";
        PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
        out.println(clockValue);
        out.flush();
    }

    private void logIOException(IOException e) {
        System.err.println("IOException occurred during client acceptance.");
        e.printStackTrace();
    }

    public boolean isValidStation(String stationId) {
        // check if a station ID is valid
        return stationId != null && !stationId.isEmpty();
    }

    private String constructResponse(String status, String json) {
        // construct an HTTP response with a status and optional JSON content
        StringBuilder responseBuilder = new StringBuilder();

        appendStatusLine(responseBuilder, status);
        appendLamportClock(responseBuilder);

        if (json != null) {
            appendJsonHeaders(responseBuilder, json);
        }

        return responseBuilder.toString();
    }

    private void appendStatusLine(StringBuilder responseBuilder, String status) {
        // append the status line to the response
        responseBuilder.append("HTTP/1.1 ").append(status).append("\r\n");
    }

    private void appendLamportClock(StringBuilder responseBuilder) {
        // append the current Lamport clock value to the response
        responseBuilder.append("LamportClock: ").append(lamportClock.send()).append("\r\n");
    }

    private void appendJsonHeaders(StringBuilder responseBuilder, String json) {
        // append JSON content headers and the content itself to the response
        responseBuilder.append("Content-Type: application/json\r\n");
        responseBuilder.append("Content-Length: ").append(json.length()).append("\r\n\r\n");
        responseBuilder.append(json);
    }

    private int extractLamportTime(Map<String, String> headers) {
        // extract and update the Lamport clock time from the request headers
        String lamportValue = headers.getOrDefault("LamportClock", "-1");

        int lamportTime = parseLamportTime(lamportValue);
        lamportClock.receive(lamportTime);

        return lamportClock.getTime(); // return the updated clock time
    }

    private int parseLamportTime(String lamportValue) {
        try {
            return Integer.parseInt(lamportValue); // parse the clock time
        } catch (NumberFormatException e) {
            System.err.println("Invalid LamportClock value: " + lamportValue);
            return -1;
        }
    }

    private boolean isQueueEmpty(PriorityQueue<WeatherData> queue) {
        // check if the weather data queue is empty
        return Optional.ofNullable(queue).map(PriorityQueue::isEmpty).orElse(true);
    }

    private Optional<WeatherData> locateWeatherData(PriorityQueue<WeatherData> queue, int lamportTime) {
        if (queue == null) {
            return Optional.empty();
        }

        // find the first weather data entry that matches the Lamport time
        return queue.stream()
                .filter(data -> data.getTime() <= lamportTime)
                .findFirst();
    }

    private boolean isValidSource(String serverID) {
        // check if a server ID is valid
        return serverID != null && !serverID.isEmpty();
    }

    private boolean processData(String content, int lamportTime, String serverID) {
        try {
            // parse the weather data and add it to the queue
            JsonObject weatherDataJSON = gson.fromJson(content, JsonObject.class);
            String stationID = extractID(weatherDataJSON);
            WeatherData newWeatherData = new WeatherData(weatherDataJSON, lamportTime, serverID);
            weatherDataMap.computeIfAbsent(stationID, k -> new PriorityQueue<>()).add(newWeatherData);
            return true;
        } catch (JsonParseException e) {
            System.err.println("JSON Parsing Error: " + e.getMessage());
            return false;
        }
    }

    private String generateResponse(String serverID) {
        // generate a response based on whether the request is new or delayed
        long currentTimestamp = System.currentTimeMillis();
        Long lastTimestamp = timeMap.get(serverID);
        timeMap.put(serverID, currentTimestamp);

        if (isNewOrDelayedRequest(lastTimestamp, currentTimestamp)) {
            return constructResponse("201 HTTP_CREATED", null);
        } else {
            return constructResponse("200 OK", null);
        }
    }

    private String findStationId(Map<String, String> headers) {
        // find the station ID from the headers or default to the first station
        String stationId = headers.get("StationID");
        if (stationId != null && !stationId.isEmpty()) {
            return stationId;
        }
        return weatherDataMap.keySet().stream().findFirst().orElse(null);
    }

    private boolean isNewOrDelayedRequest(Long lastTimestamp, long currentTimestamp) {
        // check if the request is new or delayed (over 31 seconds old)
        return lastTimestamp == null || (currentTimestamp - lastTimestamp) > 31000;
    }

    public boolean addWeatherData(JsonObject weatherDataJSON, int lamportTime, String serverID) {
        String id = extractID(weatherDataJSON);

        if (!isValidStation(id)) {
            return false; // invalid station ID
        }
        // add the new weather data to the queue
        weatherDataMap.computeIfAbsent(id, k -> new PriorityQueue<>())
                .add(new WeatherData(weatherDataJSON, lamportTime, serverID));
        return true;
    }

    public NetworkHandler getNetworkHandler() {
        return this.networkHandler; // return the network handler
    }

    public String extractID(JsonObject weatherDataJSON) {
        return weatherDataJSON.has("id") ? weatherDataJSON.get("id").toString().replace("\"", "") : null;
    }

    public void terminate() {
        markShutdown(); // mark the server as shutting down

        haltThread(clientAcceptThread); // stop the client accept thread
        stopScheduledTask(dataSaveScheduler, 5); // stop the data save task
        stopScheduledTask(oldDataCleanupScheduler, 60); // stop the cleanup task

        System.out.println("Server termination initiated...");
    }

    private void markShutdown() {
        this.isServerDown = true; // set the server down flag
    }

    public boolean getisServerDown() {
        return this.isServerDown; // return the server down flag
    }

    private void haltThread(Thread t) {
        if (t != null) {
            t.interrupt(); // interrupt the thread
        }
    }

    private void stopScheduledTask(ExecutorService scheduler, int timeoutSeconds) {
        if (scheduler == null) {
            return;
        }

        scheduler.shutdown(); // shutdown the scheduled task

        try {
            // wait for the task to finish, then forcefully stop it if necessary
            if (!scheduler.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException ex) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) {
        int port;
        if (args.length == 0) {
            port = DEFAULT_PORT; // use the default port if none provided
        } else {
            port = Integer.parseInt(args[0]); // use the specified port
        }
        AggregationServer server = new AggregationServer(false);
        server.start(port); // start the server
    }
}
