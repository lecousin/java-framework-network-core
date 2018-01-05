package net.lecousin.framework.network.tests;

import org.junit.Assert;
import org.junit.Test;

import net.lecousin.framework.core.test.LCCoreAbstractTest;
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
	
}
