package net.lecousin.framework.network.server.protocol;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.LinkedList;

import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.server.TCPServerClient;

/**
 * Implements tunnelling between a connected client, and a remote side.
 */
public class TunnelProtocol implements ServerProtocol {

	/** Constructor. */
	public TunnelProtocol(int bufferSize, Logger logger) {
		this.bufferSize = bufferSize;
		this.logger = logger;
	}
	
	protected int bufferSize;
	protected Logger logger;
	
	public static final String ATTRIBUTE_TUNNEL = "tunnelProtocol.remote";
	public static final String ATTRIBUTE_LAST_SEND = "tunnelProtocol.lastSend";
	
	/** Start tunnelling between the given client, and the given connected remote side. */
	public void registerClient(TCPServerClient client, TCPClient tunnel) {
		client.setAttribute(ATTRIBUTE_TUNNEL, tunnel);
		client.setAttribute(ATTRIBUTE_LAST_SEND, new Async<>(true));
		client.onclosed(tunnel::close);
		tunnel.getReceiver().readForEver(bufferSize, -1, data -> {
			if (data == null) {
				tunnel.close();
				client.close();
				return;
			}
			if (logger.trace()) logger.trace("Data received from remote: " + data.remaining());
			client.send(data);
		}, false);
		try { client.waitForData(0); }
		catch (ClosedChannelException e) { tunnel.close(); }
	}
	
	@Override
	public void startProtocol(TCPServerClient client) {
		// nothing to do
	}
	
	@Override
	public LinkedList<ByteBuffer> prepareDataToSend(TCPServerClient client, ByteBuffer data) {
		LinkedList<ByteBuffer> list = new LinkedList<>();
		list.add(data);
		return list;
	}
	
	@Override
	public int getInputBufferSize() {
		return bufferSize;
	}
	
	@Override
	public void dataReceivedFromClient(TCPServerClient client, ByteBuffer data, Runnable onbufferavailable) {
		@SuppressWarnings("unchecked")
		Async<IOException> previous = (Async<IOException>)client.getAttribute(ATTRIBUTE_LAST_SEND);
		boolean readAgain = previous.isDone();
		Async<IOException> ls = new Async<>();
		client.setAttribute(ATTRIBUTE_LAST_SEND, ls);
		TCPClient tunnel = (TCPClient)client.getAttribute(ATTRIBUTE_TUNNEL);
		if (readAgain)
			try { client.waitForData(0); }
			catch (ClosedChannelException e) {
				tunnel.close();
				return;
			}
		new Task.Cpu.FromRunnable("Forward data from client to remote", Task.PRIORITY_NORMAL, () -> {
			if (logger.trace()) logger.trace("Data received from client: " + data.remaining());
			IAsync<IOException> send = tunnel.send(data);
			send.onDone(onbufferavailable);
			send.onDone(ls);
			if (!readAgain)
				try { client.waitForData(0); }
				catch (ClosedChannelException e) { tunnel.close(); }
		}).startOn(previous, false);
	}
	
}
