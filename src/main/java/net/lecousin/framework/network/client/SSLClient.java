package net.lecousin.framework.network.client;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Supplier;

import javax.net.ssl.SSLException;

import net.lecousin.framework.collections.TurnArray;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.async.LockPoint;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.network.SocketOptionValue;
import net.lecousin.framework.network.ssl.SSLConnectionConfig;
import net.lecousin.framework.network.ssl.SSLLayer;
import net.lecousin.framework.util.Triple;

/**
 * SSL client adding SSL layer to a TCPClient.
 */
public class SSLClient extends TCPClient {

	/** Constructor. */
	public SSLClient(SSLConnectionConfig config) {
		ssl = new SSLLayer(config);
	}
	
	private SSLLayer ssl;
	private String applicationProtocol;
	private final TurnArray<ByteBuffer> receivedData = new TurnArray<>(5);

	private static final String CONNECT_ATTRIBUTE = "sslclient.connect";
	private static final String WAITING_DATA_ATTRIBUTE = "sslclient.waitfordata";
	
	private final SSLLayer.TCPConnection sslClient = new SSLLayer.TCPConnection() {
		
		@Override
		public Object getAttribute(String name) {
			return SSLClient.this.getAttribute(name);
		}
		
		@Override
		public void setAttribute(String name, Object value) {
			SSLClient.this.setAttribute(name, value);
		}
		
		@Override
		public Object removeAttribute(String name) {
			return SSLClient.this.removeAttribute(name);
		}
		
		@Override
		public boolean hasAttribute(String name) {
			return SSLClient.this.hasAttribute(name);
		}
		
		@Override
		public boolean isClosed() {
			return SSLClient.this.isClosed();
		}
		
		@Override
		public void close() {
			SSLClient.this.close();
		}
		
		@Override
		public void closed() {
			@SuppressWarnings("unchecked")
			Async<IOException> sp = (Async<IOException>)getAttribute(CONNECT_ATTRIBUTE);
			if (sp != null && !sp.isDone()) sp.error(new ClosedChannelException());
			channelClosed();
		}
		
		@Override
		public void handshakeDone(String alpn) {
			applicationProtocol = alpn;
			@SuppressWarnings("unchecked")
			Async<IOException> sp = (Async<IOException>)getAttribute(CONNECT_ATTRIBUTE);
			sp.unblock();
		}
		
		@Override
		public void handshakeError(SSLException error) {
			@SuppressWarnings("unchecked")
			Async<IOException> sp = (Async<IOException>)getAttribute(CONNECT_ATTRIBUTE);
			sp.error(error);
		}
		
		@SuppressWarnings("unchecked")
		@Override
		public void dataReceived(LinkedList<ByteBuffer> data) {
			if (data.isEmpty()) return;
			AsyncSupplier<ByteBuffer, IOException> waiting;
			ByteBuffer buffer;
			Triple<AsyncSupplier<ByteBuffer, IOException>, Integer, Integer> waitAgain = null;
			synchronized (sslClient) {
				LinkedList<Triple<AsyncSupplier<ByteBuffer, IOException>, Integer, Integer>> list =
					(LinkedList<Triple<AsyncSupplier<ByteBuffer, IOException>, Integer, Integer>>)
					getAttribute(WAITING_DATA_ATTRIBUTE);
				if (list == null) {
					receivedData.addAll(data);
					return;
				}
				waiting = list.removeFirst().getValue1();
				if (list.isEmpty())
					removeAttribute(WAITING_DATA_ATTRIBUTE);
				else
					waitAgain = list.getFirst();
				buffer = data.removeFirst();
				if (!data.isEmpty())
					receivedData.addAll(data);
			}
			if (waitAgain != null)
				waitForSSLData(waitAgain.getValue2().intValue(), waitAgain.getValue3().intValue());
			waiting.unblockSuccess(buffer);
		}
		
		@Override
		public void waitForData(int expectedBytes, int timeout) {
			waitForSSLData(expectedBytes, timeout);
		}
		
		@Override
		public IAsync<IOException> sendEmpty(ByteBuffer data) {
			LinkedList<ByteBuffer> b;
			try {
				b = ssl.encryptDataToSend(this, Collections.singletonList(data));
			} catch (SSLException e) {
				return new Async<>(e);
			}
			if (!b.isEmpty()) {
				return SSLClient.super.send(b, 10000);
			}
			return new Async<>(new SSLException("Error encrypting data"));
		}
		
		@Override
		public String toString() {
			return SSLClient.this.toString();
		}

	};
	
	@Override
	protected void channelClosed() {
		cancelPendingWaitingData();
		if (closed) return;
		super.channelClosed();
		sslClient.closed();
	}
	
	@Override
	public void close() {
		cancelPendingWaitingData();
		super.close();
	}
	
	@SuppressWarnings("unchecked")
	protected void cancelPendingWaitingData() {
		LinkedList<Triple<AsyncSupplier<ByteBuffer, IOException>, Integer, Integer>> list;
		synchronized (sslClient) {
			list = (LinkedList<Triple<AsyncSupplier<ByteBuffer, IOException>, Integer, Integer>>)
				removeAttribute(WAITING_DATA_ATTRIBUTE);
		}
		if (list != null) {
			logger.debug("Cancel SSL Client pending read operations as client has been closed");
			for (Triple<AsyncSupplier<ByteBuffer, IOException>, Integer, Integer> t : list)
				t.getValue1().error(new EOFException("Client was closed"));
		}
	}
	
