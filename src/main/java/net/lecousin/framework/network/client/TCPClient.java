package net.lecousin.framework.network.client;

import java.io.Closeable;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.lecousin.framework.application.Application;
import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.collections.TurnArray;
import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.event.SimpleEvent;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.IOUtil;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.network.AttributesContainer;
import net.lecousin.framework.network.NetworkManager;
import net.lecousin.framework.network.SocketOptionValue;
import net.lecousin.framework.network.TCPRemote;
import net.lecousin.framework.util.DebugUtil;
import net.lecousin.framework.util.Pair;

/**
 * TCP client, connected to a server using TCP protocol (using sockets).
 * It uses the {@link NetworkManager} to make asynchronous operations.<br/>
 */
public class TCPClient implements AttributesContainer, Closeable, TCPRemote {
	
	/** Constcutor. */
	public TCPClient() {
		Application app = LCCore.getApplication();
		manager = NetworkManager.get(app);
		logger = app.getLoggerFactory().getLogger(TCPClient.class);
	}
	
	protected NetworkManager manager;
	protected Logger logger;
	protected SocketChannel channel;
	protected boolean closed = true;
	protected Async<IOException> spConnect;
	protected boolean endOfInput = false;
	private HashMap<String,Object> attributes = new HashMap<>(20);
	public static SimpleEvent onclosed = new SimpleEvent();
	private Supplier<ByteBuffer> dataToSendProvider = null;
	private Async<IOException> dataToSendSP = null;

	@Override
	public void setAttribute(String key, Object value) { attributes.put(key, value); }
	
	@Override
	public Object getAttribute(String key) { return attributes.get(key); }
	
	@Override
	public Object removeAttribute(String key) { return attributes.remove(key); }
	
	@Override
	public boolean hasAttribute(String name) { return attributes.containsKey(name); }
	
	@Override
	public SocketAddress getLocalAddress() throws IOException {
		return channel.getLocalAddress();
	}
	
	@Override
	public SocketAddress getRemoteAddress() throws IOException {
		return channel.getRemoteAddress();
	}
	
	@Override
	public void onclosed(Runnable listener) {
		onclosed.addListener(listener);
	}

	protected void channelClosed() {
		if (closed) return;
		logger.debug("Channel closed - client closed");
		closed = true;
		if (spConnect != null) spConnect.error(new ClosedChannelException());
		synchronized (toSend) {
			while (!toSend.isEmpty())
				toSend.removeFirst().getValue2().error(new ClosedChannelException());
		}
		networkClient.channelClosed();
		onclosed.fire();
	}
	
	private class NetworkClient implements NetworkManager.Client, NetworkManager.TCPReceiver {
		@Override
		public void channelClosed() {
			if (reading != null)
				reading.error(new ClosedChannelException());
			TCPClient.this.channelClosed();
		}
		
		@Override
		public void connected() {
			logger.debug("Client connected");
			spConnect.unblock();
			spConnect = null;
		}
		
		@Override
		public void connectionFailed(IOException error) {
			logger.debug("Client connection error", error);
			closed = true;
			spConnect.error(error);
			spConnect = null;
		}
		
		private AsyncSupplier<ByteBuffer, IOException> reading = null;
		private int expectedBytes = 4096;
		
		@Override
		public void received(ByteBuffer buffer) {
			if (logger.debug()) {
				logger.debug("Client received " + buffer.remaining() + " bytes");
				if (reading.isDone()) logger.error("Received data but this was not expected");
			}
			reading.unblockSuccess(buffer);
		}
		
		@Override
		public void receiveError(IOException error, ByteBuffer buffer) {
			logger.debug("Client receive error", error);
			reading.unblockError(error);
		}
		
		@Override
		public ByteBuffer allocateReceiveBuffer() {
			return ByteBuffer.allocate(expectedBytes);
		}
		
		@Override
		public void endOfInput(ByteBuffer buffer) {
			logger.debug("Client end of input");
			buffer.flip();
			endOfInput = true;
			reading.unblockSuccess(buffer);
		}
	}
	
	protected NetworkClient networkClient = new NetworkClient();
	
