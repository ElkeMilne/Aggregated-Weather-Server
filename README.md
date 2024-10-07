Purpose of each file:

This project involves a RESTful API that gathers weather data from content servers and provides the aggregated information to clients upon request. The system consists of three primary components: the Aggregation Server, the Content Servers, and the GET Clients.

Explaining each file:
lib Folder:
This directory contains external libraries required by the project, such as JAR files for handling JSON, networking, or unit testing.

src Folder:
AggregationServer.java: The Aggregation Server, which is the central component responsible for collecting weather data from Content Servers and processing GET and PUT requests from clients. It manages request handling, server operations, and uses a Lamport Clock for synchronization.

ContentServer.java: The Content Server, which acts as a weather station. It periodically sends weather data to the Aggregation Server through PUT requests. It contains logic for parsing and packaging weather data before transmitting it to the Aggregation Server.

GETClient.java: the GET Client, which requests weather data from the Aggregation Server using GET requests. It handles communication with the Aggregation Server, retrieves data, and processes the server's response.

JSONHandler.java: Responsible for parsing and manipulating JSON data. It helps in converting data between JSON format and internal data structures used by the Aggregation Server and Content Servers. This ensures proper handling of weather data, request data, and responses.

LamportClock.java: Implementation of the Lamport Clock, a logical clock used to synchronize events in a distributed system. It helps ensure that requests from different servers are processed in a consistent order, resolving conflicts based on the timestamps.

NetworkHandler.java: Abstraction for network operations. It might contain methods for handling incoming and outgoing network connections, managing sockets, and ensuring that data is transmitted and received properly over the network.

SocketNetworkHandler.java: Extends NetworkHandler and uses specific network communication using Java Sockets. It handles low-level socket operations, such as creating sockets, reading/writing data to sockets, and closing connections.

Test Files:
AggregationServerTest.java: Unit tests for the Aggregation Server. This file contains test cases to ensure that the server functions correctly, including handling GET/PUT requests, synchronizing the Lamport Clock, and managing request queues.

ContentServerTest.java: Unit tests for the Content Server. These tests validate the correct operation of the Content Server, including how it sends data to the Aggregation Server and handles network communication.

GETClientTest.java: Unit tests for the GET Client. This file tests the functionality of the client, ensuring that it correctly sends GET requests, processes server responses, and retrieves the expected data.

JSONHandlerTest.java: Unit tests for the JSONHandler class. This file tests whether JSON data is correctly parsed and transformed into internal data structures, and whether the conversion between JSON and data structures works as expected.

LamportClockTest.java: Unit tests for the Lamport Clock. These tests check if the clock updates and synchronizes timestamps correctly, ensuring that the system maintains proper event ordering in the distributed environment.

IntegrationTest.java: This file likely contains integration tests that test the interaction between the various components of the system. It simulates the behavior of multiple content servers, the aggregation server, and clients to ensure that the entire system works as a whole.

How to run:
Compilation:

1. Navigate to the project directory and use the provided makefile to compile the classes:

make all

2. Starting the AggregationServer:

Open a terminal and run:

make aggregation

3. Starting a ContentServer:

Open another terminal and run:

make content

4. Starting a GETClient:

Open another terminal and run:

make client

5. Clean up:

To clean up the compiled classes, you can run:

make clean

Notes
Make sure the AggregationServer is running before starting any ContentServer instances. The GETClient can be started at any time but will only retrieve data if the AggregationServer is running and has received data from a ContentServer.
