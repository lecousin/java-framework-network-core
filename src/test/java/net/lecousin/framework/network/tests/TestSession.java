package net.lecousin.framework.network.tests;

import org.junit.Assert;
import org.junit.Test;

import java.net.InetSocketAddress;

import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.session.NetworkSessionProvider;
import net.lecousin.framework.network.session.Session;
import net.lecousin.framework.network.session.SessionInMemory;

public class TestSession extends LCCoreAbstractTest {

	@Test
	public void testSessionInMemory() {
		SessionInMemory sm = new SessionInMemory();
		Assert.assertNull(sm.load("123"));
		String id = sm.allocateId();
		Assert.assertNull(sm.load(id));
		Session s = new Session(id);
		Assert.assertEquals(id,  s.getId());
		s.putData("hello", "world");
		sm.save(id, s);
		s = sm.load(id);
		Assert.assertNotNull(s);
		Assert.assertEquals(id,  s.getId());
		Assert.assertEquals("world", s.getData("hello"));
		sm.freeId(id);
		Assert.assertNull(sm.load(id));
		sm.close();
	}
	
	@Test
	public void testNetworkSessionProvider() throws Exception {
		SessionInMemory sm = new SessionInMemory();
		NetworkSessionProvider sp = new NetworkSessionProvider(sm, 5000, "test");
		Assert.assertEquals(5000, sp.getExpiration());
		TCPClient client = new TCPClient();
		client.connect(new InetSocketAddress("www.google.com", 80), 10000).blockThrow(0);
		Session s = sp.create(client);
		String id = s.getId();
		Assert.assertNull(s.getData("hello"));
		s.putData("hello", "world");
		sp.save(s, client);
		s = sp.get(id, client);
		Assert.assertNotNull(s);
		Assert.assertEquals("world", s.getData("hello"));
		sp.destroy(id);
		s = sp.get(id, client);
		Assert.assertNull(s);
		sp.close();
		client.close();
		sm.close();
	}
	
}
