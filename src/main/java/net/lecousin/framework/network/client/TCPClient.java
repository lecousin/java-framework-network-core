package net.lecousin.framework.network.client;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.lecousin.framework.application.Application;
import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.collections.TurnArray;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.concurrent.util.PartialAsyncConsumer;
import net.lecousin.framework.event.SimpleEvent;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.IOUtil;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.memory.ByteArrayCache;
import net.lecousin.framework.mutable.MutableInteger;
import net.lecousin.framework.network.AbstractAttributesContainer;
import net.lecousin.framework.network.NetworkManager;
import net.lecousin.framework.network.SocketOptionValue;
import net.lecousin.framework.network.TCPRemote;
import net.lecousin.framework.util.DebugUtil;
import net.lecousin.framework.util.Pair;

/**
 * TCP client, connected to a server using TCP protocol (using sockets).
 * It uses the {@link NetworkManager} to make asynchronous operations.<br/>
 */
public class TCPClient extends AbstractAttributesContainer implements Closeable, TCPRemote {
	
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
	private SimpleEvent onclosed = new SimpleEvent();
	private Supplier<List<ByteBuffer>> dataToSendProvider = null;
	private Async<IOException> dataToSendSP = null;
	private ByteArrayCache bufferCache = ByteArrayCache.getInstance();

	@Override
	public SocketAddress getLocalAddress() throws IOException {
		return channel != null ? channel.getLocalAddress() : null;
	}
	
