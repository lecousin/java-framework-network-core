package net.lecousin.framework.network.tests.tcp;

import java.io.Closeable;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.io.util.DataUtil;
import net.lecousin.framework.log.Logger.Level;
import net.lecousin.framework.mutable.MutableBoolean;
import net.lecousin.framework.network.client.SSLClient;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.server.protocol.SSLServerProtocol;
import net.lecousin.framework.network.server.protocol.ServerProtocol;
import net.lecousin.framework.network.ssl.SSLLayer;

import org.junit.Assert;
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
		TCPClient client = connectClient();
		Assert.assertArrayEquals("Welcome\n".getBytes(StandardCharsets.US_ASCII), client.getReceiver().readBytes(8, 10000).blockResult(0));
		Async<IOException> sp = new Async<>();
		client.newDataToSendWhenPossible(() -> ByteBuffer.wrap("test".getBytes()), sp);
		client.close();
		try { Thread.sleep(1000); } catch (InterruptedException e) {}
		Assert.assertEquals(0, server.getConnectedClients().size());
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
	public void testFloodMe() throws Exception {
		Assert.assertEquals(0, server.getConnectedClients().size());
		LCCore.getApplication().getLoggerFactory().getLogger("network-data").setLevel(Level.INFO);
		LCCore.getApplication().getLoggerFactory().getLogger("network").setLevel(Level.INFO);
		LCCore.getApplication().getLoggerFactory().getLogger(SSLLayer.class).setLevel(Level.INFO);
		LCCore.getApplication().getLoggerFactory().getLogger(TCPClient.class).setLevel(Level.INFO);
		TCPClient client = connectClient();
		expectLine(client, "Welcome");
		sendLine(client, "flood me");
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

}
