package net.lecousin.framework.network.tests.tcp;

import java.io.IOException;
import java.nio.ByteBuffer;

import net.lecousin.framework.network.client.SSLClient;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.server.protocol.ServerProtocol;

import org.junit.Test;

public class TestClientErrors extends AbstractTestTCP {

	public TestClientErrors(boolean useSSL, boolean useIPv6) {
		super(useSSL, useIPv6);
	}

	@Override
	protected ServerProtocol createProtocol() {
		return new WelcomeProtocol();
	}
	
	@Test
	public void testSendWithoutConnection() throws Exception {
		try (TCPClient client = useSSL ? new SSLClient() : new TCPClient()) {
			client.send(ByteBuffer.wrap(new byte[] { 50 }), 5000).blockThrow(10000);
			throw new AssertionError("Sending data using a not connected client must throw an IOException");
		} catch (IOException e) {
			// ok
		}
	}
	
	@Test
	public void testReceiveWithoutConnection() throws Exception {
		try (TCPClient client = useSSL ? new SSLClient() : new TCPClient()) {
			client.receiveData(1024, 8000).blockThrow(10000);
			throw new AssertionError("Receiving data using a not connected client must throw an IOException");
		} catch (IOException e) {
			// ok
		}
	}


}
