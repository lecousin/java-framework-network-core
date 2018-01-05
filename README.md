# lecousin.net - Java network framework - core

This library provides network functionalities with multi-threading.

It depends on the [net.lecousin.core]("https://github.com/lecousin/java-framework-core" "java-framework-core") library
for asynchronous operations and to launch tasks on network events.

Main functionalities:
 * The NetworkManager is based on the Java NIO framework to listen to network events and launch tasks to handle them
 * The classes TCPClient and UDPClient allow to connect to a server and exchange messages asynchronously
 * The classes TCPServer and UDPServer implement a server to process client requests asynchronously
 * A TCPServer is based on a ServerProtocol, which provides the specific implementation of a protocol
 * The class SSLLayer allows to handle SSL messages exchange, and is used by the SSLClient and SSLServerProtocol
