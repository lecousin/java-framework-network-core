package net.lecousin.framework.network.tests;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;

import javax.net.ssl.SSLException;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.synch.SynchronizationPoint;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.log.Logger.Level;
import net.lecousin.framework.mutable.MutableBoolean;
import net.lecousin.framework.network.NetUtil;
import net.lecousin.framework.network.SocketOptionValue;
import net.lecousin.framework.network.client.SSLClient;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.server.TCPServer;
import net.lecousin.framework.network.server.TCPServerClient;
import net.lecousin.framework.network.server.protocol.SSLServerProtocol;
import net.lecousin.framework.network.server.protocol.ServerProtocol;
import net.lecousin.framework.network.test.AbstractNetworkTest;
import net.lecousin.framework.util.Provider;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestTCP extends AbstractNetworkTest {

	private static TCPServer server;
	private static TCPServer serverSSL;
	private static TCPServer echoServer;
	
	public static class TestProtocol implements ServerProtocol {

		@Override
		public void startProtocol(TCPServerClient client) {
			client.send(ByteBuffer.wrap(new String("Welcome\n").getBytes(StandardCharsets.US_ASCII)));
			client.setAttribute("welcome", Boolean.TRUE);
			try { client.waitForData(10000); }
			catch (ClosedChannelException e) {
				e.printStackTrace(System.err);
			}
		}

		@Override
		public int getInputBufferSize() {
			return 1024;
		}

		@Override
		public boolean dataReceivedFromClient(TCPServerClient client, ByteBuffer data, Runnable onbufferavailable) {
			System.out.println("Received from client: " + data.remaining());
			Assert.assertTrue(client.getServer() == server || client.getServer() == serverSSL);
			new Task.Cpu.FromRunnable("Handling client request", Task.PRIORITY_NORMAL, () -> {
				while (data.hasRemaining()) {
					StringBuilder msg;
					if (!client.hasAttribute("reading")) {
						msg = new StringBuilder();
						client.setAttribute("reading", msg);
					} else
						msg = (StringBuilder)client.getAttribute("reading");
					byte b = data.get();
					if (b == '\n') {
						String s = msg.toString();
						if (!s.startsWith("I'm ")) {
							if (!s.equals("flood me")) {
								client.send(ByteBuffer.wrap("I don't understand you\n".getBytes(StandardCharsets.US_ASCII)));
								client.close();
								break;
							}
							for (int i = 0; i < 1000; ++i)
								client.send(ByteBuffer.allocate(65536));
							break;
						}
						client.send(ByteBuffer.wrap(("Hello " + s.substring(4) + '\n').getBytes(StandardCharsets.US_ASCII)));
						client.removeAttribute("reading");
						continue;
					}
					msg.append((char)b);
				}
				onbufferavailable.run();
				try { client.waitForData(10000); }
				catch (ClosedChannelException e) {}
			}).start();
			return false;
		}

		@Override
		public LinkedList<ByteBuffer> prepareDataToSend(TCPServerClient client, ByteBuffer data) {
			LinkedList<ByteBuffer> list = new LinkedList<>();
			list.add(data);
			return list;
		}
		
	}

	
	public static class EchoProtocol implements ServerProtocol {

		@Override
		public void startProtocol(TCPServerClient client) {
			try { client.waitForData(10000); }
			catch (ClosedChannelException e) {
				e.printStackTrace(System.err);
			}
		}

		@Override
		public int getInputBufferSize() {
			return 1024;
		}

		@Override
		public boolean dataReceivedFromClient(TCPServerClient client, ByteBuffer data, Runnable onbufferavailable) {
			System.out.println("Received from echo client: " + data.remaining());
			new Task.Cpu.FromRunnable("Handling client request", Task.PRIORITY_NORMAL, () -> {
				client.send(data).listenInline(() -> {
					onbufferavailable.run();
					try { client.waitForData(10000); }
					catch (ClosedChannelException e) {
						e.printStackTrace(System.err);
					}
				});
			}).start();
			return false;
		}

		@Override
		public LinkedList<ByteBuffer> prepareDataToSend(TCPServerClient client, ByteBuffer data) {
			LinkedList<ByteBuffer> list = new LinkedList<>();
			list.add(data);
			return list;
		}
		
	}

	@BeforeClass
	public static void launchTCPServer() throws Exception {
		server = new TCPServer();
		server.setProtocol(new TestProtocol());
		server.bind(new InetSocketAddress("localhost", 9999), 0);
		InetAddress ipv6 = NetUtil.getLoopbackIPv6Address();
		if (ipv6 != null)
			server.bind(new InetSocketAddress(ipv6, 9999), 0);
		serverSSL = new TCPServer();
		serverSSL.setProtocol(new SSLServerProtocol(sslTest, new TestProtocol()));
		serverSSL.bind(new InetSocketAddress("localhost", 9998), 0);
		if (ipv6 != null)
			serverSSL.bind(new InetSocketAddress(ipv6, 9998), 0);
		echoServer = new TCPServer();
		echoServer.setProtocol(new EchoProtocol());
		echoServer.bind(new InetSocketAddress("localhost", 9997), 0);
		if (ipv6 != null)
			echoServer.bind(new InetSocketAddress(ipv6, 9997), 0);
	}
	
	@AfterClass
	public static void stopTCPServer() {
		server.close();
		serverSSL.close();
	}
	
	@Test(timeout=30000)
	public void testServer() throws Exception {
		server.getLocalAddresses();
		Socket s = new Socket("localhost", 9999);
		expect(s, "Welcome");
		send(s, "I'm Tester");
		expect(s, "Hello Tester");
		s.close();
		InetAddress ipv6 = NetUtil.getLoopbackIPv6Address();
		if (ipv6 != null)
			s = new Socket(ipv6, 9999);
		else
			s = new Socket("localhost", 9999);
		expect(s, "Welcome");
		send(s, "Hello");
		expect(s, "I don't understand you");
		s.close();
	}
	
	@Test(timeout=30000)
	public void testSSLServer() throws Exception {
		Socket s = sslTest.getSocketFactory().createSocket("localhost", 9998);
		expect(s, "Welcome");
		send(s, "I'm Secure Tester");
		expect(s, "Hello Secure Tester");
		s.close();
	}
	
	@Test(timeout=30000)
	public void testTCPClient() throws Exception {
		TCPClient client = new TCPClient();
		SynchronizationPoint<IOException> sp = client.connect(new InetSocketAddress("localhost", 9999), 10000);
		sp.blockThrow(0);
		client.getLocalAddress();
		Assert.assertFalse(client.hasAttribute("test"));
		client.setAttribute("test", "true");
		Assert.assertTrue(client.hasAttribute("test"));
		Assert.assertEquals("true", client.getAttribute("test"));
		Assert.assertEquals("true", client.removeAttribute("test"));
		Assert.assertFalse(client.hasAttribute("test"));
		expect(client, "Welcome");
		send(client, "I'm Tester");
		expect(client, "Hello Tester");
		client.close();
		
		client = new TCPClient();
		sp = client.connect(new InetSocketAddress("localhost", 9999), 0);
		sp.blockThrow(0);
		expect(client, "Welcome");
		send(client, "Hello");
		expect(client, "I don't understand you");
		client.close();
		
		client = new TCPClient();
		sp = client.connect(new InetSocketAddress("localhost", 9999), 10000);
		sp.blockThrow(0);
		Assert.assertArrayEquals("Welcome\n".getBytes(StandardCharsets.US_ASCII), client.getReceiver().readBytes(8, 10000).blockResult(0));
		sp = new SynchronizationPoint<>();
		client.newDataToSendWhenPossible(new Provider<ByteBuffer>() {
			@Override
			public ByteBuffer provide() {
				return ByteBuffer.wrap("test".getBytes());
			}
		}, sp);
		client.close();
		
		client = new TCPClient();
		sp = client.connect(new InetSocketAddress("localhost", 9999), 10000);
		sp.blockThrow(0);
		client.getReceiver().readAvailableBytes(10, 0).blockResult(0);
		client.close();
		
		client = new TCPClient();
		sp = client.connect(new InetSocketAddress("localhost", 9999), 10000);
		sp.blockThrow(0);
		MutableBoolean closed = new MutableBoolean(false);
		client.onclosed(() -> { closed.set(true); });
		Assert.assertFalse(closed.get());
		StringBuilder received = new StringBuilder();
		SynchronizationPoint<Exception> spReceived = new SynchronizationPoint<>();
		client.getReceiver().readForEver(256, 0, (data) -> {
			while (data.hasRemaining())
				received.append((char)data.get());
			if (received.toString().endsWith("Hello Message 2\n"))
				spReceived.unblock();
		});
		Assert.assertFalse(closed.get());
		send(client, "I'm Message 1");
		Assert.assertFalse(closed.get());
		send(client, "I'm Message 2");
		Assert.assertFalse(closed.get());
		spReceived.block(10000);
		Assert.assertFalse(closed.get());
		Assert.assertEquals("Welcome\nHello Message 1\nHello Message 2\n", received.toString());
		Assert.assertFalse(closed.get());
		client.close();
		Assert.assertTrue(closed.get());
	}
	
	@Test(timeout=30000)
	public void testSSLClient() throws Exception {
		SSLClient client = new SSLClient(sslTest);
		SynchronizationPoint<IOException> sp = client.connect(new InetSocketAddress("localhost", 9998), 10000);
		sp.blockThrow(0);
		expect(client, "Welcome");
		send(client, "I'm Secure Tester");
		expect(client, "Hello Secure Tester");
		client.close();
	}
	
	@Test
	public void testFloodMe() throws Exception {
		TCPClient client = new TCPClient();
		SynchronizationPoint<IOException> sp = client.connect(new InetSocketAddress("localhost", 9999), 10000);
		sp.blockThrow(0);
		expect(client, "Welcome");
		send(client, "flood me");
		client.getReceiver().readBytes(1000 * 65536, 15000);
		client.close();
	}
	
	@Test(timeout=120000)
	public void testEcho() throws Exception {
		LCCore.getApplication().getLoggerFactory().getLogger("network").setLevel(Level.DEBUG);
		LCCore.getApplication().getLoggerFactory().getLogger("network-data").setLevel(Level.DEBUG);
		LCCore.getApplication().getLoggerFactory().getLogger(TCPClient.class).setLevel(Level.DEBUG);
		TCPClient client = new TCPClient();
		SynchronizationPoint<IOException> sp = client.connect(new InetSocketAddress("localhost", 9997), 10000, new SocketOptionValue<>(StandardSocketOptions.SO_SNDBUF, Integer.valueOf(512)));
		sp.blockThrow(0);
		byte[] data = new byte[100000];
		for (int i = 0; i < data.length; ++i)
			data[i] = (byte)i;
		for (int i = 0; i < 10; ++i)
			client.send(ByteBuffer.wrap(data));
		for (int i = 0; i < 10; ++i)
			Assert.assertArrayEquals(data, client.getReceiver().readBytes(data.length, 10000).blockResult(0));
		client.close();
		LCCore.getApplication().getLoggerFactory().getLogger(TCPClient.class).setLevel(Level.TRACE);
		LCCore.getApplication().getLoggerFactory().getLogger("network-data").setLevel(Level.TRACE);
		LCCore.getApplication().getLoggerFactory().getLogger("network").setLevel(Level.TRACE);
	}
	
	@SuppressWarnings("resource")
	@Test(timeout=30000)
	public void testInvalidConnection() throws Exception {
		TCPClient client = new TCPClient();
		SynchronizationPoint<IOException> sp = client.connect(new InetSocketAddress("localhost", 9990), 10000);
		try { sp.blockThrow(0); throw new Exception("Connection should fail"); }
		catch (ConnectException e) {
			// expected
		}
		client.close();
		
		client = new TCPClient();
		sp = client.connect(new InetSocketAddress("0.0.0.1", 80), 10000);
		try { sp.blockThrow(0); throw new Exception("Connection should fail"); }
		catch (SocketException e) {
			// expected
		}
		client.close();
		
		client = new SSLClient();
		sp = client.connect(new InetSocketAddress("localhost", 9990), 10000);
		try { sp.blockThrow(0); throw new Exception("Connection should fail"); }
		catch (ConnectException e) {
			// expected
		}
		client.close();
		
		client = new SSLClient();
		sp = client.connect(new InetSocketAddress("0.0.0.1", 80), 10000);
		try { sp.blockThrow(0); throw new Exception("Connection should fail"); }
		catch (SocketException e) {
			// expected
		}
		client.close();

		
		client = new SSLClient();
		sp = client.connect(new InetSocketAddress("localhost", 9999), 10000);
		try { sp.blockThrow(0); throw new Exception("Connection should fail"); }
		catch (SSLException e) {
			// expected
		}
		client.close();
	}
	
	@SuppressWarnings("resource")
	public static void expect(Socket s, String message) throws Exception {
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
	
	public static void expect(TCPClient client, String message) throws Exception {
		@SuppressWarnings("resource")
		ByteArrayIO io = client.getReceiver().readUntil((byte)'\n', 128, 10000).blockResult(0);
		Assert.assertEquals(message, io.getAsString(StandardCharsets.US_ASCII));
	}
	
	public static void send(Socket s, String message) throws Exception {
		send(s, message.getBytes(StandardCharsets.US_ASCII), 0);
	}
	
	private static void send(Socket s, byte[] message, int pos) throws Exception {
		int rem = message.length - pos;
		if (rem < 3) {
			s.getOutputStream().write(message, pos, rem);
			s.getOutputStream().write('\n');
			s.getOutputStream().flush();
		} else {
			s.getOutputStream().write(message, pos, rem / 2 + 1);
			s.getOutputStream().flush();
			send(s, message, pos + rem / 2 + 1);
		}
	}
	
	public static void send(TCPClient client, String message) throws Exception {
		send(client, message.getBytes(StandardCharsets.US_ASCII), 0);
	}
	
	private static void send(TCPClient client, byte[] message, int pos) throws Exception {
		int rem = message.length - pos;
		if (rem < 3) {
			client.send(ByteBuffer.wrap(message, pos, rem));
			client.send(ByteBuffer.wrap(new byte[] { (byte)'\n' }));
		} else {
			client.send(ByteBuffer.wrap(message, pos, rem / 2 + 1));
			send(client, message, pos + rem / 2 + 1);
		}
	}
	
}
