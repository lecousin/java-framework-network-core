/**
 * This library provides utilities for networking.<br/>
 * <br/>
 * The {@link net.lecousin.framework.network.NetworkManager} launches a Thread that uses the Java NIO framework
 * to register and listen to network events. Then, listener interfaces are provided to ease the implementation
 * of network clients or servers.<br/>
 * <br/>
 * The client package contains implementations for network clients.<br/>
 * <br/>
 * The server package contains generic implementations for network servers, delegating the behaviour
 * to a {@link net.lecousin.framework.network.server.protocol.ServerProtocol}.
 */
package net.lecousin.framework.network;
