package net.lecousin.framework.network.tests.tcp;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.concurrent.util.PartialAsyncConsumer;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.util.DataUtil;
import net.lecousin.framework.mutable.MutableBoolean;
import net.lecousin.framework.network.client.SSLClient;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.server.protocol.SSLServerProtocol;
import net.lecousin.framework.network.server.protocol.ServerProtocol;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import junit.framework.AssertionFailedError;

public class TestTCPWelcomeProtocol extends AbstractTestTCP {
	
	public TestTCPWelcomeProtocol(boolean useSSL, boolean useIPv6) {
		super(useSSL, useIPv6);
	}

	@Override
	protected ServerProtocol createProtocol() {
		return new WelcomeProtocol();
	}
	
	@BeforeClass
	public static void reduceLogs() {
		deactivateNetworkTraces();
	}
	
	@AfterClass
	public static void putBackLogs() {
		activateNetworkTraces();
	}

	@Test
	public void testWithSocket() throws Exception {
		if (!(server.getProtocol() instanceof WelcomeProtocol))
			Assert.assertTrue(((SSLServerProtocol)server.getProtocol()).getInnerProtocol() instanceof WelcomeProtocol);
		Assert.assertEquals(0, server.getConnectedClients().size());
		
		server.getLocalAddresses();
		Socket s = connectSocket();
		expectLine(s, "Welcome");
		sendLine(s, "I'm Socket Tester");
		expectLine(s, "Hello Socket Tester");
		
		ArrayList<Closeable> clients = server.getConnectedClients();
		Assert.assertEquals(1, clients.size());
		clients.get(0).toString();
		
		s.close();
		try { Thread.sleep(1000); } catch (InterruptedException e) {}
		Assert.assertEquals(0, server.getConnectedClients().size());
	}

	@Test
	public void testWithClient() throws Exception {
		Assert.assertEquals(0, server.getConnectedClients().size());

		TCPClient client = connectClient();
		client.getLocalAddress();
		Assert.assertFalse(client.hasAttribute("test"));
		client.setAttribute("test", "true");
		Assert.assertTrue(client.hasAttribute("test"));
		Assert.assertEquals("true", client.getAttribute("test"));
		Assert.assertEquals("true", client.removeAttribute("test"));
		Assert.assertFalse(client.hasAttribute("test"));
		
		expectLine(client, "Welcome");
		sendLine(client, "I'm Client Tester");
		expectLine(client, "Hello Client Tester");
		client.close();
		try { Thread.sleep(1000); } catch (InterruptedException e) {}
		Assert.assertEquals(0, server.getConnectedClients().size());
		
		client = connectClient();
		expectLine(client, "Welcome");
		sendLine(client, "Hello");
		expectLine(client, "I don't understand you");
		client.close();
		try { Thread.sleep(1000); } catch (InterruptedException e) {}
		Assert.assertEquals(0, server.getConnectedClients().size());
	}
	
	@Test
	public void testClientReceiverReadBytes() throws Exception {
		try (TCPClient client = connectClient()) {
			Async<IOException> sp = new Async<>();
			client.newDataToSendWhenPossible(() -> Collections.singletonList(ByteBuffer.wrap("I'm test\n".getBytes())), sp, 5000);
			Assert.assertArrayEquals("Welco".getBytes(StandardCharsets.US_ASCII), client.getReceiver().readBytes(5, 10000).blockResult(0));
			Assert.assertArrayEquals("me\nHello ".getBytes(StandardCharsets.US_ASCII), client.getReceiver().readBytes(9, 10000).blockResult(0));
			Assert.assertArrayEquals("test\n".getBytes(StandardCharsets.US_ASCII), client.getReceiver().readBytes(5, 10000).blockResult(0));
		}
		try { Thread.sleep(1000); } catch (InterruptedException e) {}
		Assert.assertEquals(0, server.getConnectedClients().size());
		try (TCPClient client = connectClient()) {
			sendLine(client, "Bye");
			Assert.assertArrayEquals("Welco".getBytes(StandardCharsets.US_ASCII), client.getReceiver().readBytes(5, 10000).blockResult(0));
			Assert.assertArrayEquals("me\nBy".getBytes(StandardCharsets.US_ASCII), client.getReceiver().readBytes(5, 10000).blockResult(0));
			try {
				client.getReceiver().readBytes(2, 5000).blockThrow(0);
				throw new AssertionError();
			} catch (EOFException e) {
				// ok
			}
		}
	}
	
