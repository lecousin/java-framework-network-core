package net.lecousin.framework.network.tests;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;

import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.network.NetworkManager;
import net.lecousin.framework.network.NetworkManager.Listener;

import org.junit.Test;

public class TestNetworkManager extends LCCoreAbstractTest {
	
	private static class WrongListener implements Listener {

		@Override
		public void channelClosed() {
		}
		
	}

	@Test
	public void testRegisterInvalidListeners() throws Exception {
		try (ServerSocketChannel channel = ServerSocketChannel.open()) {
			try {
				NetworkManager.get().register(null, 0, null, 0).blockThrow(0);
				throw new AssertionError();
			} catch (IOException e) {
				// ok
			}
			try {
				NetworkManager.get().register(channel, SelectionKey.OP_ACCEPT, new WrongListener(), 0).blockThrow(0);
				throw new AssertionError();
			} catch (IOException e) {
				// ok
			}
			try {
				NetworkManager.get().register(channel, SelectionKey.OP_CONNECT, new WrongListener(), 0).blockThrow(0);
				throw new AssertionError();
			} catch (IOException e) {
				// ok
			}
			try {
				NetworkManager.get().register(channel, SelectionKey.OP_READ, new WrongListener(), 0).blockThrow(0);
				throw new AssertionError();
			} catch (IOException e) {
				// ok
			}
			try {
				NetworkManager.get().register(channel, SelectionKey.OP_WRITE, new WrongListener(), 0).blockThrow(0);
				throw new AssertionError();
			} catch (IOException e) {
				// ok
			}
		}
	}
	
}
