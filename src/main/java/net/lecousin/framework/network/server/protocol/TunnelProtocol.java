package net.lecousin.framework.network.server.protocol;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.server.TCPServerClient;

/**
 * Implements tunnelling between a connected client, and a remote side.
 */
public class TunnelProtocol implements ServerProtocol {

	/** Constructor. */
	public TunnelProtocol(int bufferSize, int clientSendTimeout, int remoteSendTimeout, Logger logger) {
		this.bufferSize = bufferSize;
		this.clientSendTimeout = clientSendTimeout;
		this.remoteSendTimeout = remoteSendTimeout;
		this.logger = logger;
	}
	
	protected int bufferSize;
	protected int clientSendTimeout;
	protected int remoteSendTimeout;
	protected Logger logger;
	
	public int getClientSendTimeout() {
		return clientSendTimeout;
	}

	public void setClientSendTimeout(int clientSendTimeout) {
		this.clientSendTimeout = clientSendTimeout;
	}

	public int getRemoteSendTimeout() {
		return remoteSendTimeout;
	}

	public void setRemoteSendTimeout(int remoteSendTimeout) {
		this.remoteSendTimeout = remoteSendTimeout;
	}

	public static final String ATTRIBUTE_TUNNEL = "tunnelProtocol.remote";
	public static final String ATTRIBUTE_LAST_SEND = "tunnelProtocol.lastSend";
	
	/** Start tunneling between the given client, and the given connected remote side. */
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
			client.send(data, clientSendTimeout);
		}, false);
		try { client.waitForData(0); }
		catch (ClosedChannelException e) { tunnel.close(); }
	}
	
	@Override
	public int startProtocol(TCPServerClient client) {
		// nothing to do
		return -1;
	}
	
	@Override
	public int getInputBufferSize(TCPServerClient client) {
		return bufferSize;
	}
	
	@Override
	public void dataReceivedFromClient(TCPServerClient client, ByteBuffer data) {
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
		Task.cpu("Forward data from client to remote", Task.Priority.NORMAL, t -> {
			if (logger.trace()) logger.trace("Data received from " + client + ": " + data.remaining());
			IAsync<IOException> send = tunnel.send(data, remoteSendTimeout);
			send.onDone(ls);
			if (!readAgain)
				try { client.waitForData(0); }
				catch (ClosedChannelException e) { tunnel.close(); }
			return null;
		}).startOn(previous, false);
		ls.onError(error -> {
			logger.error("Error sending data to tunnel, close client.", error);
			client.close();
		});
	}
	
}
