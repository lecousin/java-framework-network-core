package net.lecousin.framework.network.tests;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;

import net.lecousin.framework.collections.ArrayUtil;
import net.lecousin.framework.concurrent.synch.JoinPoint;
import net.lecousin.framework.concurrent.synch.SynchronizationPoint;
import net.lecousin.framework.network.client.UDPClient;
import net.lecousin.framework.network.server.UDPServer;
import net.lecousin.framework.network.server.UDPServer.MessageSender;
import net.lecousin.framework.network.test.AbstractNetworkTest;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestUDP extends AbstractNetworkTest {

	private static UDPServer server;
	
	@BeforeClass
	public static void launchUDPServer() throws Exception {
		server = new UDPServer(1024, new UDPServer.MessageListener() {
			@Override
			public void newMessage(ByteBuffer message, SocketAddress source, MessageSender reply) {
				ByteBuffer r = ByteBuffer.allocate(message.remaining() + 1);
				r.put((byte)51);
				r.put(message);
				r.flip();
				reply.reply(r);
			}
		});
		server.bind(new InetSocketAddress("localhost", 9999));
	}
	
	@AfterClass
	public static void stopUDPServer() {
		server.close();
	}
	
	@Test(timeout=30000)
	public void testSendMessagesToServer() throws Exception {
		send("Hello".getBytes());
		send("Test 2".getBytes());
		send("abcdefghijklmnopqrstuvwxyz".getBytes());
	}
	
	@Test(timeout=30000)
	public void testUDPClient() throws Exception {
		JoinPoint<IOException> jp = new JoinPoint<>();
		jp.addToJoin(sendClient("Hello".getBytes()));
		jp.addToJoin(sendClient("Test 2".getBytes()));
		jp.addToJoin(sendClient("abcdefghijklmnopqrstuvwxyz".getBytes()));
		jp.start();
		jp.blockThrow(0);
	}
	
	@SuppressWarnings("resource")
	private static void send(byte[] message) throws Exception {
		DatagramChannel channel = DatagramChannel.open();
		channel.send(ByteBuffer.wrap(message), new InetSocketAddress("localhost", 9999));
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

	@SuppressWarnings("resource")
	private static SynchronizationPoint<IOException> sendClient(byte[] message) {
		UDPClient client = new UDPClient(new InetSocketAddress("localhost", 9999));
		client.send(ByteBuffer.wrap(message), null);
		byte[] b = new byte[message.length + 100];
		SynchronizationPoint<IOException> sp = new SynchronizationPoint<>();
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
