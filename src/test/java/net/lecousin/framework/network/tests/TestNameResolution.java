package net.lecousin.framework.network.tests;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.network.name.NameService;
import net.lecousin.framework.network.name.NameService.Resolution;

import org.junit.Assert;
import org.junit.Test;

public class TestNameResolution extends LCCoreAbstractTest {

	@Test
	public void testLocalhost() throws Exception {
		List<Resolution> list = NameService.resolveName("localhost").blockResult(0);
		Assert.assertFalse(list.isEmpty());
		boolean found = false;
		for (Resolution r : list) {
			if (InetAddress.getLoopbackAddress().equals(r.getIp())) {
				found = true;
				Assert.assertTrue(r.getExpiration() > System.currentTimeMillis());
			}
		}
		Assert.assertTrue(found);
	}
	
	@Test
	public void testInvalid() throws Exception {
		try {
			NameService.resolveName("invalid.invalid").blockResult(0);
			throw new AssertionError();
		} catch (UnknownHostException e) {
			// ok
		}
	}
	
}
