package net.lecousin.framework.network.tests;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;

import javax.net.ssl.SSLException;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.util.DataUtil;
import net.lecousin.framework.log.Logger.Level;
import net.lecousin.framework.mutable.Mutable;
import net.lecousin.framework.mutable.MutableBoolean;
import net.lecousin.framework.mutable.MutableInteger;
import net.lecousin.framework.network.NetUtil;
import net.lecousin.framework.network.SocketOptionValue;
import net.lecousin.framework.network.TCPRemote;
import net.lecousin.framework.network.client.SSLClient;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.server.TCPServer;
import net.lecousin.framework.network.server.TCPServerClient;
import net.lecousin.framework.network.server.protocol.SSLServerProtocol;
import net.lecousin.framework.network.server.protocol.ServerProtocol;
import net.lecousin.framework.network.ssl.SSLLayer;
import net.lecousin.framework.network.test.AbstractNetworkTest;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.BlockJUnit4ClassRunner;

@RunWith(BlockJUnit4ClassRunner.class)
public class TestTCP extends AbstractNetworkTest {

	private static TCPServer server;
	private static TCPServer serverSSL;
	private static TCPServer echoServer;
	private static TCPServer sendDataServer;
	private static TCPServer receiveDataServer;
	
	private static final int NB_BLOCKS = 250; 
	private static final int BLOCK_SIZE = 12345; 
	
	public static class TestProtocol implements ServerProtocol {

		@Override
		public void startProtocol(TCPServerClient client) {
			client.send(ByteBuffer.wrap(new String("Welcome\n").getBytes(StandardCharsets.US_ASCII)));
			client.setAttribute("welcome", Boolean.TRUE);
			try { client.waitForData(10000); }
			catch (ClosedChannelException e) {
				e.printStackTrace(System.err);
			}
			Closeable c = new Closeable() { @Override public void close() {} };
			client.addToClose(c);
			client.removeToClose(c);
			Async<Exception> sp = new Async<>();
			client.addPending(sp);
			sp.unblock();
			client.addPending(sp);
			try {
				client.getLocalAddress();
				client.getRemoteAddress();
				client.getClientAddress();
			} catch (Throwable t) {
				t.printStackTrace(System.err);
			}
		}

		@Override
		public int getInputBufferSize() {
			return 1024;
		}

