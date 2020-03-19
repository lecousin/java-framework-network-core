package net.lecousin.framework.network.tests.tcp;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;

import javax.net.SocketFactory;

import net.lecousin.framework.concurrent.CancelException;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.network.NetUtil;
import net.lecousin.framework.network.SocketOptionValue;
import net.lecousin.framework.network.client.SSLClient;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.server.TCPServer;
import net.lecousin.framework.network.server.protocol.SSLServerProtocol;
import net.lecousin.framework.network.server.protocol.ServerProtocol;
import net.lecousin.framework.network.test.AbstractNetworkTest;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public abstract class AbstractTestTCP extends AbstractNetworkTest {

	@Parameters(name = "SSL = {0}, IPv6 = {1}")
	public static Collection<Object[]> parameters() {
		Collection<Object[]> params = Arrays.asList(
			new Object[] { Boolean.FALSE },
			new Object[] { Boolean.TRUE }
		);
		if (NetUtil.getLoopbackIPv6Address() != null)
			params = addTestParameter(params, Boolean.FALSE, Boolean.TRUE);
		else
			params = addTestParameter(params, Boolean.FALSE);
		return params;
	}
	
	protected AbstractTestTCP(boolean useSSL, boolean useIPv6) {
		this.useSSL = useSSL;
		this.useIPv6 = useIPv6;
	}
	
	protected boolean useSSL;
	protected boolean useIPv6;
	protected TCPServer server;
	protected SocketAddress serverAddress;
	
	protected abstract ServerProtocol createProtocol();
	
	@Before
	public void initTCPServer() throws IOException, CancelException {
		server = new TCPServer();
		ServerProtocol protocol = createProtocol();
		if (useSSL)
			protocol = new SSLServerProtocol(sslTest, protocol);
		server.setProtocol(protocol);
		if (useIPv6)
			serverAddress = server.bind(new InetSocketAddress(NetUtil.getLoopbackIPv6Address(), 0), 0).blockResult(0);
		else
			serverAddress = server.bind(new InetSocketAddress("localhost", 0), 0).blockResult(0);
	}
	
	@After
	public void closeTCPServer() {
		server.close();
	}
	
	protected SocketFactory getSocketFactory() {
		if (useSSL)
			return sslTest.getSocketFactory();
		return SocketFactory.getDefault();
	}
	
	protected Socket connectSocket() throws IOException {
		Socket s = getSocketFactory().createSocket();
		s.connect(serverAddress);
		return s;
	}
	
	protected TCPClient connectClient(SocketOptionValue<?>... options) throws Exception {
		TCPClient client;
		if (useSSL)
			client = new SSLClient(sslTest);
		else
			client = new TCPClient();
		Assert.assertNull(client.getLocalAddress());
		Assert.assertNull(client.getRemoteAddress());
		Async<IOException> sp = client.connect(serverAddress, 10000, options);
		try {
			sp.blockThrow(0);
		} catch (Exception e) {
			client.close();
			throw e;
		}
		Assert.assertNotNull(client.getLocalAddress());
		Assert.assertNotNull(client.getRemoteAddress());
		client.toString();
		return client;
	}
	
	public static void expectLine(Socket s, String message) throws Exception {
		InputStream in = s.getInputStream();
		StringBuilder msg = new StringBuilder();
		do {
			int c = in.read();
			Assert.assertFalse(c <= 0);
			if (c == '\n') {
				Assert.assertEquals(message, msg.toString());
				return;
			}
			msg.append((char)c);
		} while (true);
	}
	
	public static void expectLine(TCPClient client, String message) throws Exception {
		ByteArrayIO io = client.getReceiver().readUntil((byte)'\n', 128, 30000).blockResult(0);
		Assert.assertEquals(message, io.getAsString(StandardCharsets.US_ASCII));
	}
	
	public static void sendLine(Socket s, String message) throws Exception {
		sendLine(s, message.getBytes(StandardCharsets.US_ASCII), 0);
	}
	
	private static void sendLine(Socket s, byte[] message, int pos) throws Exception {
		int rem = message.length - pos;
		if (rem < 3) {
			s.getOutputStream().write(message, pos, rem);
			s.getOutputStream().write('\n');
			s.getOutputStream().flush();
		} else {
			s.getOutputStream().write(message, pos, rem / 2 + 1);
			s.getOutputStream().flush();
			sendLine(s, message, pos + rem / 2 + 1);
		}
	}
	
	public static void sendLine(TCPClient client, String message) throws Exception {
		sendLine(client, message.getBytes(StandardCharsets.US_ASCII), 0);
	}
	
	private static void sendLine(TCPClient client, byte[] message, int pos) throws Exception {
		int rem = message.length - pos;
		if (rem < 3) {
			client.send(ByteBuffer.wrap(message, pos, rem).asReadOnlyBuffer(), 10000);
			client.send(ByteBuffer.wrap(new byte[] { (byte)'\n' }), 5000);
		} else {
			client.send(ByteBuffer.wrap(message, pos, rem / 2 + 1).asReadOnlyBuffer(), 5000);
			sendLine(client, message, pos + rem / 2 + 1);
		}
	}

}
