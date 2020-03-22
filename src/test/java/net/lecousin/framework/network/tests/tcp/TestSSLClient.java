package net.lecousin.framework.network.tests.tcp;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;

import javax.net.ssl.SSLException;

import net.lecousin.framework.network.client.SSLClient;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.server.TCPServer;
import net.lecousin.framework.network.server.protocol.SSLServerProtocol;
import net.lecousin.framework.network.test.AbstractNetworkTest;

import org.junit.Test;

public class TestSSLClient extends AbstractNetworkTest {

	@Test
	public void testConnectToNonSSLServer() throws Exception {
		try (TCPServer server = new TCPServer()) {
			server.setProtocol(new WelcomeProtocol());
			SocketAddress serverAddress = server.bind(new InetSocketAddress("localhost", 0), 0).blockResult(0);
			
			try (SSLClient client = new SSLClient()) {
				client.connect(serverAddress, 10000).blockThrow(0);
				throw new AssertionError("Connect a SSLClient to a non SSL server must throw an SSLException");
			} catch (SSLException e) {
				// ok
			}
		}
	}
	
	@Test
	public void testConnectNonSSLClientToSSLServer() throws Exception {
		try (TCPServer server = new TCPServer()) {
			server.setProtocol(new SSLServerProtocol(new WelcomeProtocol()));
			SocketAddress serverAddress = server.bind(new InetSocketAddress("localhost", 0), 0).blockResult(0);
			
			try (TCPClient client = new TCPClient()) {
				client.connect(serverAddress, 10000).blockThrow(0);
				client.send(ByteBuffer.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20 }), 5000);
				client.receiveData(3, 10000).blockThrow(0);
			}
		}
	}
	
	@Test
	public void testConnectWithInvalidConfiguration() throws Exception {
		try (TCPServer server = new TCPServer()) {
			server.setProtocol(new SSLServerProtocol(new WelcomeProtocol()));
			SocketAddress serverAddress = server.bind(new InetSocketAddress("localhost", 0), 0).blockResult(0);
			
			try (SSLClient client = new SSLClient()) {
				client.connect(serverAddress, 10000).blockThrow(0);
				throw new AssertionError();
			} catch (Exception e) {
				// ok
			}
		}
	}
	
	@Test
	public void testConnectWithInvalidClientConfiguration() throws Exception {
		try (TCPServer server = new TCPServer()) {
			server.setProtocol(new SSLServerProtocol(sslTest, new WelcomeProtocol()));
			SocketAddress serverAddress = server.bind(new InetSocketAddress("localhost", 0), 0).blockResult(0);
			
			try (SSLClient client = new SSLClient()) {
				client.connect(serverAddress, 0).blockThrow(0);
				throw new AssertionError();
			} catch (Exception e) {
				// ok
			}
		}
	}
	
	@Test
	public void testConnectWithHostname() throws Exception {
		try (SSLClient client = new SSLClient()) {
			client.setHostNames("google.com");
			client.connect(new InetSocketAddress(InetAddress.getByName("google.com"), 443), 10000).blockThrow(0);
		}
	}
	
}