		@Override
		public void dataReceivedFromClient(TCPServerClient client, ByteBuffer data, Runnable onbufferavailable) {
			System.out.println("Received from client: " + data.remaining());
			Assert.assertTrue(client.getServer() == server || client.getServer() == serverSSL);
			int timeout = 10000;
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
						for (int i = 0; i < 1000; ++i) {
							byte[] buf = new byte[1024 * 1024];
							DataUtil.writeIntegerLittleEndian(buf, i, i);
							client.send(ByteBuffer.wrap(buf));
						}
						timeout = 1200000;
						break;
					}
					client.send(ByteBuffer.wrap(("Hello " + s.substring(4) + '\n').getBytes(StandardCharsets.US_ASCII)));
					client.removeAttribute("reading");
					continue;
				}
				msg.append((char)b);
			}
			onbufferavailable.run();
			try { client.waitForData(timeout); }
			catch (ClosedChannelException e) {}
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
		public void dataReceivedFromClient(TCPServerClient client, ByteBuffer data, Runnable onbufferavailable) {
			System.out.println("Received from echo client: " + data.remaining());
			client.send(data).onDone(() -> {
				onbufferavailable.run();
				try { client.waitForData(10000); }
				catch (ClosedChannelException e) {
					e.printStackTrace(System.err);
				}
			});
		}

		@Override
		public LinkedList<ByteBuffer> prepareDataToSend(TCPServerClient client, ByteBuffer data) {
			LinkedList<ByteBuffer> list = new LinkedList<>();
			list.add(data);
			return list;
		}
		
	}

	private static byte[][] generateDataToSend() {
		byte[][] data = new byte[NB_BLOCKS][BLOCK_SIZE];
		for (int i = 0; i < NB_BLOCKS; ++i) {
			for (int j = 0; j < BLOCK_SIZE; ++j)
				data[i][j] = (byte)i;
		}
		return data;
	}
	
	private static void sendDataLoop(TCPRemote remote) {
		byte[][] data = generateDataToSend();
		for (int i = 0; i < NB_BLOCKS; ++i) {
			remote.send(ByteBuffer.wrap(data[i]));
			if ((i % 20) == 0)
				try { Thread.sleep(400); }
				catch (InterruptedException e) {}
			else if ((i % 5) == 0)
				try { Thread.sleep(100); }
				catch (InterruptedException e) {}
		}
	}
	
	public static class SendDataProtocol implements ServerProtocol {

		@Override
		public void startProtocol(TCPServerClient client) {
			new Thread() {
				@Override
				public void run() {
					sendDataLoop(client);
				}
			}.start();
		}

		@Override
		public int getInputBufferSize() {
			return 1024;
		}

		@Override
		public void dataReceivedFromClient(TCPServerClient client, ByteBuffer data, Runnable onbufferavailable) {
			client.close();
			onbufferavailable.run();
		}

		@Override
		public LinkedList<ByteBuffer> prepareDataToSend(TCPServerClient client, ByteBuffer data) {
			LinkedList<ByteBuffer> list = new LinkedList<>();
			list.add(data);
			return list;
		}
		
	}
	
	public static class ReceiveDataProtocol implements ServerProtocol {

		@Override
		public void startProtocol(TCPServerClient client) {
			client.setAttribute("block_counter", Integer.valueOf(0));
			client.setAttribute("byte_counter", Integer.valueOf(0));
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
		public void dataReceivedFromClient(TCPServerClient client, ByteBuffer data, Runnable onbufferavailable) {
			int block = ((Integer)client.getAttribute("block_counter")).intValue();
			int index = ((Integer)client.getAttribute("byte_counter")).intValue();
			while (data.hasRemaining()) {
				if (block >= NB_BLOCKS) {
					System.err.println("ERROR: Unexpected data after the end");
					client.close();
					return;
				}
				byte b = data.get();
				if (b != (byte)block) {
					System.err.println("ERROR: Unexpected byte " + b + " at block " + block + " byte " + index + ", expected is " + (byte)block);
					client.close();
					return;
				}
				if (++index == BLOCK_SIZE) {
					System.out.println("Block " + block + " received from client.");
					index = 0;
					block++;
				}
			}
			client.setAttribute("block_counter", Integer.valueOf(block));
			client.setAttribute("byte_counter", Integer.valueOf(index));
			onbufferavailable.run();
			if (block == NB_BLOCKS) {
				client.send(ByteBuffer.wrap(new byte[] { 'O', 'K', '\n' }));
				return;
			}
			try { client.waitForData(10000); }
			catch (ClosedChannelException e) {}
		}

		@Override
		public LinkedList<ByteBuffer> prepareDataToSend(TCPServerClient client, ByteBuffer data) {
			LinkedList<ByteBuffer> list = new LinkedList<>();
			list.add(data);
			return list;
		}
		
	}
	
	private static SocketAddress ipv4Address;
	private static SocketAddress ipv6Address;
	private static SocketAddress ipv4SSLAddress;
	private static SocketAddress ipv6SSLAddress;
	private static SocketAddress ipv4EchoAddress;
	private static SocketAddress ipv6EchoAddress;
	private static SocketAddress ipv4DataAddress;
	private static SocketAddress ipv4ReceiveAddress;
	private static SocketAddress ipv6ReceiveAddress;
	
	@BeforeClass
	public static void launchTCPServer() throws Exception {
		server = new TCPServer();
		server.setProtocol(new TestProtocol());
		ipv4Address = server.bind(new InetSocketAddress("localhost", 0), 0).blockResult(0);
		InetAddress ipv6 = NetUtil.getLoopbackIPv6Address();
		if (ipv6 != null)
			ipv6Address = server.bind(new InetSocketAddress(ipv6, 0), 0).blockResult(0);
		serverSSL = new TCPServer();
		serverSSL.setProtocol(new SSLServerProtocol(sslTest, new TestProtocol()));
		ipv4SSLAddress = serverSSL.bind(new InetSocketAddress("localhost", 0), 0).blockResult(0);
		if (ipv6 != null)
			ipv6SSLAddress = serverSSL.bind(new InetSocketAddress(ipv6, 0), 0).blockResult(0);
		echoServer = new TCPServer();
		echoServer.setProtocol(new EchoProtocol());
		ipv4EchoAddress = echoServer.bind(new InetSocketAddress("localhost", 0), 0).blockResult(0);
		if (ipv6 != null)
			ipv6EchoAddress = echoServer.bind(new InetSocketAddress(ipv6, 0), 0).blockResult(0);
		sendDataServer = new TCPServer();
		sendDataServer.setProtocol(new SendDataProtocol());
		ipv4DataAddress = sendDataServer.bind(new InetSocketAddress("localhost", 0), 0).blockResult(0);
		receiveDataServer = new TCPServer();
		receiveDataServer.setProtocol(new ReceiveDataProtocol());
		ipv4ReceiveAddress = receiveDataServer.bind(new InetSocketAddress("localhost", 0), 0).blockResult(0);
		if (ipv6 != null)
			ipv6ReceiveAddress = receiveDataServer.bind(new InetSocketAddress(ipv6, 0), 0).blockResult(0);
		
		Assert.assertTrue(server.getProtocol() instanceof TestProtocol);
		Assert.assertEquals(0, server.getConnectedClients().size());
	}
	
	@AfterClass
	public static void stopTCPServer() {
		server.close();
		serverSSL.close();
	}
	
	@Test
	public void testServer() throws Exception {
		Assert.assertEquals(0, server.getConnectedClients().size());
		server.getLocalAddresses();
		Socket s = new Socket();
		s.connect(ipv4Address);
		expect(s, "Welcome");
		send(s, "I'm Tester");
		expect(s, "Hello Tester");
		
		ArrayList<Closeable> clients = server.getConnectedClients();
		Assert.assertEquals(1, clients.size());
		clients.get(0).toString();
		
		s.close();
		try { Thread.sleep(1000); } catch (InterruptedException e) {}
		Assert.assertEquals(0, server.getConnectedClients().size());
		InetAddress ipv6 = NetUtil.getLoopbackIPv6Address();
		s = new Socket();
		if (ipv6 != null)
			s.connect(ipv6Address);
		else
			s.connect(ipv4Address);
		expect(s, "Welcome");
		send(s, "Hello");
		expect(s, "I don't understand you");
		s.close();
		try { Thread.sleep(1000); } catch (InterruptedException e) {}
		Assert.assertEquals(0, server.getConnectedClients().size());
	}
	
	@Test
	public void testSSLServer() throws Exception {
		Socket s = sslTest.getSocketFactory().createSocket();
		s.connect(ipv4SSLAddress);
		expect(s, "Welcome");
		send(s, "I'm Secure Tester");
		expect(s, "Hello Secure Tester");
		s.close();
		if (ipv6SSLAddress != null) {
			s = sslTest.getSocketFactory().createSocket();
			s.connect(ipv6SSLAddress);
			expect(s, "Welcome");
			send(s, "I'm Secure Tester");
			expect(s, "Hello Secure Tester");
			s.close();
		}
	}
	
	@Test
	public void testTCPClient() throws Exception {
		Assert.assertEquals(0, server.getConnectedClients().size());
		TCPClient client = new TCPClient();
		Async<IOException> sp = client.connect(ipv4Address, 10000);
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
		try { Thread.sleep(1000); } catch (InterruptedException e) {}
		Assert.assertEquals(0, server.getConnectedClients().size());
		
		if (ipv6Address != null) {
			client = new TCPClient();
			sp = client.connect(ipv6Address, 10000);
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
			try { Thread.sleep(1000); } catch (InterruptedException e) {}
			Assert.assertEquals(0, server.getConnectedClients().size());
		}
		
		client = new TCPClient();
		sp = client.connect(ipv4Address, 0);
		sp.blockThrow(0);
		expect(client, "Welcome");
		send(client, "Hello");
		expect(client, "I don't understand you");
		client.close();
		try { Thread.sleep(1000); } catch (InterruptedException e) {}
		Assert.assertEquals(0, server.getConnectedClients().size());
		
		client = new TCPClient();
		sp = client.connect(ipv4Address, 10000);
		sp.blockThrow(0);
		Assert.assertArrayEquals("Welcome\n".getBytes(StandardCharsets.US_ASCII), client.getReceiver().readBytes(8, 10000).blockResult(0));
		sp = new Async<>();
		client.newDataToSendWhenPossible(() -> ByteBuffer.wrap("test".getBytes()), sp);
		client.close();
		try { Thread.sleep(1000); } catch (InterruptedException e) {}
		Assert.assertEquals(0, server.getConnectedClients().size());
		
		client = new TCPClient();
		sp = client.connect(ipv4Address, 10000);
		sp.blockThrow(0);
		client.getReceiver().readAvailableBytes(10, 0).blockResult(0);
		client.close();
		try { Thread.sleep(1000); } catch (InterruptedException e) {}
		Assert.assertEquals(0, server.getConnectedClients().size());
		
		client = new TCPClient();
		sp = client.connect(ipv4Address, 10000);
		sp.blockThrow(0);
		MutableBoolean closed = new MutableBoolean(false);
		client.onclosed(() -> { closed.set(true); });
		Assert.assertFalse(closed.get());
		StringBuilder received = new StringBuilder();
		Async<Exception> spReceived = new Async<>();
		client.getReceiver().readForEver(256, 0, (data) -> {
			while (data.hasRemaining())
				received.append((char)data.get());
			if (received.toString().endsWith("Hello Message 2\n"))
				spReceived.unblock();
		}, true);
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
		try { Thread.sleep(1000); } catch (InterruptedException e) {}
		Assert.assertEquals(0, server.getConnectedClients().size());
	}
	
	@Test
	public void testSSLClient() throws Exception {
		SSLClient client = new SSLClient(sslTest);
		Async<IOException> sp = client.connect(ipv4SSLAddress, 10000);
		sp.blockThrow(0);
		expect(client, "Welcome");
		send(client, "I'm Secure Tester");
		expect(client, "Hello Secure Tester");
		client.close();
	}
	
	@Test
	public void testFloodMe() throws Exception {
		Assert.assertEquals(0, server.getConnectedClients().size());
		LCCore.getApplication().getLoggerFactory().getLogger("network-data").setLevel(Level.DEBUG);
		LCCore.getApplication().getLoggerFactory().getLogger("network").setLevel(Level.DEBUG);
		TCPClient client = new TCPClient();
		Async<IOException> sp = client.connect(ipv4Address, 10000);
		sp.blockThrow(0);
		expect(client, "Welcome");
		send(client, "flood me");
		for (int i = 0; i < 1000; ++i) {
			try {
				byte[] buf = client.getReceiver().readBytes(1024 * 1024, 15000).blockResult(0);
				Assert.assertEquals("Buffer " + i, 1024 * 1024, buf.length);
				Assert.assertEquals(i, DataUtil.readIntegerLittleEndian(buf, i));
			} catch (IOException e) {
				throw new Exception("Error reading buffer " + i, e);
			}
			try { Thread.sleep(30); }
			catch (InterruptedException e) { break; }
		}
		client.close();
		LCCore.getApplication().getLoggerFactory().getLogger("network-data").setLevel(Level.TRACE);
		LCCore.getApplication().getLoggerFactory().getLogger("network").setLevel(Level.TRACE);
		try { Thread.sleep(1000); } catch (InterruptedException e) {}
		Assert.assertEquals(0, server.getConnectedClients().size());
	}
	
	@Test
	public void testFloodMeSSL() throws Exception {
		LCCore.getApplication().getLoggerFactory().getLogger("network-data").setLevel(Level.INFO);
		LCCore.getApplication().getLoggerFactory().getLogger("network").setLevel(Level.INFO);
		LCCore.getApplication().getLoggerFactory().getLogger(SSLLayer.class).setLevel(Level.INFO);
		LCCore.getApplication().getLoggerFactory().getLogger(TCPClient.class).setLevel(Level.INFO);
		SSLClient client = new SSLClient(sslTest);
		Async<IOException> sp = client.connect(ipv4SSLAddress, 10000);
		sp.blockThrow(0);
		expect(client, "Welcome");
		send(client, "flood me");
		for (int i = 0; i < 1000; ++i) {
			try {
				byte[] buf = client.getReceiver().readBytes(1024 * 1024, 15000).blockResult(0);
				Assert.assertEquals("Buffer " + i, 1024 * 1024, buf.length);
				Assert.assertEquals(i, DataUtil.readIntegerLittleEndian(buf, i));
			} catch (IOException e) {
				throw new Exception("Error reading buffer " + i, e);
			}
			try { Thread.sleep(30); }
			catch (InterruptedException e) { break; }
		}
		client.close();
		LCCore.getApplication().getLoggerFactory().getLogger("network-data").setLevel(Level.TRACE);
		LCCore.getApplication().getLoggerFactory().getLogger("network").setLevel(Level.TRACE);
		LCCore.getApplication().getLoggerFactory().getLogger(SSLLayer.class).setLevel(Level.TRACE);
		LCCore.getApplication().getLoggerFactory().getLogger(TCPClient.class).setLevel(Level.TRACE);
	}
	
	@Test
	public void testEcho() throws Exception {
		LCCore.getApplication().getLoggerFactory().getLogger("network").setLevel(Level.DEBUG);
		LCCore.getApplication().getLoggerFactory().getLogger("network-data").setLevel(Level.DEBUG);
		LCCore.getApplication().getLoggerFactory().getLogger(TCPClient.class).setLevel(Level.DEBUG);
		TCPClient client = new TCPClient();
		Async<IOException> sp = client.connect(ipv4EchoAddress, 10000, new SocketOptionValue<>(StandardSocketOptions.SO_SNDBUF, Integer.valueOf(512)));
		sp.blockThrow(0);
		byte[] data = new byte[100000];
		for (int i = 0; i < data.length; ++i)
			data[i] = (byte)i;
		for (int i = 0; i < 10; ++i)
			client.send(ByteBuffer.wrap(data));
		for (int i = 0; i < 10; ++i)
			Assert.assertArrayEquals(data, client.getReceiver().readBytes(data.length, 10000).blockResult(0));
		client.close();
		if (ipv6EchoAddress != null) {
			client = new TCPClient();
			sp = client.connect(ipv6EchoAddress, 10000, new SocketOptionValue<>(StandardSocketOptions.SO_SNDBUF, Integer.valueOf(512)));
			sp.blockThrow(0);
			data = new byte[100000];
			for (int i = 0; i < data.length; ++i)
				data[i] = (byte)i;
			for (int i = 0; i < 10; ++i)
				client.send(ByteBuffer.wrap(data));
			for (int i = 0; i < 10; ++i)
				Assert.assertArrayEquals(data, client.getReceiver().readBytes(data.length, 10000).blockResult(0));
			client.close();
		}
		LCCore.getApplication().getLoggerFactory().getLogger(TCPClient.class).setLevel(Level.TRACE);
		LCCore.getApplication().getLoggerFactory().getLogger("network-data").setLevel(Level.TRACE);
		LCCore.getApplication().getLoggerFactory().getLogger("network").setLevel(Level.TRACE);
	}
	
	@SuppressWarnings("resource")
	@Test
	public void testInvalidConnection() throws Exception {
		TCPServer closedServer = new TCPServer();
		closedServer.setProtocol(new TestProtocol());
		SocketAddress closedAddress = closedServer.bind(new InetSocketAddress("localhost", 0), 0).blockResult(0);
		closedServer.close();

		TCPClient client = new TCPClient();
		Async<IOException> sp = client.connect(closedAddress, 10000);
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
		sp = client.connect(closedAddress, 10000);
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

		
		Assert.assertEquals(0, server.getConnectedClients().size());
		client = new SSLClient();
		sp = client.connect(ipv4Address, 10000);
		try { sp.blockThrow(0); throw new Exception("Connection should fail"); }
		catch (SSLException e) {
			// expected
		}
		client.close();
		try { Thread.sleep(1000); } catch (InterruptedException e) {}
		Assert.assertEquals(0, server.getConnectedClients().size());
	}
	
	@Test
	public void testSendDataClientToServer() throws Exception {
		try {
			LCCore.getApplication().getLoggerFactory().getLogger("network-data").setLevel(Level.INFO);
			LCCore.getApplication().getLoggerFactory().getLogger("network").setLevel(Level.INFO);
			TCPClient client = new TCPClient();
			Async<IOException> sp = client.connect(ipv4ReceiveAddress, 10000);
			sp.blockThrow(0);
			sendDataLoop(client);
			expect(client, "OK");
			client.close();
			if (ipv6ReceiveAddress != null) {
				client = new TCPClient();
				sp = client.connect(ipv6ReceiveAddress, 10000);
				sp.blockThrow(0);
				sendDataLoop(client);
				expect(client, "OK");
				client.close();
			}
		} finally {
			LCCore.getApplication().getLoggerFactory().getLogger("network").setLevel(Level.TRACE);
			LCCore.getApplication().getLoggerFactory().getLogger("network-data").setLevel(Level.TRACE);
		}
	}
	
	@Test
	public void testSendDataServerToClient() throws Exception {
		try {
			LCCore.getApplication().getLoggerFactory().getLogger("network-data").setLevel(Level.INFO);
			LCCore.getApplication().getLoggerFactory().getLogger("network").setLevel(Level.INFO);
			TCPClient client = new TCPClient();
			Async<IOException> sp = client.connect(ipv4DataAddress, 10000);
			sp.blockThrow(0);
			MutableInteger block = new MutableInteger(0);
			Mutable<AsyncSupplier<byte[], IOException>> read = new Mutable<>(null);
			Async<IOException> end = new Async<>();
			Runnable listener = new Runnable() {
				@Override
				public void run() {
					do {
						if (read.get().hasError()) {
							end.error(read.get().getError());
							return;
						}
						for (int i = 0; i < BLOCK_SIZE; ++i)
							if (read.get().getResult()[i] != (byte)block.get()) {
								end.error(new IOException("Unexpected byte " + read.get().getResult()[i] + " in block " + block.get() + " index " + i + " expected is " + (byte)block.get()));
								return;
							}
						if (block.inc() == NB_BLOCKS) {
							end.unblock();
							break;
						}
						read.set(client.getReceiver().readBytes(BLOCK_SIZE, 10000));
						if (read.get().isDone())
							continue;
						read.get().onDone(this);
						break;
					} while (true);
				}
			};
			// wait few seconds so the server is filling the socket buffer
			try { Thread.sleep(2000); }
			catch (InterruptedException e) {}
			read.set(client.getReceiver().readBytes(BLOCK_SIZE, 10000));
			read.get().onDone(listener);
			end.blockThrow(0);
			try {
				byte[] remaining = client.getReceiver().readBytes(BLOCK_SIZE, 1000).blockResult(0);
				client.close();
				if (remaining.length > 0)
					throw new AssertionError("Unexpected bytes after the end: " + remaining.length);
			} catch (IOException e) {
				// expected
				client.close();
			}
		} finally {
			LCCore.getApplication().getLoggerFactory().getLogger("network").setLevel(Level.TRACE);
			LCCore.getApplication().getLoggerFactory().getLogger("network-data").setLevel(Level.TRACE);
		}
	}
	
	// --- Utilities
	
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
