package net.lecousin.framework.network.tests;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.collections.ArrayUtil;
import net.lecousin.framework.concurrent.CancelException;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.JoinPoint;
import net.lecousin.framework.log.Logger.Level;
import net.lecousin.framework.network.client.UDPClient;
import net.lecousin.framework.network.server.UDPServer;
import net.lecousin.framework.network.server.UDPServer.MessageSender;
import net.lecousin.framework.network.test.AbstractNetworkTest;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.BlockJUnit4ClassRunner;

@RunWith(BlockJUnit4ClassRunner.class)
public class TestUDP extends AbstractNetworkTest {

	private UDPServer server;
	private SocketAddress serverAddress;
	private boolean pauseServer = false;
	
	@Before
	public void launchUDPServer() throws Exception {
		server = new UDPServer(1024, new UDPServer.MessageListener() {
			@Override
			public void newMessage(ByteBuffer message, SocketAddress source, MessageSender reply) {
				ByteBuffer r = ByteBuffer.allocate(message.remaining() + 1);
				r.put((byte)51);
				r.put(message);
				r.flip();
				if (!pauseServer)
					reply.reply(r);
			}
		});
		serverAddress = server.bind(new InetSocketAddress("localhost", 0)).blockResult(0);
	}
	
	@After
	public void stopUDPServer() {
		server.close();
	}
	
	@Test
	public void testSendMessagesToServer() throws Exception {
		send("Hello".getBytes());
		send("Test 2".getBytes());
		send("abcdefghijklmnopqrstuvwxyz".getBytes());
	}
	
	@Test
	public void testUDPClient() throws Exception {
		JoinPoint<IOException> jp = new JoinPoint<>();
		jp.addToJoin(sendClient("Hello".getBytes()));
		jp.addToJoin(sendClient("Test 2".getBytes()));
		jp.addToJoin(sendClient("abcdefghijklmnopqrstuvwxyz".getBytes()));
		jp.start();
		jp.blockThrow(0);
	}

	@Test
	public void testSendManyMessages() throws Exception {
		try {
			LCCore.getApplication().getLoggerFactory().getLogger("network-data").setLevel(Level.INFO);
			LCCore.getApplication().getLoggerFactory().getLogger("network").setLevel(Level.INFO);
			byte[] buf = new byte[32768];
			for (int i = 0; i < buf.length; ++i)
				buf[i] = (byte)i;
			UDPClient client = new UDPClient(serverAddress);
			client.send(ByteBuffer.wrap(new byte[0]), null);
			client.send(ByteBuffer.wrap(new byte[0]), new Async<>());
			Async<IOException> last = new Async<>();
			for (int i = 0; i < 1000; ++i)
				client.send(ByteBuffer.wrap(buf), i == 999 ? last : null);
			last.blockThrow(15000);
			client.close();
		} finally {
			LCCore.getApplication().getLoggerFactory().getLogger("network-data").setLevel(Level.TRACE);
			LCCore.getApplication().getLoggerFactory().getLogger("network").setLevel(Level.TRACE);
		}
	}

	@Test
	public void testSendManyMessagesAndClose() throws Exception {
		try {
			LCCore.getApplication().getLoggerFactory().getLogger("network-data").setLevel(Level.INFO);
			LCCore.getApplication().getLoggerFactory().getLogger("network").setLevel(Level.INFO);
			byte[] buf = new byte[32768];
			for (int i = 0; i < buf.length; ++i)
				buf[i] = (byte)i;
			UDPClient client = new UDPClient(serverAddress);
			Async<IOException> last = new Async<>();
			for (int i = 0; i < 1000; ++i)
				client.send(ByteBuffer.wrap(buf), i == 999 ? last : null);
			client.close();
			try {
				last.blockThrow(15000);
			} catch (Exception e) {
				Assert.assertTrue((e instanceof CancelException) || (e instanceof ClosedChannelException));
			}
		} finally {
			LCCore.getApplication().getLoggerFactory().getLogger("network-data").setLevel(Level.TRACE);
			LCCore.getApplication().getLoggerFactory().getLogger("network").setLevel(Level.TRACE);
		}
	}
	
	@Test
	public void testPauseAndCloseServer() throws Exception {
		pauseServer = true;
		Async<IOException> sp = sendClient(new byte[] { 't', 'e', 's', 't' });
		Assert.assertFalse(sp.isDone());
		server.close();
		try {
			sp.blockThrow(0);
			throw new AssertionError("Exception expected when waiting for data and server was closed");
		} catch (IOException e) {
			// ok
		}
	}
	
	@Test
	public void testClientInvalidAddress() throws Exception {
		// send data
		UDPClient client = new UDPClient(new InetSocketAddress("0.0.0.1", 80));
		Async<IOException> sp = new Async<>();
		client.send(ByteBuffer.wrap(new byte[] { 't', 'e', 's', 't' }), sp);
		sp.block(10000);
		Assert.assertTrue(sp.hasError());
		client.close();
		
		// receive data
		client = new UDPClient(new InetSocketAddress("0.0.0.1", 80));
		ByteBuffer buf = ByteBuffer.allocate(512);
		Async<IOException> sp2 = new Async<>();
		client.waitForAnswer(buf, new UDPClient.AnswerListener() {
			@Override
			public void timeout() {
				sp2.error(new IOException("Timeout"));
			}
			@Override
			public void error(IOException error) {
				sp2.error(error);
			}
			@Override
			public ByteBuffer dataReceived(ByteBuffer reply) {
				sp2.unblock();
				return null;
			}
		}, 5000);
		sp2.block(10000);
		Assert.assertTrue(sp2.hasError());
		client.close();
	}
	
	private void send(byte[] message) throws Exception {
		DatagramChannel channel = DatagramChannel.open();
		channel.send(ByteBuffer.wrap(message), serverAddress);
		byte[] b = new byte[message.length + 100];
		ByteBuffer reply = ByteBuffer.wrap(b);
		channel.receive(reply);
		reply.flip();
		int nb = reply.remaining();
		Assert.assertEquals("Message: " + new String(message), message.length + 1, nb);
		Assert.assertEquals(51, b[0]);
		if (!ArrayUtil.equals(b, 1, message, 0, message.length))
			throw new AssertionError("Invalid reply to message " + new String(message) + ": " + new String(b, 1, nb - 1));
		channel.close();
	}

	private Async<IOException> sendClient(byte[] message) {
		UDPClient client = new UDPClient(serverAddress);
		client.send(ByteBuffer.wrap(message), null);
		byte[] b = new byte[message.length + 100];
		Async<IOException> sp = new Async<>();
		client.waitForAnswer(ByteBuffer.wrap(b), new UDPClient.AnswerListener() {
			@Override
			public void timeout() {
				sp.error(new IOException("Timeout"));
			}
			@Override
			public void error(IOException error) {
				sp.error(error);
			}
			@Override
			public ByteBuffer dataReceived(ByteBuffer reply) {
				int nb = reply.remaining();
				if (nb != message.length + 1) {
					sp.error(new IOException(nb + " byte(s) received for message " + new String(message) + ": " + new String(b, 0, nb)));
					return null;
				}
				if (b[0] != 51) {
					sp.error(new IOException("First byte is " + b[0] + " instead of 51 for message " + new String(message)));
					return null;
				}
				if (!ArrayUtil.equals(b, 1, message, 0, message.length)) {
					sp.error(new IOException("Invalid reply to message " + new String(message) + ": " + new String(b, 1, nb - 1)));
					return null;
				}
				client.close();
				sp.unblock();
				return null;
			}
		}, 5000);
		return sp;
	}
	
}
