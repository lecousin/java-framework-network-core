package net.lecousin.framework.network.tests.tcp;

import java.io.IOException;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Supplier;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.log.Logger.Level;
import net.lecousin.framework.network.SocketOptionValue;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.server.TCPServerClient;
import net.lecousin.framework.network.server.protocol.ServerProtocol;

import org.junit.Assert;
import org.junit.Test;

public class TestProtocolWithDataProvider extends AbstractTestTCP {

	public TestProtocolWithDataProvider(boolean useSSL, boolean useIPv6) {
		super(useSSL, useIPv6);
	}

	private static class MyProtocol implements ServerProtocol {

		@Override
		public int startProtocol(TCPServerClient client) {
			return 10000;
		}

		@Override
		public int getInputBufferSize(TCPServerClient client) {
			return 1024;
		}

		@Override
		public void dataReceivedFromClient(TCPServerClient client, ByteBuffer data) {
			Async<IOException> sp = new Async<>();
			Supplier<List<ByteBuffer>> dataProvider = () -> Collections.singletonList(data);
			client.newDataToSendWhenPossible(dataProvider, sp, 5000);
			sp.onDone(() -> {
				try { client.waitForData(10000); }
				catch (ClosedChannelException e) {
					e.printStackTrace(System.err);
				}
			});
		}

		@Override
		public LinkedList<ByteBuffer> prepareDataToSend(TCPServerClient client, List<ByteBuffer> data) {
			if (data instanceof LinkedList)
				return (LinkedList<ByteBuffer>)data;
			return new LinkedList<>(data);
		}
		
	}
	
	@Override
	protected ServerProtocol createProtocol() {
		return new MyProtocol();
	}
	
	@Test
	public void testEcho() throws Exception {
		LCCore.getApplication().getLoggerFactory().getLogger("network").setLevel(Level.DEBUG);
		LCCore.getApplication().getLoggerFactory().getLogger("network-data").setLevel(Level.DEBUG);
		LCCore.getApplication().getLoggerFactory().getLogger(TCPClient.class).setLevel(Level.DEBUG);
		TCPClient client = connectClient(new SocketOptionValue<>(StandardSocketOptions.SO_SNDBUF, Integer.valueOf(512)));
		byte[] data = new byte[1000];
		for (int i = 0; i < data.length; ++i)
			data[i] = (byte)i;
		for (int i = 0; i < 10; ++i)
			client.send(ByteBuffer.wrap(data).asReadOnlyBuffer(), 5000);
		for (int i = 0; i < 10; ++i)
			Assert.assertArrayEquals(data, client.getReceiver().readBytes(data.length, 10000).blockResult(0));
		client.close();
		LCCore.getApplication().getLoggerFactory().getLogger(TCPClient.class).setLevel(Level.TRACE);
		LCCore.getApplication().getLoggerFactory().getLogger("network-data").setLevel(Level.TRACE);
		LCCore.getApplication().getLoggerFactory().getLogger("network").setLevel(Level.TRACE);
	}

}
