package net.lecousin.framework.network.tests.tcp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;

import javax.net.ssl.SSLException;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.network.client.SSLClient;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.server.TCPServer;
import net.lecousin.framework.network.server.protocol.SSLServerProtocol;
import net.lecousin.framework.network.ssl.SSLConnectionConfig;
import net.lecousin.framework.network.test.AbstractNetworkTest;
import net.lecousin.framework.network.tests.tcp.TestTCPReceiveDataProtocol.ReceiveDataProtocol;
import net.lecousin.framework.network.tests.tcp.TestTCPSendDataProtocol.SendDataProtocol;

import org.junit.Assert;
import org.junit.Test;

public class TestSSLClient extends AbstractNetworkTest {

	@Test
	public void testConnectToNonSSLServer() throws Exception {
		try (TCPServer server = new TCPServer()) {
			server.setProtocol(new WelcomeProtocol());
			SocketAddress serverAddress = server.bind(new InetSocketAddress("localhost", 0), 0).blockResult(0);
			
			try (SSLClient client = new SSLClient(new SSLConnectionConfig())) {
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
			
			try (SSLClient client = new SSLClient(new SSLConnectionConfig())) {
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
			
			try (SSLClient client = new SSLClient(new SSLConnectionConfig())) {
				client.connect(serverAddress, 0).blockThrow(0);
				throw new AssertionError();
			} catch (Exception e) {
				// ok
			}
		}
	}
	
	@Test
	public void testConnectWithHostname() throws Exception {
		SSLConnectionConfig sslConfig = new SSLConnectionConfig();
		sslConfig.setHostNames(Arrays.asList("google.com"));
		try (SSLClient client = new SSLClient(sslConfig)) {
			client.connect(new InetSocketAddress(InetAddress.getByName("google.com"), 443), 10000).blockThrow(0);
		}
	}
	
	@Test
	public void testALPN() throws Exception {
		try (TCPServer server = new TCPServer()) {
			server.setProtocol(new SSLServerProtocol(sslTest, new WelcomeProtocol(), new SendDataProtocol(), new ReceiveDataProtocol()));
			SocketAddress serverAddress = server.bind(new InetSocketAddress("localhost", 0), 0).blockResult(0);
			
			SSLConnectionConfig sslConfig = new SSLConnectionConfig();
			sslConfig.setContext(sslTest);
			sslConfig.setApplicationProtocols(Arrays.asList("h2", "receive", "send", "welcome"));
			try (SSLClient client = new SSLClient(sslConfig)) {
				client.connect(serverAddress, 10000).blockThrow(0);
				if (SSLConnectionConfig.ALPN_SUPPORTED)
					Assert.assertEquals("send", client.getApplicationProtocol());
				else
					Assert.assertArrayEquals(new byte[] { 'W', 'e', 'l', 'c', 'o', 'm', 'e' }, client.getReceiver().readBytes(7, 10000).blockResult(0));
			}
		}
	}
	
	@Test
	public void testTunnelConnected() throws Exception {
		try (TCPServer server = new TCPServer()) {
			server.setProtocol(new SSLServerProtocol(sslTest, new WelcomeProtocol()));
			SocketAddress serverAddress = server.bind(new InetSocketAddress("localhost", 0), 0).blockResult(0);
		
			SSLConnectionConfig sslConfig = new SSLConnectionConfig();
			sslConfig.setContext(sslTest);
			try (SSLClient client = new SSLClient(sslConfig)) {
				TCPClient tunnel = new TCPClient();
				tunnel.connect(serverAddress, 0).blockThrow(0);
				Async<IOException> connection = new Async<>();
				client.tunnelConnected(tunnel, connection, 10000);
				connection.blockThrow(0);
				client.send(ByteBuffer.wrap(new byte[] { 'B', 'y', 'e', '\n' }), 5000);
				Assert.assertArrayEquals(new byte[] { 'W', 'e', 'l', 'c', 'o', 'm', 'e', '\n', 'B', 'y', 'e' }, client.getReceiver().readBytes(11, 5000).blockResult(0));
			}
		}
	}
	
}