	/** Connect this client to the server at the given address. */
	@SuppressWarnings("unchecked")
	public Async<IOException> connect(SocketAddress address, int timeout, SocketOptionValue<?>... options) {
		if (logger.debug())
			logger.debug("Connecting to " + address.toString());
		if (spConnect != null)
			return new Async<>(new IOException("Client already connecting"));
		spConnect = new Async<>();
		Async<IOException> result = spConnect;
		try {
			closed = false;
			endOfInput = false;
			channel = SocketChannel.open();
			channel.configureBlocking(false);
			for (@SuppressWarnings("rawtypes") SocketOptionValue option : options)
				channel.setOption(option.getOption(), option.getValue());
			channel.connect(address);
			manager.register(channel, SelectionKey.OP_CONNECT, networkClient, timeout);
			return result;
		} catch (IOException e) {
			logger.debug("Connection error", e);
			closed = true;
			spConnect.error(e);
			channel = null;
			spConnect = null;
			return result;
		}
	}
	
	/**
	 * Receive data from the server.
	 * Important note: if end of stream is reached, the returned buffer is null.
	 * @param expectedBytes buffer size that will be used to receive data
	 */
	public AsyncSupplier<ByteBuffer, IOException> receiveData(int expectedBytes, int timeout) {
		if (endOfInput) return new AsyncSupplier<>(null, null);
		if (networkClient.reading != null && !networkClient.reading.isDone())
			return new AsyncSupplier<>(null, new IOException("TCPClient is already waiting for data"));
		logger.debug("Register to NetworkManager for reading data");
		networkClient.reading = new AsyncSupplier<>();
		networkClient.expectedBytes = expectedBytes;
		manager.register(channel, SelectionKey.OP_READ, networkClient, timeout);
		return networkClient.reading;
	}
	
	/** Utility class to bufferize received data. */
	public static class BufferedReceiver {
		/** Constructor. */
		public BufferedReceiver(TCPClient client) {
			this.client = client;
		}
		
		private TCPClient client;
		private ByteBuffer remainingRead = null;
		
		/** Receive some bytes from the server.<br/>
		 * If some bytes have been previously received but not yet consumed, those bytes are
		 * immediately returned. In that case, the returned buffer can be greater than the given buffer size.<br/>
		 * Else it registers this client as waiting for data, and returns data as soon as they are available.
		 */
		public AsyncSupplier<ByteBuffer, IOException> readAvailableBytes(int bufferSize, int timeout) {
			if (remainingRead != null) {
				AsyncSupplier<ByteBuffer,IOException> res = new AsyncSupplier<>(remainingRead, null);
				remainingRead = null;
				return res;
			}
			return client.receiveData(bufferSize, timeout);
		}
		
		/**
		 * Wait for the given number of bytes. If this number of bytes cannot be reached, an error is raised.
		 */
		public AsyncSupplier<byte[],IOException> readBytes(int nbBytes, int timeout) {
			AsyncSupplier<byte[],IOException> res = new AsyncSupplier<>();
			ByteBuffer buf = ByteBuffer.allocate(nbBytes);
			if (remainingRead != null) {
				buf.put(remainingRead);
				if (!remainingRead.hasRemaining())
					remainingRead = null;
				if (!buf.hasRemaining()) {
					res.unblockSuccess(buf.array());
					return res;
				}
			}
			client.receiveData(buf.remaining(), timeout).listen(
				new IOUtil.RecursiveAsyncSupplierListener<ByteBuffer>((result, that) -> {
					if (result == null) {
						res.unblockError(new IOException("End of data after " + buf.position()
							+ " bytes while waiting for " + nbBytes + " bytes"));
						return;
					}
					new Task.Cpu<Void, NoException>("Handle received data from TCPClient", Task.PRIORITY_RATHER_IMPORTANT) {
						@Override
						public Void run() {
							buf.put(result);
							if (result.hasRemaining())
								remainingRead = result;
							if (!buf.hasRemaining()) {
								res.unblockSuccess(buf.array());
								return null;
							}
							client.receiveData(buf.remaining(), timeout).listen(that);
							return null;
						}
					}.start();
				}, res, null));
			return res;
		}
		
