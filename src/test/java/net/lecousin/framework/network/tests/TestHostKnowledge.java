package net.lecousin.framework.network.tests;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.LinkedList;

import net.lecousin.framework.application.Version;
import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.memory.IMemoryManageable.FreeMemoryLevel;
import net.lecousin.framework.network.cache.HostKnowledgeCache;
import net.lecousin.framework.network.cache.HostPortKnowledge;
import net.lecousin.framework.network.cache.HostProtocol;

import org.junit.Assert;
import org.junit.Test;

public class TestHostKnowledge extends LCCoreAbstractTest {

	@Test
	public void test() throws Exception {
		HostKnowledgeCache cache = HostKnowledgeCache.get();
		
		Assert.assertEquals(0, cache.getKnownPorts(InetAddress.getLoopbackAddress()).size());
		
		Assert.assertNull(cache.getKnowledge(InetAddress.getLoopbackAddress(), 1));
		HostPortKnowledge hp = cache.getOrCreateKnowledge(InetAddress.getLoopbackAddress(), 1);
		Assert.assertEquals(hp, cache.getKnowledge(new InetSocketAddress(InetAddress.getLoopbackAddress(), 1)));

		Assert.assertEquals(1, cache.getKnownPorts(InetAddress.getLoopbackAddress()).size());
		
		HostPortKnowledge hp2 = cache.getOrCreateKnowledge(new InetSocketAddress(InetAddress.getLoopbackAddress(), 2));
		Assert.assertEquals(hp2, cache.getOrCreateKnowledge(new InetSocketAddress(InetAddress.getLoopbackAddress(), 2)));

		HostProtocol p = new HostProtocol("test");
		hp.addProtocol(p);
		hp.used();
		Assert.assertEquals(1, hp.getProtocols().size());
		Assert.assertEquals(p, hp.getProtocolByName("test"));
		Assert.assertNull(hp.getProtocolByName("test2"));
		Assert.assertNull(hp.getProtocolByAlpn("test"));
		Assert.assertNull(hp.getProtocolByAlpn("test2"));
		
		p.setAlpn("test2");
		Assert.assertEquals(p, hp.getProtocolByAlpn("test2"));
		
		Assert.assertNull(p.getImplementation());
		p.setImplementation("impl");
		Assert.assertEquals("impl", p.getImplementation());
		
		Assert.assertEquals(0, p.getVersions().size());
		p.addVersion(new Version("1.0"));
		Assert.assertEquals(1, p.getVersions().size());
		
		hp.addProtocol(new HostProtocol("test2", "test2", new LinkedList<>(), null));
		hp.getOrCreateProtocolByName("test3");
		
		cache.getDescription();
		cache.getItemsDescription();
		cache.freeMemory(FreeMemoryLevel.EXPIRED_ONLY);
		cache.freeMemory(FreeMemoryLevel.LOW);
		cache.freeMemory(FreeMemoryLevel.MEDIUM);
		cache.freeMemory(FreeMemoryLevel.URGENT);
	}
	
}