	@Test
	public void testClientReceiverSkipBytes() throws Exception {
		try (TCPClient client = connectClient()) {
			client.getReceiver().skipBytes(1, 10000).blockThrow(0);
			Assert.assertArrayEquals(new byte[] { 'e' }, client.getReceiver().readBytes(1, 10000).blockResult(0));
			client.getReceiver().skipBytes(2, 10000).blockThrow(0);
			Assert.assertArrayEquals(new byte[] { 'o' }, client.getReceiver().readBytes(1, 10000).blockResult(0));
			sendLine(client, "I'm test");
			client.getReceiver().skipBytes(5, 10000).blockThrow(0);
			Assert.assertArrayEquals(new byte[] { 'l' }, client.getReceiver().readBytes(1, 10000).blockResult(0));
			client.getReceiver().skipBytes(7, 10000).blockThrow(0);
			Assert.assertArrayEquals(new byte[] { '\n' }, client.getReceiver().readBytes(1, 10000).blockResult(0));
			Async<IOException> skip = client.getReceiver().skipBytes(1, 10000);
			Async<IOException> sp = new Async<>();
			client.newDataToSendWhenPossible(() -> Collections.singletonList(ByteBuffer.wrap("Bye\n".getBytes())), sp, 5000);
			skip.blockThrow(0);
			Assert.assertArrayEquals(new byte[] { 'y' }, client.getReceiver().readBytes(1, 10000).blockResult(0));
			try {
				client.getReceiver().skipBytes(5, 10000).blockThrow(0);
				throw new AssertionError();
			} catch (EOFException e) {
				// ok
			}
		}
	}
	
	@Test
	public void testClientReceiverReadAvailableBytes() throws Exception {
		TCPClient client = connectClient();
		client.getReceiver().readAvailableBytes(10, 0).blockResult(0);
		client.close();
		try { Thread.sleep(1000); } catch (InterruptedException e) {}
		Assert.assertEquals(0, server.getConnectedClients().size());
	}
	
