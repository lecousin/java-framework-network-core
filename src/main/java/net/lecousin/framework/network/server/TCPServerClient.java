package net.lecousin.framework.network.server;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.function.Supplier;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.network.AttributesContainer;
import net.lecousin.framework.network.TCPRemote;

/**
 * A client connected to a {@link TCPServer}.
 */
public class TCPServerClient implements AttributesContainer, Closeable, TCPRemote {

	TCPServerClient(TCPServer.Client privateInterface) {
		this.privateInterface = privateInterface;
	}
	
	private TCPServer.Client privateInterface;
	private HashMap<String,Object> attributes = new HashMap<>(20);
	ArrayList<AutoCloseable> toClose = new ArrayList<>();
	LinkedList<IAsync<?>> pending = new LinkedList<>();
	
	@Override
	public void setAttribute(String key, Object value) { attributes.put(key, value); }
	
	@Override
	public Object getAttribute(String key) { return attributes.get(key); }
	
	@Override
	public Object removeAttribute(String key) { return attributes.remove(key); }
	
	@Override
	public boolean hasAttribute(String name) { return attributes.containsKey(name); }
	
	/** Signal we are expecting data from this client.
	 * @param timeout timeout in milliseconds
	 */
	public void waitForData(int timeout) throws ClosedChannelException {
		privateInterface.waitForData(timeout);
	}
	
	/** Send data to this client. */
	public Async<IOException> send(ByteBuffer data, boolean closeAfter) throws ClosedChannelException {
		return privateInterface.send(data, closeAfter);
	}
	
	@Override
	public IAsync<IOException> send(ByteBuffer data) {
		try { return send(data, false); }
		catch (ClosedChannelException e) { return new Async<>(e); }
	}

	@Override
	public boolean isClosed() {
		return privateInterface.isClosed();
	}
	
	@Override
	public void close() {
		privateInterface.close();
	}
	
	/** Signal this client has been closed. */
	public void closed() {
		privateInterface.close();
	}
	
	/** Add a resource to close together with this client. */
	public void addToClose(AutoCloseable toClose) {
		this.toClose.add(toClose);
	}

	/** Remove a resource to close together with this client. */
	public void removeToClose(AutoCloseable toClose) {
		this.toClose.remove(toClose);
	}
	
	/** Add a synchronization point that should be cancelled on client deconnection. */
	public void addPending(IAsync<?> sp) {
		if (sp.isDone()) return;
		synchronized (pending) { pending.add(sp); }
		sp.onDone(() -> {
			synchronized (pending) { pending.remove(sp); }
		});
	}
	
	/** Called when this client is closed, and closes associated resources. */
	@Override
	public void onclosed(Runnable r) {
		if (privateInterface.isClosed())
			r.run();
		else
			toClose.add(r::run);
	}
	
	/**
	 * Send data to this client, but instead of giving directly the data, a provider is given.
	 */
	@Override
	public void newDataToSendWhenPossible(Supplier<ByteBuffer> dataProvider, Async<IOException> sp) {
		privateInterface.newDataToSendWhenPossible(dataProvider, sp);
	}
	
	@Override
	public String toString() {
		return "TCPServerClient on " + privateInterface.channel;
	}
	
	/** Return the local address of this client. */
	@Override
	public SocketAddress getLocalAddress() throws IOException {
		return privateInterface.channel.getLocalAddress();
	}

	/** Return the remote address of this client. */
	@Override
	public SocketAddress getRemoteAddress() throws IOException {
		return privateInterface.channel.getRemoteAddress();
	}
	
	/** Return the remote address of this client. */
	public byte[] getClientAddress() throws IOException {
		return ((InetSocketAddress)privateInterface.channel.getRemoteAddress()).getAddress().getAddress();
	}
	
	public TCPServer getServer() {
		return privateInterface.getServer();
	}
}
