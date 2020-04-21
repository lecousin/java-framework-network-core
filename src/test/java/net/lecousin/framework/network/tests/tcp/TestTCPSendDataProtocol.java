package net.lecousin.framework.network.tests.tcp;

import java.io.IOException;
import java.nio.ByteBuffer;

import net.lecousin.framework.concurrent.Executable;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.concurrent.threads.Task.Priority;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.mutable.Mutable;
import net.lecousin.framework.mutable.MutableInteger;
import net.lecousin.framework.network.TCPRemote;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.server.TCPServerClient;
import net.lecousin.framework.network.server.protocol.ALPNServerProtocol;
import net.lecousin.framework.network.server.protocol.ServerProtocol;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestTCPSendDataProtocol extends AbstractTestTCP {

	public TestTCPSendDataProtocol(boolean useSSL, boolean useIPv6) {
		super(useSSL, useIPv6);
	}
	
	private static final int NB_BLOCKS = 250; 
	private static final int BLOCK_SIZE = 12345; 

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
		MutableInteger i = new MutableInteger(0);
		Mutable<Executable<Void, NoException>> exec = new Mutable<>(null);
		exec.set(t -> {
			remote.send(ByteBuffer.wrap(data[i.get()]), 5000);
			if (i.get() == NB_BLOCKS - 1)
				return null;
			long wait = 0;
			if ((i.get() % 20) == 0)
				wait = 400;
			else if ((i.get() % 5) == 0)
				wait = 100;
			i.inc();
			Task.cpu("Send data in loop", Priority.NORMAL, exec.get()).executeIn(wait).start();
			return null;
		});
		try {
			exec.get().execute(null);
		} catch (Exception e) {
		}
	}
	
	public static class SendDataProtocol implements ALPNServerProtocol {

		@Override
		public String getALPNName() {
			return "send";
		}
		
		@Override
		public int startProtocol(TCPServerClient client) {
			sendDataLoop(client);
			return -1;
		}

		@Override
		public int getInputBufferSize(TCPServerClient client) {
			return 1024;
		}

		@Override
		public void dataReceivedFromClient(TCPServerClient client, ByteBuffer data) {
			client.close();
		}
		
	}

	@Override
	protected ServerProtocol createProtocol() {
		return new SendDataProtocol();
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
	public void testSendDataServerToClient() throws Exception {
		TCPClient client = connectClient();
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
	}
	
	@Test
	public void testReceiveAndClose() throws Exception {
		TCPClient client = connectClient();
		// wait few seconds so the server is filling the socket buffer
		try { Thread.sleep(2000); }
		catch (InterruptedException e) {}
		client.getReceiver().readBytes(BLOCK_SIZE, 10000);
		client.close();
	}
}
