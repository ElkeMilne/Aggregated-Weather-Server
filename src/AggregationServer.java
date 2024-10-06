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
    private LinkedBlockingQueue<Socket> clientRequestQueue = new LinkedBlockingQueue<>();
    private Map<String, PriorityQueue<WeatherData>> weatherDataMap = new ConcurrentHashMap<>();
    private Map<String, Long> timeMap = new ConcurrentHashMap<>();
    private volatile boolean isServerDown = false;
    private LamportClock lamportClock = new LamportClock();
    private Thread clientAcceptThread;
    private ScheduledExecutorService dataSaveScheduler;
    private ScheduledExecutorService oldDataCleanupScheduler;
    private static final int DEFAULT_PORT = 4567;

    public AggregationServer(boolean isTestMode) {
        this.networkHandler = new SocketNetworkHandler(isTestMode);
    }

    public void start(int portNumber) {
        System.out.println("server start");
        networkHandler.initialiseServer(portNumber);

        dataSaveScheduler = Executors.newScheduledThreadPool(1);
        dataSaveScheduler.scheduleAtFixedRate(this::saveDataToFile, 0, 60, TimeUnit.SECONDS);

        loadDataFromFile();

        oldDataCleanupScheduler = Executors.newScheduledThreadPool(1);
        oldDataCleanupScheduler.scheduleAtFixedRate(this::cleanupOldEntries, 0, 21, TimeUnit.SECONDS);

        initialiseClientAcceptThread();

        processClientRequests();
    }

    public JsonObject getWeatherData(String stationID) {
        return Optional.ofNullable(weatherDataMap.get(stationID))
                       .filter(queue -> !queue.isEmpty())
                       .map(PriorityQueue::peek)
                       .map(WeatherData::getData)
                       .orElse(null);
    }

    public int getLamportClockTime() {
        // No synchronization needed here, this is a simple read
        return lamportClock.getTime();
    }

    public void loadDataFromFile() {
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

        this.weatherDataMap = loadedQueue;
        this.timeMap = loadedTimeService;
    }

    private synchronized void cleanupOldEntries() {
        long currentTime = System.currentTimeMillis();

        // Get old server IDs based on time threshold
        Set<String> oldServerIDs = findOldServerIDs(currentTime);

        // Remove old server entries
        removeOldServerEntries(oldServerIDs);

        // Clean up weather data map if no valid servers remain
        if (timeMap.isEmpty()) {
            weatherDataMap.clear();
            return;
        }

        // Remove old weather data associated with old server IDs
        cleanUpWeatherData(oldServerIDs);
    }

    private Set<String> findOldServerIDs(long currentTime) {
        return timeMap.keySet().stream()
            .filter(entry -> currentTime - timeMap.get(entry) > 20000)
            .collect(Collectors.toSet());
    }

    private synchronized void removeOldServerEntries(Set<String> oldServerIDs) {
        oldServerIDs.forEach(timeMap::remove);
    }

    private synchronized void cleanUpWeatherData(Set<String> oldServerIDs) {
        weatherDataMap.forEach((stationID, queue) -> {
            queue.removeIf(weatherData -> oldServerIDs.contains(weatherData.getserverID()));
            if (queue.isEmpty()) {
                weatherDataMap.remove(stationID);
            }
        });
    }

    private void processClientRequests() {
        System.out.println("Processing client requests...\n");

        try {
            // Continuously process client requests until server is shut down
            processRequestsLoop();
        } catch (Exception e) {
            logException(e);
        } finally {
            closeServerResources();
        }
    }

    private void processRequestsLoop() throws Exception {
        while (!isServerDown) {
            Socket clientSocket = waitForClient();
            if (clientSocket != null) {
                handleNewConnection(clientSocket);
            }
        }
    }

    private void handleNewConnection(Socket clientSocket) {
        System.out.println("New connection\n");
        handleClientSocket(clientSocket);
    }

    private void logException(Exception e) {
        System.err.println("An error occurred while processing client requests:");
        e.printStackTrace();
    }

    private void closeServerResources() {
        System.out.println("Closing server resources...\n");
        networkHandler.closeResources();
    }

    private String extractContent(List<String> lineList) {
        int startIndex = lineList.indexOf("") + 1;
        if (startIndex <= 0 || startIndex >= lineList.size()) {
            return "";
        }
        return lineList.subList(startIndex, lineList.size())
                       .stream()
                       .collect(Collectors.joining());
    }

    private String processGet(Map<String, String> headers, String content) {
        // No synchronization needed here since we are only reading from the map
        int lamportTimestamp = extractLamportTime(headers);
        String stationKey = findStationId(headers);

        if (stationKey == null || !weatherDataMap.containsKey(stationKey)) {
            return constructNoContentResponse();
        }

        PriorityQueue<WeatherData> dataQueue = weatherDataMap.get(stationKey);
        if (isQueueEmpty(dataQueue)) {
            return constructNoContentResponse();
        }

        return getMatchingDataResponse(dataQueue, lamportTimestamp);
    }

    private String constructNoContentResponse() {
        return constructResponse("204 No Content", null);
    }

    private String getMatchingDataResponse(PriorityQueue<WeatherData> dataQueue, int lamportTimestamp) {
        return locateWeatherData(dataQueue, lamportTimestamp)
                .map(data -> constructResponse("200 OK", data.getData().toString()))
                .orElse(constructNoContentResponse());
    }

    private Socket waitForClient() throws InterruptedException {
        return isServerDown ? null : clientRequestQueue.poll(10, TimeUnit.MILLISECONDS);
    }

    private void handleClientSocket(Socket clientSocket) {
        try {
            String requestData = receiveDataFromClient(clientSocket); // Separate data reception logic
            if (requestData != null) {
                String responseData = processRequest(requestData); // Process request and generate response
                sendResponseToClient(responseData, clientSocket);  // Send response back to client
            }
        } catch (IOException e) {
            logError("Error handling client socket: " + e.getMessage(), e); // Log error with details
        } finally {
            closeSocket(clientSocket);  // Cleanly close socket using a separate method
        }
    }

    private String receiveDataFromClient(Socket clientSocket) throws IOException {
        return networkHandler.waitForDataFromClient(clientSocket);  // Delegate to networkHandler
    }

    private void sendResponseToClient(String responseData, Socket clientSocket) throws IOException {
        networkHandler.sendResponseToClient(responseData, clientSocket);  // Delegate to networkHandler
    }

    private void closeSocket(Socket clientSocket) {
        if (clientSocket != null && !clientSocket.isClosed()) {
            try {
                clientSocket.close();  // Ensure socket is closed to release resources
            } catch (IOException e) {
                logError("Error closing client socket: " + e.getMessage(), e);  // Log error during socket close
            }
        }
    }

    private void logError(String message, Exception e) {
        System.err.println(message);  // Replace with logging mechanism if needed
        e.printStackTrace();  // Optionally print stack trace for debugging
    }

    private <T> T readDataFile(String file, String initialDataFilePath, Type type) {
        T result = readFile(file, type);
        if (result == null) {
            result = readFile(initialDataFilePath, type);
        }
        return result;
    }

    private <T> T readFile(String filePath, Type type) {
        try {
            String jsonData = Files.readString(Paths.get(filePath));
            return gson.fromJson(jsonData, type);
        } catch (IOException e) {
            System.err.println("Error reading file: " + filePath);
            e.printStackTrace();
            return null;
        }
    }

    public String processRequest(String inputData) {
        List<String> lineList = parseInputData(inputData);
        String requestMethod = extractRequestMethod(lineList);
        
        Map<String, String> headers = extractHeaders(lineList);
        String bodyContent = extractContent(lineList);

        return handleRequestMethod(requestMethod, headers, bodyContent);
    }

    private List<String> parseInputData(String inputData) {
        return Arrays.asList(inputData.split("\r\n"));
    }

    private String extractRequestMethod(List<String> lineList) {
        return lineList.isEmpty() ? "" : lineList.get(0).split(" ")[0].toUpperCase();
    }

    private String handleRequestMethod(String requestMethod, Map<String, String> headers, String bodyContent) {
        switch (requestMethod) {
            case "PUT":
                return processPut(headers, bodyContent);
            case "GET":
                return processGet(headers, bodyContent);
            default:
                return constructResponse("400 Bad Request", null);
        }
    }

    private synchronized void saveDataToFile() {
        String dataFilePath = getFilePath("data.json");
        String initDataFilePath = getFilePath("initData.json");
        saveWeatherData(weatherDataMap, dataFilePath, initDataFilePath);

        String timeFilePath = getFilePath("timeData.json");
        String initTimeFilePath = getFilePath("initTimeData.json");
        saveWeatherData(weatherDataMap, timeFilePath, initTimeFilePath);
    }

    private String getFilePath(String fileName) {
        return "src" + File.separator + fileName;
    }

    private synchronized void saveWeatherData(
      Map<String, PriorityQueue<WeatherData>> data,
      String filePath,
      String tempFilePath) {
      
      writeJsonToFile(data, tempFilePath);
      moveTempFile(tempFilePath, filePath);
    }

    private synchronized void writeJsonToFile(Map<String, PriorityQueue<WeatherData>> data, String filePath) {
        try {
            String jsonData = gson.toJson(data);
            Files.write(Paths.get(filePath), jsonData.getBytes());
        } catch (IOException e) {
            System.err.println("Failed to write JSON to file: " + filePath);
            e.printStackTrace();
        }
    }

    private synchronized void moveTempFile(String tempFilePath, String finalFilePath) {
        try {
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
        return lineList.stream()
            .filter(line -> line.contains(": "))
            .map(line -> line.split(": ", 2))
            .collect(Collectors.toMap(parts -> parts[0], parts -> parts[1]));
    }

    private synchronized String processPut(Map<String, String> headers, String content) {
        String serverKey = headers.get("ServerID");

        if (!isValidSource(serverKey)) {
            return constructBadRequestResponse();
        }

        int lamportTimestamp = extractLamportTime(headers);
        
        if (!processData(content, lamportTimestamp, serverKey)) {
            return constructBadRequestResponse();
        }

        return generateResponse(serverKey);
    }

    private String constructBadRequestResponse() {
        return constructResponse("400 Bad Request", null);
    }

    private void initialiseClientAcceptThread() {
        System.out.println("Initializing accept thread...\n");

        clientAcceptThread = new Thread(this::acceptClientConnections);
        clientAcceptThread.start();
    }

    private void acceptClientConnections() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                handleIncomingClient();
            } catch (IOException e) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                logIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();  // Preserve interrupted state
                throw new RuntimeException(e);
            }
        }
    }

    private void handleIncomingClient() throws IOException, InterruptedException {
        Socket clientSocket = networkHandler.acceptIncomingClient();

        if (clientSocket != null) {
            sendLamportClockToClient(clientSocket);
            synchronized (clientRequestQueue) {
                clientRequestQueue.put(clientSocket);
            }
            System.out.println("Added connection to request queue\n");
        }
    }

    private void sendLamportClockToClient(Socket clientSocket) throws IOException {
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
        return stationId != null && !stationId.isEmpty();
    }

    private String constructResponse(String status, String json) {
        StringBuilder responseBuilder = new StringBuilder();

        appendStatusLine(responseBuilder, status);
        appendLamportClock(responseBuilder);
        
        if (json != null) {
            appendJsonHeaders(responseBuilder, json);
        }

        return responseBuilder.toString();
    }

    private void appendStatusLine(StringBuilder responseBuilder, String status) {
        responseBuilder.append("HTTP/1.1 ").append(status).append("\r\n");
    }

    private void appendLamportClock(StringBuilder responseBuilder) {
        responseBuilder.append("LamportClock: ").append(lamportClock.send()).append("\r\n");
    }

    private void appendJsonHeaders(StringBuilder responseBuilder, String json) {
        responseBuilder.append("Content-Type: application/json\r\n");
        responseBuilder.append("Content-Length: ").append(json.length()).append("\r\n\r\n");
        responseBuilder.append(json);
    }

    private int extractLamportTime(Map<String, String> headers) {
        String lamportValue = headers.getOrDefault("LamportClock", "-1");

        int lamportTime = parseLamportTime(lamportValue);
        lamportClock.receive(lamportTime);
        
        return lamportClock.getTime();
    }

    private int parseLamportTime(String lamportValue) {
        try {
            return Integer.parseInt(lamportValue);
        } catch (NumberFormatException e) {
            System.err.println("Invalid LamportClock value: " + lamportValue);
            return -1;
        }
    }

    private boolean isQueueEmpty(PriorityQueue<WeatherData> queue) {
        return Optional.ofNullable(queue).map(PriorityQueue::isEmpty).orElse(true);
    }

    private Optional<WeatherData> locateWeatherData(PriorityQueue<WeatherData> queue, int lamportTime) {
        if (queue == null) {
            return Optional.empty();
        }

        return queue.stream()
                    .filter(data -> data.getTime() <= lamportTime)
                    .findFirst();
    }

    private boolean isValidSource(String serverID) {
        return serverID != null && !serverID.isEmpty();
    }

    private boolean processData(String content, int lamportTime, String serverID) {
        try {
            JsonObject weatherDataJSON = gson.fromJson(content, JsonObject.class);
            String stationID = extractID(weatherDataJSON);
            WeatherData newWeatherData = new WeatherData(weatherDataJSON, lamportTime, serverID);

            // No need for synchronized here, because computeIfAbsent is thread-safe
            weatherDataMap.computeIfAbsent(stationID, k -> new PriorityQueue<>()).add(newWeatherData);
            return true;
        } catch (JsonParseException e) {
            System.err.println("JSON Parsing Error: " + e.getMessage());
            return false;
        }
    }

    private String generateResponse(String serverID) {
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
        String stationId = headers.get("StationID");
        if (stationId != null && !stationId.isEmpty()) {
            return stationId;
        }
        return weatherDataMap.keySet().stream().findFirst().orElse(null);
    }

    private boolean isNewOrDelayedRequest(Long lastTimestamp, long currentTimestamp) {
        return lastTimestamp == null || (currentTimestamp - lastTimestamp) > 20000;
    }

    public boolean addWeatherData(JsonObject weatherDataJSON, int lamportTime, String serverID) {
        String id = extractID(weatherDataJSON);

        if (!isValidStation(id)) {
            return false;
        }
        weatherDataMap.computeIfAbsent(id, k -> new PriorityQueue<>())
            .add(new WeatherData(weatherDataJSON, lamportTime, serverID));
        return true;
    }

    public NetworkHandler getNetworkHandler() {
        return this.networkHandler;
    }

    public String extractID(JsonObject weatherDataJSON) {
        return weatherDataJSON.has("id") ? weatherDataJSON.get("id").toString().replace("\"", "") : null;
    }

    public void terminate() {
        markShutdown();

        haltThread(clientAcceptThread);
        stopScheduledTask(dataSaveScheduler, 5);
        stopScheduledTask(oldDataCleanupScheduler, 60);

        System.out.println("Server termination initiated...");
    }

    private void markShutdown() {
        this.isServerDown = true;
    }

    public boolean getisServerDown() {
        return this.isServerDown;
    }

    private void haltThread(Thread t) {
        if (t != null) {
            t.interrupt();
        }
    }

    private void stopScheduledTask(ExecutorService scheduler, int timeoutSeconds) {
        if (scheduler == null) {
            return;
        }

        scheduler.shutdown();

        try {
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
            port = DEFAULT_PORT;
        } else {
            port = Integer.parseInt(args[0]);
        }
        AggregationServer server = new AggregationServer(false);
        server.start(port);
    }
}