		/**
		 * Receive data until the given byte is read. This can be useful for protocols that have an end of message marker,
		 * or protocols that send lines of text.
		 */
		@SuppressWarnings("resource")
		public AsyncSupplier<ByteArrayIO,IOException> readUntil(byte endMarker, int initialBufferSize, int timeout) {
			AsyncSupplier<ByteArrayIO,IOException> res = new AsyncSupplier<>();
			ByteArrayIO io = new ByteArrayIO(initialBufferSize, "");
			if (remainingRead != null) {
				do {
					byte b = remainingRead.get();
					if (b == endMarker) {
						io.seekSync(SeekType.FROM_BEGINNING, 0);
						res.unblockSuccess(io);
						if (!remainingRead.hasRemaining())
							remainingRead = null;
						return res;
					}
					io.write(b);
				} while (remainingRead.hasRemaining());
			}
			client.receiveData(initialBufferSize, timeout).listen(
				new IOUtil.RecursiveAsyncSupplierListener<ByteBuffer>((result, that) -> {
					if (result == null) {
						res.unblockError(new IOException("End of data after " + io.getPosition()
							+ " bytes read, while waiting for an end marker byte " + endMarker));
						return;
					}
					new Task.Cpu<Void, NoException>("TCPClient.readUntil", Task.PRIORITY_RATHER_IMPORTANT) {
						@Override
						public Void run() {
							while (result.hasRemaining()) {
								byte b = result.get();
								if (b == endMarker) {
									if (result.hasRemaining())
										remainingRead = result;
									io.seekSync(SeekType.FROM_BEGINNING, 0);
									res.unblockSuccess(io);
									return null;
								}
								io.write(b);
							}
							client.receiveData(initialBufferSize, timeout).listen(that);
							return null;
						}
					}.start();
				}, res, null));
			return res;
		}
		
		/** Wait for data until connection is closed.
		 * When some data is available, the given listener is called.
		 * If the listener does not fully consumed the buffer, when new data is received
		 * the new data is appended to the remaining bytes before to call again the listener,
		 * except if the parameter keepRemainingData is false.
		 * Once this method has been called, no other read method can be called because it
		 * will conflict by trying to read concurrently.
		 * Important note: when end of stream is reached, the listener is called with a null value.
		 */
		public void readForEver(int bufferSize, int timeout, Consumer<ByteBuffer> listener, boolean keepRemainingData) {
			if (remainingRead != null) {
				ByteBuffer data = remainingRead;
				remainingRead = null;
				call(null, data, listener, bufferSize, timeout, keepRemainingData);
				return;
			}
			client.receiveData(bufferSize, timeout).onDone((data) -> {
				ByteBuffer rem = remainingRead;
				remainingRead = null;
				call(rem, data, listener, bufferSize, timeout, keepRemainingData);
			}, (error) -> { }, (cancel) -> { });
		}
		
		private void call(
			ByteBuffer remainingData, ByteBuffer newData, Consumer<ByteBuffer> listener,
			int bufferSize, int timeout, boolean keepRemainingData
		) {
			new Task.Cpu.FromRunnable("Call TCPClient data receiver", Task.PRIORITY_NORMAL, () -> {
				ByteBuffer data;
				if (remainingData == null)
					data = newData;
				else {
					if (newData != null) {
						data = ByteBuffer.allocate(remainingData.remaining() + newData.remaining());
						data.put(remainingData);
						data.put(newData);
						data.flip();
					} else
						data = remainingData;
				}
				try {
					listener.accept(data);
				} catch (Throwable t) {
					client.logger.error("Exception thrown by data listener", t);
					return;
				}
				if (data != null && data.hasRemaining() && keepRemainingData)
					remainingRead = data;
				if (data == null && newData == null)
					return; // end of data
				client.receiveData(bufferSize, timeout).onDone((d) -> {
					ByteBuffer rem = remainingRead;
					remainingRead = null;
					call(rem, d, listener, bufferSize, timeout, keepRemainingData);
				}, (error) -> { }, (cancel) -> { });
			}).start();
		}
	}
	
	private static final String BUFFERED_RECEIVER_ATTRIBUTE = "TCPClient.BufferedReceiver";
	
	/**
	 * Return a BufferedReceiver associated with this client.
	 * The BufferedReceiver is instantiated on the first call to this method.
	 * Once this method has been called, the method receiveData MUST NOT be used anymore,
	 * because some data may have been already receivedand bufferized into the BufferedReceiver.
	 */
	public BufferedReceiver getReceiver() {
		BufferedReceiver br = (BufferedReceiver)getAttribute(BUFFERED_RECEIVER_ATTRIBUTE);
		if (br == null) {
			br = new BufferedReceiver(this);
			setAttribute(BUFFERED_RECEIVER_ATTRIBUTE, br);
		}
		return br;
	}
	
