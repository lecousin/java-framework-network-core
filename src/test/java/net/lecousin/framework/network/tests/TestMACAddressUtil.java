package net.lecousin.framework.network.tests;

import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.encoding.EncodingException;
import net.lecousin.framework.network.MACAddressUtil;

import org.junit.Assert;
import org.junit.Test;

public class TestMACAddressUtil extends LCCoreAbstractTest {

	@Test
	public void testFromString() throws Exception {
		Assert.assertArrayEquals(
			new byte[] { 1, 2, 3, 4, 5, 6 },
			MACAddressUtil.fromString("01:02:03:04:05:06")
		);
		Assert.assertArrayEquals(
			new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 },
			MACAddressUtil.fromString("01-02-03-04-05-06-07-08")
		);
		Assert.assertArrayEquals(
			new byte[] { 1, 2, 3, 4, 5, 6 },
			MACAddressUtil.fromString("0102.0304.0506")
		);
		
		try {
			MACAddressUtil.fromString("1");
			throw new AssertionError();
		} catch (EncodingException e) {
			// ok
		}
		
		try {
			MACAddressUtil.fromString("01:02:03:04-05:06");
			throw new AssertionError();
		} catch (EncodingException e) {
			// ok
		}
		
		try {
			MACAddressUtil.fromString("0102.0304-0506");
			throw new AssertionError();
		} catch (EncodingException e) {
			// ok
		}
		
		try {
			MACAddressUtil.fromString("0102.0304.050607");
			throw new AssertionError();
		} catch (EncodingException e) {
			// ok
		}
		
		try {
			MACAddressUtil.fromString("01.02.03.04.05.0607");
			throw new AssertionError();
		} catch (EncodingException e) {
			// ok
		}
	}
	
	@Test
	public void testToString() {
		Assert.assertEquals(
			"01:02:03:04:05:06",
			new String(MACAddressUtil.toString(new byte[] { 1, 2, 3, 4, 5, 6 }))
		);

		Assert.assertEquals(
			"01-02-03-04-05-06",
			new String(MACAddressUtil.toStringGroups2Digits(new byte[] { 1, 2, 3, 4, 5, 6 }, '-'))
		);
	}
	
}
