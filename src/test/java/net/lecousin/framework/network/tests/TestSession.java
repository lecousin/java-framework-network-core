package net.lecousin.framework.network.tests;

import java.net.InetSocketAddress;

import org.junit.Assert;
import org.junit.Test;

import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.session.NetworkSessionProvider;
import net.lecousin.framework.network.session.Session;
import net.lecousin.framework.network.session.SessionInMemory;

public class TestSession extends LCCoreAbstractTest {

	@Test
	public void testSessionInMemory() throws Exception {
		SessionInMemory sm = new SessionInMemory(0);
		Assert.assertFalse(sm.load("123", new Session("")).blockResult(0).booleanValue());
		String id = sm.allocateId();
		Assert.assertFalse(sm.load(id, new Session("")).blockResult(0).booleanValue());
		Session s = new Session(id);
		Assert.assertEquals(id,  s.getId());
		s.putData("hello", "world");
		sm.save(id, s).blockThrow(0);
		s = new Session(id);
		Assert.assertTrue(sm.load(id, s).blockResult(0).booleanValue());
		Assert.assertEquals("world", s.getData("hello"));
		sm.release(id);
		sm.remove(id);
		Assert.assertFalse(sm.load(id, new Session("")).blockResult(0).booleanValue());
		sm.close();
	}
	
	@Test
	public void testNetworkSessionProvider() throws Exception {
		SessionInMemory sm = new SessionInMemory(5000);
		NetworkSessionProvider sp = new NetworkSessionProvider(sm, "test");
		Assert.assertEquals(5000, sp.getStorage().getExpiration());
		TCPClient client = new TCPClient();
		client.connect(new InetSocketAddress("www.google.com", 80), 10000).blockThrow(0);
		Session s = sp.create(client);
		String id = s.getId();
		Assert.assertNull(s.getData("hello"));
		s.putData("hello", "world");
		sp.save(s, client);
		s = sp.get(id, client).blockResult(0);
		Assert.assertNotNull(s);
		Assert.assertEquals("world", s.getData("hello"));
		sp.destroy(s);
		s = sp.get(id, client).blockResult(0);
		Assert.assertNull(s);
		sp.close();
		client.close();
		sm.close();
	}
	
}
