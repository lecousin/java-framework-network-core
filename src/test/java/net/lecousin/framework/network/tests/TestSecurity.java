package net.lecousin.framework.network.tests;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import org.junit.Assert;
import org.junit.Test;

import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.security.NetworkSecurity;
import net.lecousin.framework.network.server.TCPServer;
import net.lecousin.framework.network.tests.TestTCP.TestProtocol;

public class TestSecurity extends LCCoreAbstractTest {

	@SuppressWarnings("resource")
	@Test
	public void testBlockIP() throws Exception {
		TCPServer server = new TCPServer();
		server.setProtocol(new TestProtocol());
		server.bind(new InetSocketAddress("localhost", 9999), 0);
		
		// check we can connect
		TCPClient client = new TCPClient();
		client.connect(new InetSocketAddress("localhost", 9999), 5000).blockThrow(0);
		ByteBuffer buf = client.receiveData(5, 5000).blockResult(0);
		Assert.assertEquals(5, buf.remaining());
		client.close();
		
		// add us to the black list
		NetworkSecurity.blacklist("test", InetAddress.getByName("localhost"), 10000);
		client = new TCPClient();
		try {
			client.connect(new InetSocketAddress("localhost", 9999), 5000).blockThrow(0);
			buf = client.receiveData(5, 5000).blockResult(0);
			if (buf != null && buf.remaining() > 0)
				throw new AssertionError("Connection succeed while we are blacklisted");
		} catch (Exception t) {
		}
		client.close();
		
		// unblack list
		NetworkSecurity.unblacklist("test", InetAddress.getByName("localhost"));
		client = new TCPClient();
		client.connect(new InetSocketAddress("localhost", 9999), 5000).blockThrow(0);
		buf = client.receiveData(5, 5000).blockResult(0);
		Assert.assertEquals(5, buf.remaining());
		client.close();
		
		server.close();
	}
	
}