	@Override
	public Async<IOException> connect(SocketAddress address, int timeout, SocketOptionValue<?>... options) {
		Async<IOException> result = new Async<>();
		setAttribute(CONNECT_ATTRIBUTE, result);
		Async<IOException> conn = super.connect(address, timeout, options);
		conn.thenStart("Start SSL protocol for TCPClient", Task.Priority.NORMAL, (Task<Void, NoException> t) -> {
			if (!conn.isSuccessful()) {
				removeAttribute(CONNECT_ATTRIBUTE);
				conn.forwardIfNotSuccessful(result);
				return null;
			}
			ssl.startConnection(sslClient, true, timeout);
			return null;
		}, true);
		return result;
	}
	
	/**
	 * Instead of connecting directly, this method can be used when a tunnel has been established through
	 * another TCPClient. This SSLClient will use the SocketChannel of the tunnel, and start the SSL handshake.
	 * The given synchronization point is unblocked once the SSL handshake is done.
	 */
	public void tunnelConnected(TCPClient tunnel, Async<IOException> connection, int timeout) {
		setAttribute(CONNECT_ATTRIBUTE, connection);
		channel = tunnel.channel;
		closed = false;
		Task.cpu("Start SSL protocol for TCPClient through tunnel", Task.Priority.NORMAL, t -> {
			ssl.startConnection(sslClient, true, timeout);
			return null;
		}).start();
	}
	
	/**
	 * Return the application protocol negotiated using ALPN.
	 * It is only available once connection and handshake is done, and only if specific
	 * protocols have been requested and that one is matching the protocols offer by the server.
	 */
	public String getApplicationProtocol() {
		return applicationProtocol;
	}
	
	@Override
	public AsyncSupplier<ByteBuffer, IOException> receiveData(int expectedBytes, int timeout) {
		AsyncSupplier<ByteBuffer, IOException> result = new AsyncSupplier<>();
		boolean firstWait = false;
		synchronized (sslClient) {
			if (!receivedData.isEmpty()) {
				result.unblockSuccess(receivedData.removeFirst());
				return result;
			}
			@SuppressWarnings("unchecked")
			LinkedList<Triple<AsyncSupplier<ByteBuffer, IOException>, Integer, Integer>> list =
				(LinkedList<Triple<AsyncSupplier<ByteBuffer, IOException>,Integer,Integer>>)getAttribute(WAITING_DATA_ATTRIBUTE);
			if (list == null) {
				list = new LinkedList<>();
				setAttribute(WAITING_DATA_ATTRIBUTE, list);
				firstWait = true;
			}
			list.add(new Triple<>(result, Integer.valueOf(expectedBytes), Integer.valueOf(timeout)));
		}
		if (result.isDone())
			return result;
		if (firstWait)
			waitForSSLData(expectedBytes, timeout);
		return result;
	}
	
	private AsyncSupplier<ByteBuffer, IOException> lastReceive = null;
	private Async<NoException> lastReceiveDecrypted = null;
	
	private void waitForSSLData(int expectedBytes, int timeout) {
		AsyncSupplier<ByteBuffer, IOException> receive;
		Async<NoException> decrypted = new Async<>();
		Async<NoException> previous;
		synchronized (sslClient) {
			if (lastReceive != null && !lastReceive.isDone())
				return;
			receive = super.receiveData(expectedBytes, timeout);
			lastReceive = receive;
			previous = lastReceiveDecrypted;
			lastReceiveDecrypted = decrypted;
		}
		@SuppressWarnings("unchecked")
		Task<Void, NoException> task = Task.cpu("Receive SSL data from server", Task.Priority.NORMAL, taskCtx -> {
			if (!receive.isSuccessful()) {
				LinkedList<Triple<AsyncSupplier<ByteBuffer, IOException>, Integer, Integer>> list;
				synchronized (sslClient) {
					list = (LinkedList<Triple<AsyncSupplier<ByteBuffer, IOException>, Integer, Integer>>)
						removeAttribute(WAITING_DATA_ATTRIBUTE);
				}
				if (list != null)
					for (Triple<AsyncSupplier<ByteBuffer, IOException>, Integer, Integer> t : list)
						receive.forwardIfNotSuccessful(t.getValue1());
				return null;
			}
			ByteBuffer b = receive.getResult();
			if (b == null) {
				close();
				return null;
			}
			ssl.dataReceived(sslClient, b, timeout);
			return null;
		});
		receive.onDone(() -> {
			if (previous == null) task.start();
			else previous.thenStart(task, true);
		});
		task.getOutput().onDone(decrypted);
	}
	
	private LockPoint<NoException> encryptLock = new LockPoint<>();
	
	@Override
	public IAsync<IOException> send(List<ByteBuffer> data, int timeout) {
		LinkedList<ByteBuffer> encrypted;
		encryptLock.lock();
		try {
			encrypted = ssl.encryptDataToSend(sslClient, data);
		} catch (SSLException e) {
			return new Async<>(e);
		} finally {
			encryptLock.unlock();
		}
		return SSLClient.super.send(encrypted, timeout);
	}
	
	@Override
	public void newDataToSendWhenPossible(Supplier<List<ByteBuffer>> dataProvider, Async<IOException> sp, int timeout) {
		super.newDataToSendWhenPossible(() -> {
			LinkedList<ByteBuffer> encrypted;
			encryptLock.lock();
			try {
				encrypted = ssl.encryptDataToSend(sslClient, dataProvider.get());
			} catch (SSLException e) {
				sp.error(e);
				return new ArrayList<>(0);
			} finally {
				encryptLock.unlock();
			}
			return encrypted;
		}, sp, timeout);
	}
	
	
	@Override
	public String toString() {
		return "SSLClient [" + channel + "]";
	}
}