	private TurnArray<Pair<ByteBuffer, Async<IOException>>> toSend = new TurnArray<>();
	private NetworkManager.Sender sender = new NetworkManager.Sender() {
		@Override
		public void channelClosed() {
			TCPClient.this.channelClosed();
		}
		
		@Override
		public void sendTimeout() {
		}
		
		@Override
		public void readyToSend() {
			logger.debug("Socket ready for sending, sending...");
			boolean needsMore = false;
			while (true) {
				Pair<ByteBuffer, Async<IOException>> p;
				synchronized (toSend) {
					if (toSend.isEmpty()) {
						if (dataToSendProvider != null) {
							p = new Pair<>(dataToSendProvider.get(), dataToSendSP);
							toSend.add(p);
							dataToSendProvider = null;
							dataToSendSP = null;
						} else
							break;
					} else
						p = toSend.getFirst();
				}
				if (logger.debug())
					logger.debug("Sending up to " + p.getValue1().remaining() + " bytes to " + channel);
				if (p.getValue1().remaining() == 0) {
					if (p.getValue2() != null)
						p.getValue2().unblock();
					synchronized (toSend) {
						if (!toSend.isEmpty())
							toSend.removeFirst();
					}
					continue;
				}
				int nb;
				try {
					nb = channel.write(p.getValue1());
				} catch (IOException e) {
					// error while sending data, just skip it
					if (p.getValue2() != null) p.getValue2().error(e);
					synchronized (toSend) {
						if (!toSend.isEmpty())
							toSend.removeFirst();
					}
					continue;
				}
				if (logger.debug())
					logger.debug(nb + " bytes sent to " + channel);
				if (nb == 0) {
					// cannot write anymore
					needsMore = true;
					break;
				}
				if (!p.getValue1().hasRemaining()) {
					if (p.getValue2() != null)
						p.getValue2().unblock();
					synchronized (toSend) {
						if (!toSend.isEmpty())
							toSend.removeFirst();
					}
				}
			}
			if (!needsMore) {
				// no more data to send
				return;
			}
			// still something to write, we need to register to the network manager
			manager.register(channel, SelectionKey.OP_WRITE, sender, 0);
		}
	};

	/**
	 * Send data. Data are queued until they can be sent, so multiple calls to this method can be done
	 * without waiting for the previous message to be sent.
	 */
	@Override
	public IAsync<IOException> send(ByteBuffer data) {
		if (logger.debug())
			logger.debug("Sending data: " + data.remaining());
		if (manager.getDataLogger().trace()) {
			if (data.hasArray()) {
				StringBuilder s = new StringBuilder(data.remaining() * 4);
				s.append("TCPClient: Data to send to server:\r\n");
				DebugUtil.dumpHex(s, data.array(), data.arrayOffset() + data.position(), data.remaining());
				manager.getDataLogger().trace(s.toString());
			}
		}
		if (data.remaining() == 0)
			return new Async<>(true);
		Async<IOException> sp = new Async<>();
		synchronized (toSend) {
			toSend.addLast(new Pair<>(data, sp));
			if (toSend.size() == 1 && dataToSendProvider == null)
				manager.register(channel, SelectionKey.OP_WRITE, sender, 0);
		}
		return sp;
	}
	
	@Override
	public void newDataToSendWhenPossible(Supplier<ByteBuffer> dataProvider, Async<IOException> sp) {
		synchronized (this) {
			Supplier<ByteBuffer> prevProvider = dataToSendProvider;
			Async<IOException> prevSP = dataToSendSP;
			dataToSendProvider = dataProvider;
			dataToSendSP = sp;
			if (toSend.isEmpty() && prevProvider == null)
				manager.register(channel, SelectionKey.OP_WRITE, sender, 0);
			if (prevProvider != null)
				prevSP.unblock();
		}
	}
	
	@Override
	public void close() {
		if (closed) return;
		logger.debug("Close client");
		try { channel.close(); }
		catch (Throwable e) { /* ignore */ }
		channelClosed();
		closed = true;
	}
	
	@Override
	public boolean isClosed() {
		return closed;
	}
	
	@Override
	public String toString() {
		return "TCPClient [" + channel + "]";
	}
	
}
