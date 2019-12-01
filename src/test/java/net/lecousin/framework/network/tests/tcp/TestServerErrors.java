package net.lecousin.framework.network.tests.tcp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

import net.lecousin.framework.network.server.TCPServer;
import net.lecousin.framework.network.test.AbstractNetworkTest;

import org.junit.Test;

public class TestServerErrors extends AbstractNetworkTest {

	@Test
	public void testConnect2ServersOnSamePort() throws Exception {
		try (TCPServer s1 = new TCPServer()) {
			try (TCPServer s2 = new TCPServer()) {
				s1.setProtocol(new WelcomeProtocol());
				s2.setProtocol(new WelcomeProtocol());
				
				SocketAddress s1Address = s1.bind(new InetSocketAddress("localhost", 0), 10).blockResult(0);
				try {
					s2.bind(s1Address, 10).blockThrow(0);
					throw new AssertionError("Binding a second server to the same address must throw an IOException");
				} catch (IOException e) {
					// ok
				}
			}
		}
	}
	
}
