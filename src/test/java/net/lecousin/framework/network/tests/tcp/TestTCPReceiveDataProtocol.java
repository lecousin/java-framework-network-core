package net.lecousin.framework.network.tests.tcp;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;

import net.lecousin.framework.memory.ByteArrayCache;
import net.lecousin.framework.network.TCPRemote;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.server.TCPServerClient;
import net.lecousin.framework.network.server.protocol.ServerProtocol;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestTCPReceiveDataProtocol extends AbstractTestTCP {

	public TestTCPReceiveDataProtocol(boolean useSSL, boolean useIPv6) {
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
		for (int i = 0; i < NB_BLOCKS; ++i) {
			remote.send(ByteBuffer.wrap(data[i]), 5000);
			if ((i % 20) == 0)
				try { Thread.sleep(400); }
				catch (InterruptedException e) {}
			else if ((i % 5) == 0)
				try { Thread.sleep(100); }
				catch (InterruptedException e) {}
		}
	}
	
	private static class ReceiveDataProtocol implements ServerProtocol {

		@Override
		public int startProtocol(TCPServerClient client) {
			client.setAttribute("block_counter", Integer.valueOf(0));
			client.setAttribute("byte_counter", Integer.valueOf(0));
			return 10000;
		}

		@Override
		public int getInputBufferSize() {
			return 1024;
		}

		@Override
		public void dataReceivedFromClient(TCPServerClient client, ByteBuffer data) {
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
			ByteArrayCache.getInstance().free(data);
			if (block == NB_BLOCKS) {
				client.send(ByteBuffer.wrap(new byte[] { 'O', 'K', '\n' }), 5000);
				return;
			}
			try { client.waitForData(10000); }
			catch (ClosedChannelException e) {}
		}
		
	}
	
	@Override
	protected ServerProtocol createProtocol() {
		return new ReceiveDataProtocol();
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
	public void testSendDataClientToServer() throws Exception {
		TCPClient client = connectClient();
		sendDataLoop(client);
		expectLine(client, "OK");
		client.close();
	}
	
	@Test
	public void testSendThenClose() throws Exception {
		TCPClient client = connectClient();
		byte[][] data = generateDataToSend();
		for (int i = 0; i < NB_BLOCKS / 2; ++i)
			client.send(ByteBuffer.wrap(data[i]), 5000);
		client.close();
	}

}
