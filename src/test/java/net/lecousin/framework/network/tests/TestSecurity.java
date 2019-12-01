package net.lecousin.framework.network.tests;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import net.lecousin.framework.application.Application;
import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.JoinPoint;
import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.serialization.SerializationException;
import net.lecousin.framework.io.serialization.TypeDefinition;
import net.lecousin.framework.network.NetUtil;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.security.BruteForceAttempt;
import net.lecousin.framework.network.security.IPBlackList;
import net.lecousin.framework.network.security.NetworkSecurity;
import net.lecousin.framework.network.security.NetworkSecurityExtensionPoint;
import net.lecousin.framework.network.security.NetworkSecurityFeature;
import net.lecousin.framework.network.security.NetworkSecurityPlugin;
import net.lecousin.framework.network.server.TCPServer;
import net.lecousin.framework.network.tests.tcp.WelcomeProtocol;
import net.lecousin.framework.plugins.ExtensionPoints;
import net.lecousin.framework.xml.serialization.XMLDeserializer;

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
		server.setProtocol(new WelcomeProtocol());
		SocketAddress serverAddressIPv4 = server.bind(new InetSocketAddress("localhost", 0), 0).blockResult(0);
		Inet6Address ipv6 = NetUtil.getLoopbackIPv6Address();
		SocketAddress serverAddressIPv6 = ipv6 != null ? server.bind(new InetSocketAddress(ipv6, 0), 0).blockResult(0) : null;
		
		// check we can connect
		TCPClient client = new TCPClient();
		client.connect(serverAddressIPv4, 5000).blockThrow(0);
		ByteBuffer buf = client.receiveData(5, 5000).blockResult(0);
		Assert.assertEquals(5, buf.remaining());
		client.close();
		if (ipv6 != null) {
			client = new TCPClient();
			client.connect(serverAddressIPv6, 5000).blockThrow(0);
			buf = client.receiveData(5, 5000).blockResult(0);
			Assert.assertEquals(5, buf.remaining());
			client.close();
		}
		
		IPBlackList bl = security.getFeature(IPBlackList.class);
		
		// add us to the black list
		bl.blacklist("test", InetAddress.getByName("localhost"), 10000);
		client = new TCPClient();
		try {
			client.connect(serverAddressIPv4, 5000).blockThrow(0);
			buf = client.receiveData(5, 5000).blockResult(0);
			if (buf != null && buf.remaining() > 0)
				throw new AssertionError("Connection succeed while we are blacklisted");
		} catch (Exception t) {
		}
		client.close();
		if (ipv6 != null) {
			bl.blacklist("test", ipv6, 10000);
			client = new TCPClient();
			try {
				client.connect(serverAddressIPv6, 5000).blockThrow(0);
				buf = client.receiveData(5, 5000).blockResult(0);
				if (buf != null && buf.remaining() > 0)
					throw new AssertionError("Connection succeed while we are blacklisted");
			} catch (Exception t) {
			}
			client.close();
		}
		
		// unblack list
		bl.unblacklist("test", InetAddress.getByName("localhost"));
		client = new TCPClient();
		client.connect(serverAddressIPv4, 5000).blockThrow(0);
		buf = client.receiveData(5, 5000).blockResult(0);
		Assert.assertEquals(5, buf.remaining());
		client.close();
		if (ipv6 != null) {
			bl.unblacklist("test", ipv6);
			client = new TCPClient();
			client.connect(serverAddressIPv6, 5000).blockThrow(0);
			buf = client.receiveData(5, 5000).blockResult(0);
			Assert.assertEquals(5, buf.remaining());
			client.close();
		}
		
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
		IPBlackList bl = security.getFeature(IPBlackList.class);
		InetAddress ipv4 = InetAddress.getByAddress(new byte[] { 4, 3, 2, 1 });
		Assert.assertTrue(bl.acceptAddress(ipv4));
		bf.attempt(ipv4, "test", "test");
		Assert.assertTrue(bl.acceptAddress(ipv4));
		bf.attempt(ipv4, "test", "test");
		Assert.assertTrue(bl.acceptAddress(ipv4));
		bf.attempt(ipv4, "test", "test2");
		Assert.assertTrue(bl.acceptAddress(ipv4));
		bf.attempt(ipv4, "test", "test3");
		Assert.assertTrue(bl.acceptAddress(ipv4));
		bf.attempt(ipv4, "test", "test4");
		Assert.assertTrue(bl.acceptAddress(ipv4));
		bf.attempt(ipv4, "test", "test5");
		Assert.assertTrue(bl.acceptAddress(ipv4));
		bf.attempt(ipv4, "test", "test6");
		Assert.assertTrue(bl.acceptAddress(ipv4));
		bf.attempt(ipv4, "test", "test7");
		Assert.assertTrue(bl.acceptAddress(ipv4));
		bf.attempt(ipv4, "test", "test8");
		Assert.assertTrue(bl.acceptAddress(ipv4));
		bf.attempt(ipv4, "test", "test9");
		Assert.assertTrue(bl.acceptAddress(ipv4));
		bf.attempt(ipv4, "test", "test10");
		Assert.assertFalse(bl.acceptAddress(ipv4));
		bf.attempt(ipv4, "test", "test11");
		Assert.assertFalse(bl.acceptAddress(ipv4));

		InetAddress ipv6 = InetAddress.getByAddress(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 });
		Assert.assertTrue(bl.acceptAddress(ipv6));
		bf.attempt(ipv6, "test", "test");
		Assert.assertTrue(bl.acceptAddress(ipv6));
		bf.attempt(ipv6, "test", "test");
		Assert.assertTrue(bl.acceptAddress(ipv6));
		bf.attempt(ipv6, "test", "test2");
		Assert.assertTrue(bl.acceptAddress(ipv6));
		bf.attempt(ipv6, "test", "test3");
		Assert.assertTrue(bl.acceptAddress(ipv6));
		bf.attempt(ipv6, "test", "test4");
		Assert.assertTrue(bl.acceptAddress(ipv6));
		bf.attempt(ipv6, "test", "test5");
		Assert.assertTrue(bl.acceptAddress(ipv6));
		bf.attempt(ipv6, "test", "test6");
		Assert.assertTrue(bl.acceptAddress(ipv6));
		bf.attempt(ipv6, "test", "test7");
		Assert.assertTrue(bl.acceptAddress(ipv6));
		bf.attempt(ipv6, "test", "test8");
		Assert.assertTrue(bl.acceptAddress(ipv6));
		bf.attempt(ipv6, "test", "test9");
		Assert.assertTrue(bl.acceptAddress(ipv6));
		bf.attempt(ipv6, "test", "test10");
		Assert.assertFalse(bl.acceptAddress(ipv6));
		bf.attempt(ipv6, "test", "test11");
		Assert.assertFalse(bl.acceptAddress(ipv6));
	}
	
	@Test
	public void testLoadConfiguration() throws Exception {
		Application app = LCCore.getApplication();
		app.setProperty(BruteForceAttempt.PROPERTY_DELAY_KEEP_ATTEMPT, "100");
		app.setProperty(BruteForceAttempt.PROPERTY_MAX_ATTEMPTS, "1");
		app.setProperty(BruteForceAttempt.PROPERTY_BLACK_LIST_DELAY, "100");
		Map<Class<?>, Object> instances = new HashMap<>();
		JoinPoint<Exception> jp = new JoinPoint<>();
		for (NetworkSecurityPlugin plugin : ExtensionPoints.getExtensionPoint(NetworkSecurityExtensionPoint.class).getPlugins()) {
			IO.Readable input = app.getResource("tests-network-core/security/" + plugin.getClass().getName() + ".xml", Task.PRIORITY_NORMAL);
			AsyncSupplier<Object, SerializationException> res =
				new XMLDeserializer(null, plugin.getClass().getSimpleName()).deserialize(
					new TypeDefinition(plugin.getConfigurationClass()), input, new ArrayList<>(0));
			jp.addToJoin(1);
			res.onDone(cfg -> {
				NetworkSecurityFeature instance = plugin.newInstance(app, cfg);
				instance.clean();
				instances.put(instance.getClass(), instance);
				jp.joined();
			}, err -> jp.error(err), cancel -> jp.cancel(cancel));
			input.closeAfter(res);
		}
		jp.start();
		jp.blockThrow(0);
		
		BruteForceAttempt bf = (BruteForceAttempt)instances.get(BruteForceAttempt.class);
		IPBlackList bl = security.getFeature(IPBlackList.class);
		InetAddress ipv4 = InetAddress.getByAddress(new byte[] { 1, 1, 1, 1 });
		Assert.assertTrue(bl.acceptAddress(ipv4));
		bf.attempt(ipv4, "test", "test");
		Assert.assertFalse(bl.acceptAddress(ipv4));
		Thread.sleep(200);
		Assert.assertTrue(bl.acceptAddress(ipv4));
		bf.attempt(ipv4, "test", "test");
		Assert.assertFalse(bl.acceptAddress(ipv4));
	}
	
}
