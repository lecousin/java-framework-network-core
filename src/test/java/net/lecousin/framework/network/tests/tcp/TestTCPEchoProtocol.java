package net.lecousin.framework.network.tests.tcp;

import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.log.Logger.Level;
import net.lecousin.framework.network.SocketOptionValue;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.server.TCPServerClient;
import net.lecousin.framework.network.server.protocol.ServerProtocol;

import org.junit.Assert;
import org.junit.Test;

public class TestTCPEchoProtocol extends AbstractTestTCP {

	public TestTCPEchoProtocol(boolean useSSL, boolean useIPv6) {
		super(useSSL, useIPv6);
	}
	
	private static class EchoProtocol implements ServerProtocol {

		@Override
		public int startProtocol(TCPServerClient client) {
			return 10000;
		}

		@Override
		public int getInputBufferSize() {
			return 1024;
		}

		@Override
		public void dataReceivedFromClient(TCPServerClient client, ByteBuffer data) {
			System.out.println("Received from echo client: " + data.remaining());
			client.send(data, 5000).onDone(() -> {
				try { client.waitForData(10000); }
				catch (ClosedChannelException e) {
					e.printStackTrace(System.err);
				}
			});
		}
		
	}

	@Override
	protected ServerProtocol createProtocol() {
		return new EchoProtocol();
	}
	
	@Test
	public void testEcho() throws Exception {
		LCCore.getApplication().getLoggerFactory().getLogger("network").setLevel(Level.DEBUG);
		LCCore.getApplication().getLoggerFactory().getLogger("network-data").setLevel(Level.DEBUG);
		LCCore.getApplication().getLoggerFactory().getLogger(TCPClient.class).setLevel(Level.DEBUG);
		TCPClient client = connectClient(new SocketOptionValue<>(StandardSocketOptions.SO_SNDBUF, Integer.valueOf(512)));
		byte[] data = new byte[100000];
		for (int i = 0; i < data.length; ++i)
			data[i] = (byte)i;
		for (int i = 0; i < 10; ++i)
			client.send(ByteBuffer.wrap(data).asReadOnlyBuffer(), 5000);
		for (int i = 0; i < 10; ++i)
			Assert.assertArrayEquals("Array " + i, data, client.getReceiver().readBytes(data.length, 10000).blockResult(0));
		client.close();
		LCCore.getApplication().getLoggerFactory().getLogger(TCPClient.class).setLevel(Level.TRACE);
		LCCore.getApplication().getLoggerFactory().getLogger("network-data").setLevel(Level.TRACE);
		LCCore.getApplication().getLoggerFactory().getLogger("network").setLevel(Level.TRACE);
	}

}
