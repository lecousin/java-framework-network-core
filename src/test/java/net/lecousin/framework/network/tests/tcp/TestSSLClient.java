package net.lecousin.framework.network.tests.tcp;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import javax.net.ssl.SSLException;

import net.lecousin.framework.network.client.SSLClient;
import net.lecousin.framework.network.server.TCPServer;
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
	
}
