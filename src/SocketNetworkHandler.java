import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.locks.ReentrantLock;

public class SocketNetworkHandler implements NetworkHandler {
  private ServerSocket serverSocket;
  private Socket clientSocket;
  private PrintWriter out;
  private BufferedReader in;
  // For testing
  private boolean isTestMode;
  private String testResponse;

  // Lock to ensure thread-safe operations on clientSocket and streams
  private final ReentrantLock lock = new ReentrantLock();

  // constructor
  public SocketNetworkHandler(boolean isTestMode) {
    this.isTestMode = isTestMode;
  }

  @Override
  public void initialiseServer(int portNumber) {
    try {
      serverSocket = new ServerSocket(portNumber);
      System.out.println("Server listening on port " + portNumber + " " + serverSocket);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  @Override
  public Socket acceptIncomingClient() {
    try {
      return serverSocket.accept();
    } catch (IOException e) {
      e.printStackTrace();
      return null;
    }
  }

  @Override
  public String waitForDataFromClient(Socket clientSocket) {
    StringBuilder requestBuilder = new StringBuilder();

    try {
      InputStream input = clientSocket.getInputStream();
      BufferedReader in = new BufferedReader(new InputStreamReader(input));

      String line;
      int contentLength = 0;
      boolean isHeader = true;
      while (isHeader && (line = in.readLine()) != null) {
        if (line.startsWith("Content-Length: ")) {
          contentLength = Integer.parseInt(line.split(":")[1].trim());
        }

        // Append each line followed by a CRLF to the request builder.
        requestBuilder.append(line).append("\r\n");

        // A blank line indicates the end of the headers.
        if (line.isEmpty()) {
          isHeader = false;
        }
      }

      if (contentLength > 0) {
        char[] bodyChars = new char[contentLength];
        in.read(bodyChars, 0, contentLength);
        requestBuilder.append(bodyChars);
      }

      return requestBuilder.toString();

    } catch (Exception e) {
      e.printStackTrace();
      return null;
    }
  }

  @Override
  public synchronized void sendResponseToClient(String response, Socket clientSocket) {
    try {
      out = new PrintWriter(clientSocket.getOutputStream(), true);
      out.println(response);
      out.flush();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  @Override
  public String sendDataToServer(String serverName, int portNumber, String data) {
    lock.lock(); // Ensure thread-safe access to shared resources
    try {
      out = new PrintWriter(clientSocket.getOutputStream(), true);
      out.println(data);
      out.flush();
      System.out.println("Waiting for response from server..." + data);
      return readServerResponse();
    } catch (IOException e) {
      System.out.println("Error while connecting to the server: " + e.getMessage());
      e.printStackTrace();
      return null;
    } finally {
      closeResources();
      lock.unlock(); // Unlock after resources are closed
    }
  }

  @Override
  public String receiveDataFromServer(String serverName, int portNumber, String request) {
    if (isTestMode) {
      return testResponse;
    }
    lock.lock(); // Ensure thread-safe access
    try {
      out = new PrintWriter(clientSocket.getOutputStream(), true);
      out.println(request);
      out.flush();
      return readServerResponse();
    } catch (IOException e) {
      e.printStackTrace();
      return null;
    } finally {
      closeResources();
      lock.unlock(); // Unlock after resources are closed
    }
  }

  @Override
  public synchronized int initialiseClientSocket(String serverName, int portNumber) {
    if (isTestMode) {
      return 0;
    }
    lock.lock(); // Ensure thread-safe access
    try {
      this.closeResources(); // Close previous resources before reinitializing
      clientSocket = new Socket(serverName, portNumber);
      out = new PrintWriter(clientSocket.getOutputStream(), true);
      in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
      String clockLine = in.readLine();
      System.out.println("Clock line: " + clockLine);
      if (clockLine != null && clockLine.startsWith("LamportClock: ")) {
        return Integer.parseInt(clockLine.split(":")[1].trim());
      } else {
        throw new IOException("Error while initializing client socket.");
      }
    } catch (IOException e) {
      System.out.println("Error while initializing client socket: " + e.getMessage());
      e.printStackTrace();
      closeResources();
      return -1;
    } finally {
      lock.unlock(); // Unlock after initialization is complete
    }
  }

  private String readServerResponse() throws IOException {
    StringBuilder responseBuilder = new StringBuilder();
    String line;
    int contentLength = 0;
    boolean isHeader = true;
    in.readLine();
    while (isHeader && (line = in.readLine()) != null) {
      if (line.startsWith("Content-Length: ")) {
        contentLength = Integer.parseInt(line.split(":")[1].trim());
      }

      responseBuilder.append(line).append("\r\n");

      if (line.isEmpty()) {
        isHeader = false;
      }
    }

    char[] bodyChars = new char[contentLength];
    in.read(bodyChars, 0, contentLength);
    responseBuilder.append(bodyChars);
    return responseBuilder.toString();

  }

  @Override
  public synchronized void closeResources() {
    lock.lock(); // Ensure thread-safe resource closing
    try {
      if (in != null)
        in.close();
      if (out != null)
        out.close();
      if (clientSocket != null)
        clientSocket.close();
      if (serverSocket != null)
        serverSocket.close();
    } catch (IOException e) {
      e.printStackTrace();
    } finally {
      lock.unlock(); // Unlock after resources are closed
    }
  }

  @Override
  public String handleTestRequest(String serverName, int portNumber, String request) {
    this.testResponse = request;
    return receiveDataFromServer(serverName, portNumber, request);
  }

  @Override
  public boolean checkClientSocketIsClosed() {
    return clientSocket == null;
  }
}