	@Override
	public SocketAddress getRemoteAddress() throws IOException {
		return channel != null ? channel.getRemoteAddress() : null;
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
			if (logger.debug())
				logger.debug("Client connected to " + channel);
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
				logger.debug("Client received " + buffer.remaining() + " bytes from " + channel);
				if (reading.isDone()) logger.error("Received data but this was not expected");
			}
			reading.unblockSuccess(buffer);
		}
		
		@Override
		public void receiveError(IOException error, ByteBuffer buffer) {
			logger.debug("Client receive error", error);
			reading.unblockError(error);
			if (buffer != null && buffer.hasArray())
				bufferCache.free(buffer.array());
		}
		
		@Override
		public ByteBuffer allocateReceiveBuffer() {
			return ByteBuffer.wrap(bufferCache.get(expectedBytes, true));
		}
		
		@Override
		public void endOfInput(ByteBuffer buffer) {
			logger.debug("Client end of input");
			buffer.flip();
			endOfInput = true;
			reading.unblockSuccess(buffer.hasRemaining() ? buffer : null);
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
		if (channel == null || !channel.isConnected()) return new AsyncSupplier<>(null, new ClosedChannelException());
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
			byte[] buf = client.bufferCache.get(nbBytes, false);
			MutableInteger pos = new MutableInteger(0);
			if (remainingRead != null) {
				int len = Math.min(nbBytes, remainingRead.remaining());
				remainingRead.get(buf, 0, len);
				if (!remainingRead.hasRemaining()) {
					client.bufferCache.free(remainingRead);
					remainingRead = null;
				}
				if (len == nbBytes) {
					res.unblockSuccess(buf);
					return res;
				}
				pos.set(len);
			}
			client.receiveData(nbBytes - pos.get(), timeout).listen(
				new IOUtil.RecursiveAsyncSupplierListener<ByteBuffer>((result, that) -> {
					if (result == null) {
						unexpectedEnd(res, pos.get(), nbBytes + " byte(s)");
						return;
					}
					Task.cpu("Handle received data from TCPClient", Task.Priority.RATHER_IMPORTANT, t -> {
						int len = Math.min(result.remaining(), nbBytes - pos.get());
						result.get(buf, pos.get(), len);
						if (result.hasRemaining())
							remainingRead = result;
						else
							client.bufferCache.free(result);
						pos.add(len);
						if (pos.get() == nbBytes) {
							res.unblockSuccess(buf);
							return null;
						}
						client.receiveData(nbBytes - pos.get(), timeout).listen(that);
						return null;
					}).start();
				}, res, null));
			return res;
		}
		
		/**
		 * Wait for the given number of bytes to be consumed. If this number of bytes cannot be reached, an error is raised.
		 */
		public Async<IOException> skipBytes(int nbBytes, int timeout) {
			Async<IOException> res = new Async<>();
			MutableInteger pos = new MutableInteger(0);
			if (remainingRead != null) {
				int len = Math.min(nbBytes, remainingRead.remaining());
				remainingRead.position(remainingRead.position() + len);
				if (!remainingRead.hasRemaining()) {
					client.bufferCache.free(remainingRead);
					remainingRead = null;
				}
				if (len == nbBytes) {
					res.unblock();
					return res;
				}
				pos.set(len);
			}
			client.receiveData(Math.min(nbBytes - pos.get(), 65536), timeout).listen(
				new IOUtil.RecursiveAsyncSupplierListener<ByteBuffer>((result, that) -> {
					if (result == null) {
						unexpectedEnd(res, pos.get(), nbBytes + " byte(s)");
						return;
					}
					Task.cpu("Handle received data from TCPClient", Task.Priority.RATHER_IMPORTANT, t -> {
						int len = Math.min(result.remaining(), nbBytes - pos.get());
						result.position(result.position() + len);
						if (result.hasRemaining())
							remainingRead = result;
						else
							client.bufferCache.free(result);
						pos.add(len);
						if (pos.get() == nbBytes) {
							res.unblock();
							return null;
						}
						client.receiveData(Math.min(nbBytes - pos.get(), 65536), timeout).listen(that);
						return null;
					}).start();
				}, res, null));
			return res;
		}
		
		/**
		 * Receive data until the given byte is read. This can be useful for protocols that have an end of message marker,
		 * or protocols that send lines of text.
		 */
		@SuppressWarnings("java:S2095") // ByteArrayIO is returned so do not close it
		public AsyncSupplier<ByteArrayIO, IOException> readUntil(byte endMarker, int initialBufferSize, int timeout) {
			AsyncSupplier<ByteArrayIO, IOException> res = new AsyncSupplier<>();
			ByteArrayIO io = new ByteArrayIO(initialBufferSize, "");
			if (remainingRead != null) {
				do {
					byte b = remainingRead.get();
					if (b == endMarker) {
						io.seekSync(SeekType.FROM_BEGINNING, 0);
						res.unblockSuccess(io);
						if (!remainingRead.hasRemaining()) {
							client.bufferCache.free(remainingRead);
							remainingRead = null;
						}
						return res;
					}
					io.write(b);
				} while (remainingRead.hasRemaining());
			}
			client.receiveData(initialBufferSize, timeout).listen(
				new IOUtil.RecursiveAsyncSupplierListener<ByteBuffer>((result, that) -> {
					if (result == null) {
						unexpectedEnd(res, io.getPosition(), "an end marker byte " + endMarker);
						return;
					}
					Task.cpu("TCPClient.readUntil", Task.Priority.RATHER_IMPORTANT, t -> {
						while (result.hasRemaining()) {
							byte b = result.get();
							if (b == endMarker) {
								if (result.hasRemaining())
									remainingRead = result;
								else
									client.bufferCache.free(result);
								io.seekSync(SeekType.FROM_BEGINNING, 0);
								res.unblockSuccess(io);
								return null;
							}
							io.write(b);
						}
						client.bufferCache.free(result);
						client.receiveData(initialBufferSize, timeout).listen(that);
						return null;
					}).start();
				}, res, null));
			return res;
		}
		
		/** Receive data from client and consume it. */
		public IAsync<IOException> consume(PartialAsyncConsumer<ByteBuffer, IOException> consumer, int bufferSize, int timeout) {
			Async<IOException> result = new Async<>();
			consume(consumer, result, bufferSize, timeout);
			return result;
		}
		
		/** Receive data from client and consume it. */
		public void consume(PartialAsyncConsumer<ByteBuffer, IOException> consumer, Async<IOException> result, int bufferSize, int timeout) {
			if (remainingRead != null) {
				consumer.consume(remainingRead).onDone(end -> {
					if (!end.booleanValue()) {
						remainingRead = null;
						consume(consumer, result, bufferSize, timeout);
					} else {
						if (!remainingRead.hasRemaining())
							remainingRead = null;
						else if (!remainingRead.isReadOnly())
							remainingRead = remainingRead.asReadOnlyBuffer();
						result.unblock();
					}
				}, result);
				return;
			}
			client.receiveData(bufferSize, timeout).thenStart("Consume data from client", Task.Priority.NORMAL, buffer -> {
				if (buffer == null) {
					result.error(new EOFException("Unexpected end of data from server"));
					return;
				}
				consumer.consume(buffer).onDone(end -> {
					if (!end.booleanValue()) {
						consume(consumer, result, bufferSize, timeout);
					} else {
						if (buffer.hasRemaining())
							remainingRead = buffer.asReadOnlyBuffer();
						result.unblock();
					}
				}, result);
			}, result);
		}
		
		private static void unexpectedEnd(IAsync<IOException> result, long nbRead, String waitingFor) {
			result.error(new EOFException("End of data after " + nbRead + "byte(s) while waiting for " + waitingFor));
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
			client.receiveData(bufferSize, timeout).onDone(data -> {
				ByteBuffer rem = remainingRead;
				remainingRead = null;
				call(rem, data, listener, bufferSize, timeout, keepRemainingData);
			}, error -> { }, cancel -> { });
		}
		
		private void call(
			ByteBuffer remainingData, ByteBuffer newData, Consumer<ByteBuffer> listener,
			int bufferSize, int timeout, boolean keepRemainingData
		) {
			Task.cpu("Call TCPClient data receiver", Task.Priority.NORMAL, task -> {
				ByteBuffer data;
				if (remainingData == null)
					data = newData;
				else {
					if (newData != null) {
						data = ByteBuffer.wrap(client.bufferCache.get(remainingData.remaining() + newData.remaining(), true));
						data.put(remainingData);
						data.put(newData);
						data.flip();
					} else {
						data = remainingData;
					}
				}
				try {
					listener.accept(data);
				} catch (Exception t) {
					client.logger.error("Exception thrown by data listener", t);
					return null;
				}
				if (data != null && data.hasRemaining() && keepRemainingData)
					remainingRead = data.asReadOnlyBuffer();
				if (data == null && newData == null)
					return null; // end of data
				client.receiveData(bufferSize, timeout).onDone(d -> {
					ByteBuffer rem = remainingRead;
					remainingRead = null;
					call(rem, d, listener, bufferSize, timeout, keepRemainingData);
				}, error -> { }, cancel -> { });
				return null;
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
	private boolean sending = false;
	private int lastSendTimeout = 0;
	private NetworkManager.Sender sender = new NetworkManager.Sender() {
		@Override
		public void channelClosed() {
			TCPClient.this.channelClosed();
		}
		
		@Override
		public void sendTimeout() {
			close();
		}
		
		@Override
		public void readyToSend() {
			if (logger.debug())
				logger.debug("Socket ready for sending to " + channel + ", sending...");
			boolean needsMore = false;
			while (true) {
				Pair<ByteBuffer, Async<IOException>> p;
				synchronized (toSend) {
					sending = true;
					if (toSend.isEmpty()) {
						if (dataToSendProvider != null) {
							Iterator<ByteBuffer> it = dataToSendProvider.get().iterator();
							if (!it.hasNext()) {
								dataToSendSP.unblock();
								dataToSendProvider = null;
								dataToSendSP = null;
								sending = false;
								break;
							}
							do {
								ByteBuffer data = it.next();
								toSend.add(new Pair<>(data, it.hasNext() ? null : dataToSendSP));
							} while (it.hasNext());
							dataToSendProvider = null;
							dataToSendSP = null;
						} else {
							sending = false;
							break;
						}
					}
					p = toSend.getFirst();
				}
				if (logger.debug())
					logger.debug("Sending up to " + p.getValue1().remaining() + " bytes to " + channel);
				if (p.getValue1().remaining() == 0) {
					synchronized (toSend) {
						if (!toSend.isEmpty())
							toSend.removeFirst();
					}
					bufferCache.free(p.getValue1());
					if (p.getValue2() != null)
						p.getValue2().unblock();
					continue;
				}
				int nb;
				try {
					nb = channel.write(p.getValue1());
				} catch (Exception e) {
					// error while sending data, skip all data
					if (logger.debug())
						logger.debug("Data not sent to " + channel, e);
					synchronized (toSend) {
						while (!toSend.isEmpty()) {
							Async<IOException> sp = toSend.removeFirst().getValue2();
							if (sp != null) sp.error(IO.error(e));
						}
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
					synchronized (toSend) {
						if (!toSend.isEmpty())
							toSend.removeFirst();
					}
					bufferCache.free(p.getValue1());
					if (p.getValue2() != null)
						p.getValue2().unblock();
				}
			}
			synchronized (toSend) {
				sending = false;
			}
			if (!needsMore) {
				// no more data to send
				return;
			}
			// still something to write, we need to register to the network manager
			manager.register(channel, SelectionKey.OP_WRITE, sender, lastSendTimeout);
		}
	};

	/**
	 * Send data. Data are queued until they can be sent, so multiple calls to this method can be done
	 * without waiting for the previous message to be sent.
	 */
	@Override
	public IAsync<IOException> send(List<ByteBuffer> dataList, int timeout) {
		Iterator<ByteBuffer> it = dataList.iterator();
		if (!it.hasNext())
			return new Async<>(true);
		synchronized (toSend) {
			if (channel == null || !channel.isConnected())
				return new Async<>(new ClosedChannelException());
			Async<IOException> sp = new Async<>();
			boolean allEmpty = true;
			boolean firstSend = toSend.isEmpty();
			do {
				ByteBuffer data = it.next();
				if (data.remaining() == 0) {
					bufferCache.free(data);
					continue;
				}
				allEmpty = false;
				if (logger.debug())
					logger.debug("Sending data to " + channel + ": " + data.remaining());
				if (data.hasArray() && manager.getDataLogger().trace()) {
					StringBuilder s = new StringBuilder(data.remaining() * 4);
					s.append("TCPClient: Data to send to server:\r\n");
					DebugUtil.dumpHex(s, data.array(), data.arrayOffset() + data.position(), data.remaining());
					manager.traceData(s);
				}
				toSend.addLast(new Pair<>(data, it.hasNext() ? null : sp));
			} while (it.hasNext());
			if (allEmpty) {
				sp.unblock();
				return sp;
			}
			lastSendTimeout = timeout;
			if (!sending && firstSend && dataToSendProvider == null)
				manager.register(channel, SelectionKey.OP_WRITE, sender, timeout);
			return sp;
		}
	}
	
	@Override
	public void newDataToSendWhenPossible(Supplier<List<ByteBuffer>> dataProvider, Async<IOException> sp, int timeout) {
		synchronized (this) {
			Supplier<List<ByteBuffer>> prevProvider = dataToSendProvider;
			Async<IOException> prevSP = dataToSendSP;
			dataToSendProvider = dataProvider;
			dataToSendSP = sp;
			if (toSend.isEmpty() && prevProvider == null)
				manager.register(channel, SelectionKey.OP_WRITE, sender, timeout);
			if (prevProvider != null)
				prevSP.unblock();
		}
	}
	
	// TODO overwrite asConsumer because we are already able to keep buffers in toSend
	
	@Override
	public void close() {
		if (closed) return;
		logger.debug("Close client");
		try { channel.close(); }
		catch (Exception e) { /* ignore */ }
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
