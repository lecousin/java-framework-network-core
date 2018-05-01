package net.lecousin.framework.network.server.protocol;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.LinkedList;

import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.synch.ISynchronizationPoint;
import net.lecousin.framework.concurrent.synch.SynchronizationPoint;
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
		client.setAttribute(ATTRIBUTE_LAST_SEND, new SynchronizationPoint<>(true));
		client.onclosed(() -> { tunnel.close(); });
		tunnel.getReceiver().readForEver(bufferSize, -1, (data) -> {
			if (data == null) {
				tunnel.close();
				client.close();
				return;
			}
			logger.trace("Data received from remote: " + data.remaining());
			client.send(data);
		});
		try {
			client.waitForData(0);
		} catch (ClosedChannelException e) {
			tunnel.close();
		}
	}
	
	@Override
	public void startProtocol(TCPServerClient client) {
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
		SynchronizationPoint<IOException> previous = (SynchronizationPoint<IOException>)client.getAttribute(ATTRIBUTE_LAST_SEND);
		boolean readAgain = previous.isUnblocked();
		SynchronizationPoint<IOException> ls = new SynchronizationPoint<>();
		client.setAttribute(ATTRIBUTE_LAST_SEND, ls);
		@SuppressWarnings("resource")
		TCPClient tunnel = (TCPClient)client.getAttribute(ATTRIBUTE_TUNNEL);
		if (readAgain)
			try { client.waitForData(0); }
			catch (ClosedChannelException e) {
				tunnel.close();
			}
		new Task.Cpu.FromRunnable("Forward data from client to remote", Task.PRIORITY_NORMAL, () -> {
			logger.trace("Data received from client: " + data.remaining());
			ISynchronizationPoint<IOException> send = tunnel.send(data);
			send.listenInline(onbufferavailable);
			send.listenInline(ls);
			if (!readAgain)
				try { client.waitForData(0); }
				catch (ClosedChannelException e) {
					tunnel.close();
				}
		}).startOn(previous, false);
	}
	
}