	@Test
	public void testClientReceiverReadForEver() throws Exception {
		TCPClient client = connectClient();
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
		sendLine(client, "I'm Message 1");
		Assert.assertFalse(closed.get());
		sendLine(client, "I'm Message 2");
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
	public void testClientReceiverReadForEverWithRemainingData() throws Exception {
		TCPClient client = connectClient();
		StringBuilder received = new StringBuilder();
		client.getReceiver().readBytes(1, 10000).blockResult(0);
		Async<Exception> spReceived = new Async<>();
		client.getReceiver().readForEver(256, 0, (data) -> {
			while (data.hasRemaining())
				received.append((char)data.get());
			if (received.toString().endsWith("Hello Message 2\n"))
				spReceived.unblock();
		}, true);
		sendLine(client, "I'm Message 1");
		sendLine(client, "I'm Message 2");
		spReceived.block(10000);
		client.close();
	}

	@Test
	public void testClientReceiverReadForEverWithPartialFirstRead() throws Exception {
		TCPClient client = connectClient();
		StringBuilder received = new StringBuilder();
		Async<Exception> spReceived = new Async<>();
		client.getReceiver().readForEver(256, 0, (data) -> {
			if (received.length() == 0) {
				int len = data.remaining();
				if (len > 1) len--;
				for (int i = 0; i < len; ++i)
					received.append((char)data.get());
			} else
				while (data.hasRemaining())
					received.append((char)data.get());
			if (received.toString().endsWith("Hello Message 2\n"))
				spReceived.unblock();
		}, true);
		sendLine(client, "I'm Message 1");
		sendLine(client, "I'm Message 2");
		spReceived.block(10000);
		Assert.assertEquals("Welcome\nHello Message 1\nHello Message 2\n", received.toString());
		client.close();
	}
	
	private static class TextConsumer implements PartialAsyncConsumer<ByteBuffer, IOException> {
		
		private StringBuilder received = new StringBuilder();
		private String expected;
		
		public TextConsumer(String expected) {
			this.expected = expected;
		}
		
		@Override
		public boolean isExpectingData() {
			return received.length() < expected.length();
		}
		
		@Override
		public AsyncSupplier<Boolean, IOException> consume(ByteBuffer data) {
			while (isExpectingData()) {
				if (!data.hasRemaining())
					return new AsyncSupplier<>(Boolean.FALSE, null);
				received.append((char)(data.get() & 0xFF));
			}
			if (!received.toString().equals(expected))
				return new AsyncSupplier<>(null, new IOException("Unexpected message from server: " + received.toString()));
			return new AsyncSupplier<>(Boolean.TRUE, null);
		}
	}
	
	@Test
	public void testClientReceiverWithConsumer() throws Exception {
		try (TCPClient client = connectClient()) {
			IAsync<IOException> consumed = client.getReceiver().consume(new TextConsumer("Welcome\n"), 8192, 10000);
			sendLine(client, "I'm Message 1");
			sendLine(client, "I'm Message 2");
			consumed.blockThrow(0);
			consumed = client.getReceiver().consume(new TextConsumer("Hello Message 1\n"), 8192, 10000);
			consumed.blockThrow(0);
			consumed = client.getReceiver().consume(new TextConsumer("Hello Message 2\n"), 8192, 10000);
			consumed.blockThrow(0);
			sendLine(client, "I'm Message 3");
			consumed = client.getReceiver().consume(new TextConsumer("Hello Message 3\n"), 8192, 10000);
			consumed.blockThrow(0);
			
			sendLine(client, "I'm A");
			consumed = client.getReceiver().consume(new TextConsumer("Hel"), 8192, 10000);
			consumed.blockThrow(0);
			consumed = client.getReceiver().consume(new TextConsumer("lo A\nHello B\nHello C\n"), 8192, 10000);
			Assert.assertFalse(consumed.isDone());
			sendLine(client, "I'm B").blockThrow(0);
			Assert.assertFalse(consumed.isDone());
			sendLine(client, "I'm C");
			consumed.blockThrow(0);

			sendLine(client, "I'm D");
			consumed = client.getReceiver().consume(new TextConsumer("Hello "), 8192, 10000);
			consumed.blockThrow(0);
			consumed = client.getReceiver().consume(new TextConsumer("D"), 8192, 10000);
			consumed.blockThrow(0);
			consumed = client.getReceiver().consume(new TextConsumer("\n"), 8192, 10000);
			consumed.blockThrow(0);
			
			sendLine(client, "I'm Message 4");
			consumed = client.getReceiver().consume(new TextConsumer("Hello Message 4\nBye XXX"), 8192, 10000);
			sendLine(client, "Bye");
			try {
				consumed.blockThrow(0);
				throw new AssertionError();
			} catch (EOFException e) {
				// ok
			}
		}
	}
	
	@Test
	public void testClientAsConsumer() throws Exception {
		try (TCPClient client = connectClient()) {
			AsyncConsumer<ByteBuffer, IOException> consumer = client.asConsumer(3, 5000);
			consumer.consume(ByteBuffer.wrap(new byte[] { 'B', 'y', 'e', '\n' }));
			consumer.end().blockThrow(0);
			consumer.error(new IOException("test"));
			Assert.assertArrayEquals(new byte[] { 'W', 'e', 'l', 'c', 'o', 'm', 'e', '\n', 'B', 'y', 'e' }, client.getReceiver().readBytes(11, 5000).blockResult(0));
		}
	}
	
	@Test
	public void testFloodMe() throws Exception {
		Assert.assertEquals(0, server.getConnectedClients().size());
		TCPClient client = connectClient();
		expectLine(client, "Welcome");
		sendLine(client, "flood me");
		for (int i = 0; i < 1000; ++i) {
			try {
				byte[] buf = client.getReceiver().readBytes(1024 * 1024, 15000).blockResult(0);
				Assert.assertEquals("Buffer " + i, 1024 * 1024, buf.length);
				Assert.assertEquals(i, DataUtil.Read32.LE.read(buf, i));
				if (i < 10 || i > 995 || (i % 100) == 0)
					System.out.println("Received: " + i);
			} catch (IOException e) {
				throw new Exception("Error reading buffer " + i, e);
			}
			try { Thread.sleep(30); }
			catch (InterruptedException e) { break; }
		}
		client.close();
		try { Thread.sleep(1000); } catch (InterruptedException e) {}
		Assert.assertEquals(0, server.getConnectedClients().size());
	}
	
	@SuppressWarnings("resource")
	@Test
	public void testInvalidConnection() throws Exception {
		server.close();

		try {
			connectClient();
			throw new AssertionFailedError("Connection must fail on a closed server");
		} catch (ConnectException e) {
			// ok
		}
		
		TCPClient client = useSSL ? new SSLClient() : new TCPClient();
		Async<IOException> sp = client.connect(new InetSocketAddress("0.0.0.1", 80), 10000);
		try {
			sp.blockThrow(0);
			throw new AssertionFailedError("Connection must fail on 0.0.0.1");
		} catch (SocketException e) {
			// expected
		}
		client.close();
		
		Assert.assertEquals(0, server.getConnectedClients().size());
	}
	
	@Test
	public void testClientReceiverReadAvailableBytesWithRemainingBytes() throws Exception {
		TCPClient client = connectClient();
		Assert.assertArrayEquals(new byte[] { 'W' }, client.getReceiver().readBytes(1, 10000).blockResult(0));
		byte[] remaining = new byte[] { 'e', 'l', 'c', 'o', 'm', 'e', '\n' };
		int pos = 0;
		while (pos < remaining.length) {
			ByteBuffer b = client.getReceiver().readAvailableBytes(1024, 10000).blockResult(0);
			while (b.hasRemaining()) {
				Assert.assertEquals(remaining[pos++], b.get());
			}
		}
		client.close();
	}
	
	@Test
	public void testClientReceiverReadUntilWithRemainingBytes() throws Exception {
		try (TCPClient client = connectClient()) {
			Assert.assertArrayEquals(new byte[] { 'W' }, client.getReceiver().readBytes(1, 10000).blockResult(0));
			ByteArrayIO io = client.getReceiver().readUntil((byte)'m', 256, 10000).blockResult(0);
			byte[] remaining = new byte[] { 'e', 'l', 'c', 'o' };
			int pos = 0;
			while (pos < remaining.length)
				Assert.assertEquals(remaining[pos++], io.read());
			Assert.assertEquals(-1, io.read());
			
			io = client.getReceiver().readUntil((byte)'\n', 256, 10000).blockResult(0);
			remaining = new byte[] { 'e' };
			pos = 0;
			while (pos < remaining.length)
				Assert.assertEquals(remaining[pos++], io.read());
			Assert.assertEquals(-1, io.read());
			
			sendLine(client, "I'm test");

			io = client.getReceiver().readUntil((byte)'t', 256, 10000).blockResult(0);
			remaining = new byte[] { 'H', 'e', 'l', 'l', 'o', ' ' };
			pos = 0;
			while (pos < remaining.length)
				Assert.assertEquals(remaining[pos++], io.read());
			Assert.assertEquals(-1, io.read());

			AsyncSupplier<ByteArrayIO, IOException> sp = client.getReceiver().readUntil((byte)'x', 256, 10000);
			Assert.assertFalse(sp.isDone());
			sendLine(client, "I'm x");
			io = sp.blockResult(0);
			remaining = new byte[] { 'e', 's', 't', '\n', 'H', 'e', 'l', 'l', 'o', ' ' };
			pos = 0;
			while (pos < remaining.length)
				Assert.assertEquals(remaining[pos++], io.read());
			Assert.assertEquals(-1, io.read());
			
			sendLine(client, "Bye");

			try {
				client.getReceiver().readUntil((byte)'z', 256, 10000).blockResult(0);
				throw new AssertionError();
			} catch (EOFException e) {
				// ok
			}
		}
	}
	
	@Test
	public void testClientReceiverReadByteByByte() throws Exception {
		TCPClient client = connectClient();
		byte[] remaining = new byte[] { 'W', 'e', 'l', 'c', 'o', 'm', 'e', '\n' };
		for (int pos = 0; pos < remaining.length; ++pos) {
			Assert.assertEquals(remaining[pos], client.getReceiver().readBytes(1, 10000).blockResult(0)[0]);
		}
		client.close();
	}

}
