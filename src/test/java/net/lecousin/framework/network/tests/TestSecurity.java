package net.lecousin.framework.network.tests;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.security.BruteForceAttempt;
import net.lecousin.framework.network.security.IPBlackList;
import net.lecousin.framework.network.security.NetworkSecurity;
import net.lecousin.framework.network.server.TCPServer;
import net.lecousin.framework.network.tests.TestTCP.TestProtocol;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class TestSecurity extends LCCoreAbstractTest {

	@Before
	public void initSecurity() {
		security = NetworkSecurity.get(LCCore.getApplication());
		security.isLoaded().block(0);
	}
	
	private NetworkSecurity security;
	
	@SuppressWarnings("resource")
	@Test
	public void testBlockIP() throws Exception {
		TCPServer server = new TCPServer();
		server.setProtocol(new TestProtocol());
		SocketAddress serverAddress = server.bind(new InetSocketAddress("localhost", 0), 0).blockResult(0);
		
		// check we can connect
		TCPClient client = new TCPClient();
		client.connect(serverAddress, 5000).blockThrow(0);
		ByteBuffer buf = client.receiveData(5, 5000).blockResult(0);
		Assert.assertEquals(5, buf.remaining());
		client.close();
		
		IPBlackList bl = security.getFeature(IPBlackList.class);
		
		// add us to the black list
		bl.blacklist("test", InetAddress.getByName("localhost"), 10000);
		client = new TCPClient();
		try {
			client.connect(serverAddress, 5000).blockThrow(0);
			buf = client.receiveData(5, 5000).blockResult(0);
			if (buf != null && buf.remaining() > 0)
				throw new AssertionError("Connection succeed while we are blacklisted");
		} catch (Exception t) {
		}
		client.close();
		
		// unblack list
		bl.unblacklist("test", InetAddress.getByName("localhost"));
		client = new TCPClient();
		client.connect(serverAddress, 5000).blockThrow(0);
		buf = client.receiveData(5, 5000).blockResult(0);
		Assert.assertEquals(5, buf.remaining());
		client.close();
		
		// black list with IPv6
		bl.blacklist("test", InetAddress.getByAddress(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 }), 5000);
		bl.unblacklist("test", InetAddress.getByAddress(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 }));

		// keep black listed ips to save
		bl.blacklist("test2", InetAddress.getByAddress(new byte[] { 1, 2, 3, 4 }), 600000);
		bl.blacklist("test2", InetAddress.getByAddress(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 }), 600000);
		
		server.close();
	}
	
	@Test
	public void testBruteForceAttack() throws Exception {
		BruteForceAttempt bf = security.getFeature(BruteForceAttempt.class);
		bf.attempt(InetAddress.getByAddress(new byte[] { 1, 2, 3, 4 }), "test", "test");
		bf.attempt(InetAddress.getByAddress(new byte[] { 1, 2, 3, 4 }), "test", "test");
		bf.attempt(InetAddress.getByAddress(new byte[] { 1, 2, 3, 4 }), "test", "test");
		bf.attempt(InetAddress.getByAddress(new byte[] { 1, 2, 3, 4 }), "test", "test");
		bf.attempt(InetAddress.getByAddress(new byte[] { 1, 2, 3, 4 }), "test", "test");

		bf.attempt(InetAddress.getByAddress(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 }), "test", "test");
		bf.attempt(InetAddress.getByAddress(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 }), "test", "test");
		bf.attempt(InetAddress.getByAddress(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 }), "test", "test");
		bf.attempt(InetAddress.getByAddress(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 }), "test", "test");
		bf.attempt(InetAddress.getByAddress(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 }), "test", "test");
	}
	
}
