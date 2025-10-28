import java.net.Socket;

public interface NetworkHandler {

    // sets up the server on the given port number
    void initialiseServer(int portNumber);

    // accepts an incoming client connection and returns the client socket
    Socket acceptIncomingClient();

    // waits for data from a connected client socket
    String waitForDataFromClient(Socket clientSocket);

    // sends a response back to the client through the socket
    void sendResponseToClient(String response, Socket clientSocket);

    // sends data to a server and returns the response as a string
    String sendDataToServer(String serverName, int portNumber, String data);

    // initializes a client socket for communication with the server
    int initialiseClientSocket(String serverName, int portNumber);

    // receives data from the server based on the request sent
    String receiveDataFromServer(String serverName, int portNumber, String request);

    // closes all open resources like sockets
    void closeResources();

    // handles a test request for cases like unit tests
    String handleTestRequest(String serverName, int portNumber, String request);

    // checks if the client socket is closed
    public boolean checkClientSocketIsClosed();
}
