package net.lecousin.framework.network.tests;

import java.net.InetSocketAddress;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.memory.IMemoryManageable.FreeMemoryLevel;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.session.NetworkSessionProvider;
import net.lecousin.framework.network.session.Session;
import net.lecousin.framework.network.session.SessionInMemory;

import org.junit.Assert;
import org.junit.Test;

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
		s.removeData("hello");
		Assert.assertNull(s.getData("hello"));
		// removed by not saved
		sm.release(id);
		s = new Session(id);
		Assert.assertTrue(sm.load(id, s).blockResult(0).booleanValue());
		Assert.assertEquals("world", s.getData("hello"));
		s.removeData("hello");
		Assert.assertNull(s.getData("hello"));
		// removed and saved
		sm.save(id, s).blockThrow(0);
		s = new Session(id);
		Assert.assertTrue(sm.load(id, s).blockResult(0).booleanValue());
		Assert.assertNull(s.getData("hello"));
		
		sm.release(id);
		sm.remove(id);
		Assert.assertFalse(sm.load(id, new Session("")).blockResult(0).booleanValue());
		sm.freeMemory(FreeMemoryLevel.EXPIRED_ONLY);
		sm.close();
	}
	
	@Test
	public void testSessionInMemoryLoadExpired() throws Exception {
		SessionInMemory sm = new SessionInMemory(2000);
		String id = sm.allocateId();
		Session s = new Session(id);
		s.putData("toto", "titi");
		sm.save(id, s).blockThrow(0);
		sm.getDescription();
		sm.getItemsDescription();
		sm.freeMemory(FreeMemoryLevel.EXPIRED_ONLY);
		Assert.assertTrue(sm.load(id, s).blockResult(0).booleanValue());
		Assert.assertEquals("titi", s.getData("toto"));
		s.putData("toto", "tata");
		long t1 = System.currentTimeMillis();
		sm.save(id, s).blockThrow(0);
		long t2 = System.currentTimeMillis();
		// wait for expiration
		do {
			boolean loaded = sm.load(id, new Session("")).blockResult(0).booleanValue();
			long t = System.currentTimeMillis();
			if (t - t1 < 2000)
				Assert.assertTrue(loaded);
			else if (t - t2 > 2000) {
				if (t - t2 > 5000)
					Assert.assertFalse(loaded);
				else if (loaded)
					continue;
				break;
			}
		} while (true);
		sm.close();
	}
	
	@Test
	public void testSessionInMemoryAutomaticExpiration() throws Exception {
		SessionInMemory sm = new SessionInMemory(10);
		String id = sm.allocateId();
		Session s = new Session(id);
		s.putData("toto", "titi");
		sm.save(id, s).blockThrow(0);
		// wait for expiration
		Async<NoException> wait = new Async<>();
		wait.block(2000);
		Assert.assertFalse(sm.load(id, new Session("")).blockResult(0).booleanValue());
		sm.close();
	}
	
	@Test
	public void testNetworkSessionProvider() throws Exception {
		SessionInMemory sm = new SessionInMemory(5000);
		NetworkSessionProvider sp = new NetworkSessionProvider(sm, LCCore.getApplication());
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
		Assert.assertNull(sp.get(null, null));
		Assert.assertNull(sp.get("1", null).blockResult(0));
		s = sp.get("0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF", client).blockResult(0);
		Assert.assertNull(s);
		
		s = sp.create(client);
		sp.save(s, client);
		id = s.getId();
		Assert.assertNotNull(sp.get(id, client).blockResult(0));
		s = sp.get("0000000000000000" + id.substring(16, id.length() - 16) + "0000000000000000", client).blockResult(0);
		Assert.assertNull(s);
		s = sp.get("0000000000000000" + id.substring(16), client).blockResult(0);
		Assert.assertNull(s);

		client.close();
		Assert.assertNull(sp.get(id, client).blockResult(0));
		
		client = new TCPClient();
		client.connect(new InetSocketAddress("www.yahoo.com", 80), 10000).blockThrow(0);
		Assert.assertNull(sp.get(id, client).blockResult(0));
		client.close();
		
		Assert.assertNull(sp.create(new TCPClient()));
		sm.close();
		Assert.assertNull(sp.create(new TCPClient()));
		sp.close();
	}
	
}
