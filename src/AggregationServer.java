
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
    private static final Gson gson = new Gson();
    private LinkedBlockingQueue<Socket> clientRequestQueue = new LinkedBlockingQueue<>();  // thread-safe queue for client requests
    private Map<String, PriorityQueue<WeatherData>> weatherDataMap = new ConcurrentHashMap<>();  // thread-safe map to store weather data
    private Map<String, Long> timeMap = new ConcurrentHashMap<>();  // to track time for each server ID
    private volatile boolean isServerDown = false;  // used to shut down the server safely
    private LamportClock lamportClock = new LamportClock();  // lamport clock instance
    private Thread clientAcceptThread;  // thread to handle incoming clients
    private ScheduledExecutorService dataSaveScheduler;  // scheduler to periodically save data to file
    private ScheduledExecutorService oldDataCleanupScheduler;  // scheduler to clean old weather data
    private static final int DEFAULT_PORT = 4567;  // default port number

    public AggregationServer(boolean isTestMode) {
        this.networkHandler = new SocketNetworkHandler(isTestMode); // init the network handler
    }

    public void start(int portNumber) {
        System.out.println("server start");
        networkHandler.initialiseServer(portNumber);  // initializing server

        dataSaveScheduler = Executors.newScheduledThreadPool(1);
        dataSaveScheduler.scheduleAtFixedRate(this::saveDataToFile, 0, 60, TimeUnit.SECONDS);  // saving data every 60 seconds

        loadDataFromFile();  // load any existing data from file

        oldDataCleanupScheduler = Executors.newScheduledThreadPool(1);
        oldDataCleanupScheduler.scheduleAtFixedRate(this::cleanupOldEntries, 0, 21, TimeUnit.SECONDS);  // clean old data every 21 seconds

        initialiseClientAcceptThread();  // start accepting client connections

        processClientRequests();  // process requests from clients
    }

    public JsonObject getWeatherData(String stationID) {
        return Optional.ofNullable(weatherDataMap.get(stationID)) // grab weather data for station
                .filter(queue -> !queue.isEmpty()) // ensure it's not empty
                .map(PriorityQueue::peek) // get the first (latest) data
                .map(WeatherData::getData) // return the actual data
                .orElse(null);  // if no data, return null
    }

    public int getLamportClockTime() {
        return lamportClock.getTime();  // just return the current lamport time
    }

    public synchronized void loadDataFromFile() {
        // load data and timestamps from files
        Map<String, PriorityQueue<WeatherData>> loadedQueue = readDataFile(
                "src" + File.separator + "data.json",
                "src" + File.separator + "initData.json",
                new TypeToken<ConcurrentHashMap<String, PriorityQueue<WeatherData>>>() {
                }.getType());

        Map<String, Long> loadedTimeService = readDataFile(
                "src" + File.separator + "timeData.json",
                "src" + File.separator + "initTimeData.json",
                new TypeToken<ConcurrentHashMap<String, Long>>() {
                }.getType());

        this.weatherDataMap = loadedQueue;  // update the weather data map
        this.timeMap = loadedTimeService;  // update the time map
    }

    private synchronized void cleanupOldEntries() {
        long currentTime = System.currentTimeMillis();  // get current time

        // find and remove server IDs that are older than 31 seconds
        Set<String> oldServerIDs = findOldServerIDs(currentTime);
        removeOldServerEntries(oldServerIDs);

        // Clean up weather data map if no valid servers remain
        if (timeMap.isEmpty()) {
            weatherDataMap.clear();  // clear the weather data map if no servers left
            return;
        }

        // Remove old weather data associated with old server IDs
        cleanUpWeatherData(oldServerIDs);
    }

    private Set<String> findOldServerIDs(long currentTime) {
        // find server IDs that haven't updated in a while
        return timeMap.keySet().stream()
                .filter(entry -> currentTime - timeMap.get(entry) > 20000) // 20-second threshold
                .collect(Collectors.toSet());
    }

    private void removeOldServerEntries(Set<String> oldServerIDs) {
        oldServerIDs.forEach(timeMap::remove);  // remove old server IDs from time map
    }

    private void cleanUpWeatherData(Set<String> oldServerIDs) {
        weatherDataMap.forEach((stationID, queue) -> {
            queue.removeIf(weatherData -> oldServerIDs.contains(weatherData.getserverID()));  // remove data from old servers
            if (queue.isEmpty()) {
                weatherDataMap.remove(stationID);  // remove station if no data is left
            }
        });
    }

    private void processClientRequests() {
        System.out.println("Processing client requests...\n");

        try {
            processRequestsLoop();  // loop to process client requests
        } catch (Exception e) {
            logException(e);  // log any exceptions
        } finally {
            closeServerResources();  // make sure to close resources in the end
        }
    }

    private void processRequestsLoop() throws Exception {
        while (!isServerDown) {
            Socket clientSocket = waitForClient();  // wait for client to connect
            if (clientSocket != null) {
                handleNewConnection(clientSocket);  // handle the new connection
            }
        }
    }

    private void handleNewConnection(Socket clientSocket) {
        System.out.println("New connection\n");
        handleClientSocket(clientSocket);  // handle incoming client socket
    }

    private void logException(Exception e) {
        System.err.println("An error occurred while processing client requests:");
        e.printStackTrace();  // log error details
    }

    private void closeServerResources() {
        System.out.println("Closing server resources...\n");
        networkHandler.closeResources();  // close network resources
    }

    private String extractContent(List<String> lineList) {
        // extract the body of the request after headers
        int startIndex = lineList.indexOf("") + 1;
        if (startIndex <= 0 || startIndex >= lineList.size()) {
            return "";
        }
        return lineList.subList(startIndex, lineList.size())
                .stream()
                .collect(Collectors.joining());  // return the body content
    }

    private String processGet(Map<String, String> headers, String content) {
        // process GET request
        int lamportTimestamp = extractLamportTime(headers);
        String stationKey = findStationId(headers);

        if (stationKey == null || !weatherDataMap.containsKey(stationKey)) {
            return constructNoContentResponse();  // return no content if no matching station found
        }

        PriorityQueue<WeatherData> dataQueue = weatherDataMap.get(stationKey);
        if (isQueueEmpty(dataQueue)) {
            return constructNoContentResponse();  // return no content if queue is empty
        }

        return getMatchingDataResponse(dataQueue, lamportTimestamp);  // return matching data
    }

    private String constructNoContentResponse() {
        return constructResponse("204 No Content", null);  // send 204 if no content
    }

    private String getMatchingDataResponse(PriorityQueue<WeatherData> dataQueue, int lamportTimestamp) {
        return locateWeatherData(dataQueue, lamportTimestamp)
                .map(data -> constructResponse("200 OK", data.getData().toString())) // send data if found
                .orElse(constructNoContentResponse());  // otherwise, no content
    }

    private Socket waitForClient() throws InterruptedException {
        return isServerDown ? null : clientRequestQueue.poll(10, TimeUnit.MILLISECONDS);  // poll queue for clients
    }

    private void handleClientSocket(Socket clientSocket) {
        try {
            String requestData = receiveDataFromClient(clientSocket);  // receive data from client
            if (requestData != null) {
                String responseData = processRequest(requestData);  // process the request
                sendResponseToClient(responseData, clientSocket);  // send response back to client
            }
        } catch (IOException e) {
            logError("Error handling client socket: " + e.getMessage(), e);  // log error handling client socket
        } finally {
            closeSocket(clientSocket);  // close the socket cleanly
        }
    }

    private String receiveDataFromClient(Socket clientSocket) throws IOException {
        return networkHandler.waitForDataFromClient(clientSocket);  // delegate data reception to network handler
    }

    private void sendResponseToClient(String responseData, Socket clientSocket) throws IOException {
        networkHandler.sendResponseToClient(responseData, clientSocket);  // delegate response sending to network handler
    }

    private void closeSocket(Socket clientSocket) {
        if (clientSocket != null && !clientSocket.isClosed()) {
            try {
                clientSocket.close();  // close the socket
            } catch (IOException e) {
                logError("Error closing client socket: " + e.getMessage(), e);  // log error during socket close
            }
        }
    }

    private void logError(String message, Exception e) {
        System.err.println(message);  // log error message
        e.printStackTrace();  // log stack trace for debugging
    }

    private <T> T readDataFile(String file, String initialDataFilePath, Type type) {
        T result = readFile(file, type);  // try reading from the main file
        if (result == null) {
            result = readFile(initialDataFilePath, type);  // fallback to initial data file
        }
        return result;
    }

    private <T> T readFile(String filePath, Type type) {
        try {
            String jsonData = Files.readString(Paths.get(filePath));  // read file content as string
            return gson.fromJson(jsonData, type);  // parse JSON to the provided type
        } catch (IOException e) {
            System.err.println("Error reading file: " + filePath);  // log any errors reading the file
            e.printStackTrace();
            return null;
        }
    }

    public String processRequest(String inputData) {
        List<String> lineList = parseInputData(inputData);  // parse input data into lines
        String requestMethod = extractRequestMethod(lineList);  // extract request method (e.g., GET, PUT)

        Map<String, String> headers = extractHeaders(lineList);  // extract headers
        String bodyContent = extractContent(lineList);  // extract the body content

        return handleRequestMethod(requestMethod, headers, bodyContent);  // handle the request
    }

    private List<String> parseInputData(String inputData) {
        return Arrays.asList(inputData.split("\r\n"));  // split input into lines
    }

    private String extractRequestMethod(List<String> lineList) {
        return lineList.isEmpty() ? "" : lineList.get(0).split(" ")[0].toUpperCase();  // get method (e.g., PUT or GET)
    }

    private String handleRequestMethod(String requestMethod, Map<String, String> headers, String bodyContent) {
        switch (requestMethod) {
            case "PUT":
                return processPut(headers, bodyContent);  // process PUT request
            case "GET":
                return processGet(headers, bodyContent);  // process GET request
            default:
                return constructResponse("400 Bad Request", null);  // return bad request for unsupported methods
        }
    }

    private synchronized void saveDataToFile() {
        String dataFilePath = getFilePath("data.json");  // define file paths
        String initDataFilePath = getFilePath("initData.json");
        saveWeatherData(weatherDataMap, dataFilePath, initDataFilePath);  // save weather data

        String timeFilePath = getFilePath("timeData.json");
        String initTimeFilePath = getFilePath("initTimeData.json");
        saveWeatherData(weatherDataMap, timeFilePath, initTimeFilePath);  // save time data
    }

    private String getFilePath(String fileName) {
        return "src" + File.separator + fileName;  // return the file path
    }

    private synchronized void saveWeatherData(
            Map<String, PriorityQueue<WeatherData>> data,
            String filePath,
            String tempFilePath) {

        writeJsonToFile(data, tempFilePath);  // write the data to a temp file
        moveTempFile(tempFilePath, filePath);  // move temp file to the actual path
    }

    private void writeJsonToFile(Map<String, PriorityQueue<WeatherData>> data, String filePath) {
        try {
            String jsonData = gson.toJson(data);  // serialize data to JSON
            Files.write(Paths.get(filePath), jsonData.getBytes());  // write JSON to file
        } catch (IOException e) {
            System.err.println("Failed to write JSON to file: " + filePath);  // log error writing file
            e.printStackTrace();
        }
    }

    private void moveTempFile(String tempFilePath, String finalFilePath) {
        try {
            Files.move(Paths.get(tempFilePath),
                    Paths.get(finalFilePath),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);  // atomically move the temp file to the final location
        } catch (IOException e) {
            System.err.println("Failed to move file from " + tempFilePath + " to " + finalFilePath);  // log error during move
            e.printStackTrace();
        }
    }

    private Map<String, String> extractHeaders(List<String> lineList) {
        return lineList.stream()
                .filter(line -> line.contains(": ")) // filter out header lines
                .map(line -> line.split(": ", 2)) // split into key-value pairs
                .collect(Collectors.toMap(parts -> parts[0], parts -> parts[1]));  // collect into a map
    }

    private String processPut(Map<String, String> headers, String content) {
        String serverKey = headers.get("ServerID");  // get server ID

        if (!isValidSource(serverKey)) {
            return constructBadRequestResponse();  // return bad request if invalid source
        }

        int lamportTimestamp = extractLamportTime(headers);  // extract lamport clock time

        if (!processData(content, lamportTimestamp, serverKey)) {
            return constructBadRequestResponse();  // return bad request if data processing fails
        }

        return generateResponse(serverKey);  // generate response
    }

    private String constructBadRequestResponse() {
        return constructResponse("400 Bad Request", null);  // construct bad request response
    }

    private void initialiseClientAcceptThread() {
        System.out.println("Initializing accept thread...\n");

        clientAcceptThread = new Thread(this::acceptClientConnections);  // start thread to accept clients
        clientAcceptThread.start();
    }

    private void acceptClientConnections() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                handleIncomingClient();  // handle each incoming client
            } catch (IOException e) {
                if (Thread.currentThread().isInterrupted()) {
                    break;  // break the loop if interrupted
                }
                logIOException(e);  // log any IOExceptions
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();  // interrupt the thread if necessary
                throw new RuntimeException(e);  // wrap it into a runtime exception
            }
        }
    }

    private void handleIncomingClient() throws IOException, InterruptedException {
        Socket clientSocket = networkHandler.acceptIncomingClient();  // accept a new client

        if (clientSocket != null) {
            sendLamportClockToClient(clientSocket);  // send lamport clock to client
            clientRequestQueue.put(clientSocket);  // add client to request queue
            System.out.println("Added connection to request queue\n");
        }
    }

    private void sendLamportClockToClient(Socket clientSocket) throws IOException {
        String clockValue = "LamportClock: " + lamportClock.getTime() + "\r\n";  // create lamport clock header
        PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
        out.println(clockValue);  // send lamport clock to client
        out.flush();
    }

    private void logIOException(IOException e) {
        System.err.println("IOException occurred during client acceptance.");  // log IO exceptions
        e.printStackTrace();
    }

    public boolean isValidStation(String stationId) {
        return stationId != null && !stationId.isEmpty();  // check if station ID is valid
    }

    private String constructResponse(String status, String json) {
        // construct an HTTP response with a status and optional JSON content
        StringBuilder responseBuilder = new StringBuilder();

        appendStatusLine(responseBuilder, status);  // append status line to response
        appendLamportClock(responseBuilder);  // append lamport clock to response

        if (json != null) {
            appendJsonHeaders(responseBuilder, json);  // append JSON headers if there's content
        }

        return responseBuilder.toString();  // return the response as a string
    }

    private void appendStatusLine(StringBuilder responseBuilder, String status) {
        responseBuilder.append("HTTP/1.1 ").append(status).append("\r\n");  // add status line
    }

    private void appendLamportClock(StringBuilder responseBuilder) {
        responseBuilder.append("LamportClock: ").append(lamportClock.send()).append("\r\n");  // append lamport clock time
    }

    private void appendJsonHeaders(StringBuilder responseBuilder, String json) {
        responseBuilder.append("Content-Type: application/json\r\n");  // add content type
        responseBuilder.append("Content-Length: ").append(json.length()).append("\r\n\r\n");  // add content length
        responseBuilder.append(json);  // add the actual JSON content
    }

    private int extractLamportTime(Map<String, String> headers) {
        String lamportValue = headers.getOrDefault("LamportClock", "-1");  // get lamport clock from headers

        int lamportTime = parseLamportTime(lamportValue);  // parse the lamport time
        lamportClock.receive(lamportTime);  // update lamport clock

        return lamportClock.getTime();  // return the current time
    }

    private int parseLamportTime(String lamportValue) {
        try {
            return Integer.parseInt(lamportValue);  // parse lamport clock value
        } catch (NumberFormatException e) {
            System.err.println("Invalid LamportClock value: " + lamportValue);  // log error if parsing fails
            return -1;
        }
    }

    private boolean isQueueEmpty(PriorityQueue<WeatherData> queue) {
        return Optional.ofNullable(queue).map(PriorityQueue::isEmpty).orElse(true);  // check if queue is empty
    }

    private Optional<WeatherData> locateWeatherData(PriorityQueue<WeatherData> queue, int lamportTime) {
        if (queue == null) {
            return Optional.empty();  // return empty if queue is null
        }

        return queue.stream()
                .filter(data -> data.getTime() <= lamportTime) // find matching data with lamport time
                .findFirst();
    }

    private boolean isValidSource(String serverID) {
        return serverID != null && !serverID.isEmpty();  // validate server ID
    }

    private boolean processData(String content, int lamportTime, String serverID) {
        try {
            JsonObject weatherDataJSON = gson.fromJson(content, JsonObject.class);  // parse content into JSON
            String stationID = extractID(weatherDataJSON);  // extract station ID from JSON
            WeatherData newWeatherData = new WeatherData(weatherDataJSON, lamportTime, serverID);  // create new weather data object
            weatherDataMap.computeIfAbsent(stationID, k -> new PriorityQueue<>()).add(newWeatherData);  // add data to map
            return true;
        } catch (JsonParseException e) {
            System.err.println("JSON Parsing Error: " + e.getMessage());  // log parsing error
            return false;
        }
    }

    private String generateResponse(String serverID) {
        long currentTimestamp = System.currentTimeMillis();  // get current time
        Long lastTimestamp = timeMap.get(serverID);
        timeMap.put(serverID, currentTimestamp);  // update the timestamp for this server ID

        if (isNewOrDelayedRequest(lastTimestamp, currentTimestamp)) {
            return constructResponse("201 HTTP_CREATED", null);  // new or delayed request response
        } else {
            return constructResponse("200 OK", null);  // OK response for regular requests
        }
    }

    private String findStationId(Map<String, String> headers) {
        String stationId = headers.get("StationID");  // get station ID from headers
        if (stationId != null && !stationId.isEmpty()) {
            return stationId;
        }
        return weatherDataMap.keySet().stream().findFirst().orElse(null);  // return first available station ID if none is provided
    }

    private boolean isNewOrDelayedRequest(Long lastTimestamp, long currentTimestamp) {
        return lastTimestamp == null || (currentTimestamp - lastTimestamp) > 20000;  // check if request is new or delayed
    }

    public boolean addWeatherData(JsonObject weatherDataJSON, int lamportTime, String serverID) {
        String id = extractID(weatherDataJSON);

        if (!isValidStation(id)) {
            return false;  // return false if station ID is invalid
        }
        weatherDataMap.computeIfAbsent(id, k -> new PriorityQueue<>())
                .add(new WeatherData(weatherDataJSON, lamportTime, serverID));  // add new weather data to map
        return true;
    }

    public NetworkHandler getNetworkHandler() {
        return this.networkHandler; // return the network handler
    }

    public String extractID(JsonObject weatherDataJSON) {
        return weatherDataJSON.has("id") ? weatherDataJSON.get("id").toString().replace("\"", "") : null;  // extract ID from JSON
    }

    public synchronized void terminate() {
        markShutdown();  // mark server as down

        haltThread(clientAcceptThread);  // stop client accept thread
        stopScheduledTask(dataSaveScheduler, 5);  // stop data save scheduler
        stopScheduledTask(oldDataCleanupScheduler, 60);  // stop cleanup scheduler

        System.out.println("Server termination initiated...");
    }

    private void markShutdown() {
        this.isServerDown = true;  // set flag to indicate server is down
    }

    public boolean getisServerDown() {
        return this.isServerDown;  // return server down status
    }

    private void haltThread(Thread t) {
        if (t != null) {
            t.interrupt();  // interrupt the thread
        }
    }

    private void stopScheduledTask(ExecutorService scheduler, int timeoutSeconds) {
        if (scheduler == null) {
            return;  // return if scheduler is null
        }

        scheduler.shutdown();  // shutdown the scheduler

        try {
            if (!scheduler.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();  // force shutdown if it doesn't stop in time
            }
        } catch (InterruptedException ex) {
            scheduler.shutdownNow();  // force shutdown if interrupted
            Thread.currentThread().interrupt();  // re-interrupt the current thread
        }
    }

    public static void main(String[] args) {
        int port;
        if (args.length == 0) {
            port = DEFAULT_PORT;  // use default port if no argument
        } else {
            port = Integer.parseInt(args[0]);  // parse port number from argument
        }
        AggregationServer server = new AggregationServer(false);  // create a new server instance
        server.start(port);  // start the server
    }
}
