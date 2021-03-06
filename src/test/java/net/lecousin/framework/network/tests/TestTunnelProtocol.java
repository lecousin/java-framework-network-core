package net.lecousin.framework.network.tests;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.log.Logger.Level;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.server.TCPServer;
import net.lecousin.framework.network.server.TCPServerClient;
import net.lecousin.framework.network.server.protocol.ServerProtocol;
import net.lecousin.framework.network.server.protocol.TunnelProtocol;
import net.lecousin.framework.network.test.AbstractNetworkTest;

import org.junit.Assert;
import org.junit.Test;

public class TestTunnelProtocol extends AbstractNetworkTest {

	private static class TestTunnel implements ServerProtocol {
		private TestTunnel() {
			logger = LCCore.getApplication().getLoggerFactory().getLogger(TestTunnelProtocol.class);
			logger.setLevel(Level.TRACE);
			tunnelProtocol = new TunnelProtocol(4096, 1000, 2000, logger);
			Assert.assertEquals(1000, tunnelProtocol.getClientSendTimeout());
			tunnelProtocol.setClientSendTimeout(5000);
			Assert.assertEquals(5000, tunnelProtocol.getClientSendTimeout());
			Assert.assertEquals(2000, tunnelProtocol.getRemoteSendTimeout());
			tunnelProtocol.setRemoteSendTimeout(5000);
			Assert.assertEquals(5000, tunnelProtocol.getRemoteSendTimeout());
		}
		
		private Logger logger;
		private TunnelProtocol tunnelProtocol;

		@Override
		public int startProtocol(TCPServerClient client) {
			TCPClient tunnel = new TCPClient();
			try {
				tunnel.connect(new InetSocketAddress("www.google.com", 80), 10000).blockThrow(0);
			} catch (Throwable t) {
				logger.error("Unable to connect to google", t);
				client.close();
				tunnel.close();
				return -1;
			}
			tunnelProtocol.registerClient(client, tunnel);
			int recvTimeout = tunnelProtocol.startProtocol(client);
			tunnelProtocol.getInputBufferSize(client);
			client.send(ByteBuffer.wrap(new byte[] { 1 }), 5000);
			return recvTimeout;
		}

		@Override
		public int getInputBufferSize(TCPServerClient client) {
			return 4096;
		}

		@Override
		public void dataReceivedFromClient(TCPServerClient client, ByteBuffer data) {
			tunnelProtocol.dataReceivedFromClient(client, data);
		}

		@Override
		public LinkedList<ByteBuffer> prepareDataToSend(TCPServerClient client, List<ByteBuffer> data) {
			return tunnelProtocol.prepareDataToSend(client, data);
		};
	}
	
	@Test
	public void test() throws Exception {
		deactivateNetworkTraces();
		try (TCPServer server = new TCPServer()) {
			server.setProtocol(new TestTunnel());
			server.bind(new InetSocketAddress("localhost", 12345), 10).blockThrow(0);
			
			TCPClient client = new TCPClient();
			client.connect(new InetSocketAddress("localhost", 12345), 10000).blockThrow(0);
			byte[] buf = client.getReceiver().readBytes(1, 0).blockResult(0);
			Assert.assertEquals(1, buf[0]);
			client.send(ByteBuffer.wrap("GET / HTTP/1.1\r\nHost: www.google.com\r\n\r\n".getBytes(StandardCharsets.US_ASCII)), 10000).blockThrow(0);
			ByteArrayIO io = client.getReceiver().readUntil((byte)'\n', 2048, 20000).blockResult(0);
			Assert.assertEquals("HTTP/1.1 200 ", io.getAsString(StandardCharsets.US_ASCII).substring(0, 13));
			io.close();
			client.close();
		} finally {
			activateNetworkTraces();
		}
	}
	
}
