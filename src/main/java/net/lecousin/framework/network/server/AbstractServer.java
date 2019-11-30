package net.lecousin.framework.network.server;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.NetworkChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.application.Application;
import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.network.NetworkManager;

/** Abstract class for a server.
 * @param <TNetworkChannel> type of network channel
 * @param <TServerChannel> implementation of AbstractServerChannel
 */
public abstract class AbstractServer<TNetworkChannel extends AbstractSelectableChannel & NetworkChannel,
	 TServerChannel extends AbstractServer.AbstractServerChannel<TNetworkChannel>>
implements Closeable {

	protected AbstractServer() {
		app = LCCore.getApplication();
		manager = NetworkManager.get(app);
		app.toClose(this);
	}
	
	protected Application app;
	protected NetworkManager manager;
	protected ArrayList<TServerChannel> channels = new ArrayList<>();

	@Override
	public void close() {
		unbindAll();
		app.closed(this);
	}
	
	/** Stop listening to all ports and addresses. */
	public void unbindAll() {
		List<IAsync<IOException>> sp = new LinkedList<>();
		for (TServerChannel channel : channels) {
			if (manager.getLogger().info())
				manager.getLogger().info("Closing server: " + channel.channel.toString());
			channel.key.cancel();
			try { channel.channel.close(); }
			catch (IOException e) { manager.getLogger().error("Error closing server", e); }
			sp.add(NetworkManager.get().register(channel.channel, 0, null, 0));
		}
		for (IAsync<IOException> s : sp)
			s.block(5000);
		channels.clear();
	}
	
	/** Return the list of addresses this server listens to. */
	@SuppressWarnings("squid:S1319") // return ArrayList instead of List
	public ArrayList<InetSocketAddress> getLocalAddresses() {
		ArrayList<InetSocketAddress> addresses = new ArrayList<>(channels.size());
		for (TServerChannel channel : channels)
			try { addresses.add((InetSocketAddress)channel.channel.getLocalAddress()); }
			catch (Exception e) { /* ignore */ }
		return addresses;
	}
	
	protected void finalizeBinding(
		AsyncSupplier<SelectionKey, IOException> accept, TServerChannel sc, TNetworkChannel channel,
		AsyncSupplier<SocketAddress, IOException> result
	) {
		accept.thenStart(new Task.Cpu.FromRunnable("Bind server", Task.PRIORITY_IMPORTANT, () -> {
			sc.key = accept.getResult();
			channels.add(sc);
			try {
				SocketAddress addr = channel.getLocalAddress();
				if (manager.getLogger().info())
					manager.getLogger().info("New server listening at " + addr.toString());
				result.unblockSuccess(addr);
			} catch (IOException e) {
				result.error(e);
			}
		}), result);
	}
	
	
	/** Abstract class for a channel on which the server is listening.
	 * @param <T> type of network channel
	 */
	protected abstract static class AbstractServerChannel<T extends AbstractSelectableChannel> {
		public AbstractServerChannel(T channel) {
			this.channel = channel;
		}
		
		protected T channel;
		protected SelectionKey key;
	}

}
