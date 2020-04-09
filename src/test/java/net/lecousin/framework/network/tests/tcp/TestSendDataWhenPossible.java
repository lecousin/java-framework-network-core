package net.lecousin.framework.network.tests.tcp;

import java.io.IOException;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Supplier;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.network.SocketOptionValue;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.server.TCPServerClient;
import net.lecousin.framework.network.server.protocol.ServerProtocol;

import org.junit.Assert;
import org.junit.Test;

public class TestSendDataWhenPossible extends AbstractTestTCP {

	public TestSendDataWhenPossible(boolean useSSL, boolean useIPv6) {
		super(useSSL, useIPv6);
	}
	
	private static class EchoProtocol implements ServerProtocol {

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
			@SuppressWarnings("unchecked")
			Async<IOException> previous = (Async<IOException>)client.getAttribute("last send");
			if (previous == null) previous = new Async<>(true);
			Async<IOException> send = new Async<>();
			client.setAttribute("last send", send);
			previous.thenStart(Task.cpu("Send data to client", Task.Priority.NORMAL, t -> {
				client.newDataToSendWhenPossible(() -> {
					LinkedList<ByteBuffer> list = new LinkedList<>();
					list.add(data);
					return list;
				}, send, 10000);
				return null;
			}), send);
			try { client.waitForData(10000); }
			catch (ClosedChannelException e) {
				e.printStackTrace(System.err);
			}
			send.onError(e -> e.printStackTrace());
		}
		
	}

	@Override
	protected ServerProtocol createProtocol() {
		return new EchoProtocol();
	}
	
	private static class DataProvider implements Supplier<List<ByteBuffer>> {
		public DataProvider(int index) {
			data = ByteBuffer.allocate(16384);
			data.array()[0] = (byte)index;
		}
		
		private ByteBuffer data;
		
		@Override
		public List<ByteBuffer> get() {
			LinkedList<ByteBuffer> list = new LinkedList<>();
			list.add(data);
			return list;
		}
	}
	
	@Test
	public void testEcho() throws Exception {
		deactivateNetworkTraces();
		DataProvider[] providers = new DataProvider[1000];
		for (int i = 0; i < providers.length; ++i)
			providers[i] = new DataProvider(i);

		TCPClient client = connectClient(new SocketOptionValue<>(StandardSocketOptions.SO_SNDBUF, Integer.valueOf(4096)));
		
		Async<IOException> lastSend = new Async<>(true);
		for (int i = 0; i < providers.length; ++i)
			lastSend = send(client, providers[i], lastSend);
		
		for (int i = 0; i < providers.length; ++i) {
			byte[] read = client.getReceiver().readBytes(16384, 10000).blockResult(0);
			Assert.assertEquals((byte)i, read[0]);
		}

		client.close();
	}
	
	private static Async<IOException> send(TCPClient client, DataProvider provider, Async<IOException> previous) {
		Async<IOException> send = new Async<>();
		previous.thenStart(Task.cpu("Send data to server", Task.Priority.NORMAL, t -> {
			client.newDataToSendWhenPossible(provider, send, 10000);
			return null;
		}), send);
		return send;
	}

}
