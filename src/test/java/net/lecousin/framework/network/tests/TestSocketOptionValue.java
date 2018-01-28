package net.lecousin.framework.network.tests;

import java.net.StandardSocketOptions;

import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.network.SocketOptionValue;

import org.junit.Assert;
import org.junit.Test;

public class TestSocketOptionValue extends LCCoreAbstractTest {

	@Test
	public void test() {
		SocketOptionValue<Boolean> sov = new SocketOptionValue<>(StandardSocketOptions.SO_KEEPALIVE, Boolean.TRUE);
		Assert.assertTrue(sov.getValue().booleanValue());
		sov.setValue(Boolean.FALSE);
		Assert.assertFalse(sov.getValue().booleanValue());
	}
	
}
