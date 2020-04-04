package net.lecousin.framework.network.server;

import java.io.Closeable;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Supplier;

import net.lecousin.framework.concurrent.CancelException;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.async.LockPoint;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.concurrent.threads.Task.Priority;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.memory.ByteArrayCache;
import net.lecousin.framework.network.NetworkManager;
import net.lecousin.framework.network.server.protocol.ServerProtocol;
import net.lecousin.framework.network.server.protocol.ServerProtocolCommonAttributes;
import net.lecousin.framework.util.DebugUtil;
import net.lecousin.framework.util.Pair;

/** TCP Server.<br/>
 * A {@link ServerProtocol} must be associated to handle clients.<br/>
 * A server can listen to several IP addresses and ports.<br/>
 * Each connected client is represented by a {@link TCPServerClient}.<br/>
 * It uses the {@link NetworkManager} to perform asynchronous operations.
 */
public class TCPServer extends AbstractServer<ServerSocketChannel, TCPServer.ServerChannel> {

	protected ServerProtocol protocol;
	protected ArrayList<Client> clients = new ArrayList<>();
	protected ByteArrayCache bufferCache = ByteArrayCache.getInstance();
	
	public ServerProtocol getProtocol() {
		return protocol;
	}
	
	public void setProtocol(ServerProtocol protocol) {
		this.protocol = protocol;
	}
	
	@Override
	public void unbindAll() {
		super.unbindAll();
		ArrayList<Client> list;
		synchronized (clients) {
			list = new ArrayList<>(clients);
			clients.clear();
		}
		for (Client client : list)
			client.close();
	}
	
	/** Return a list of currently connected clients. */
	@SuppressWarnings("java:S1319") // return ArrayList instead of List
	public ArrayList<Closeable> getConnectedClients() {
		ArrayList<Closeable> list;
		synchronized (clients) {
			list = new ArrayList<>(clients);
		}
		return list;
	}
	
	/** Listen to the given address, with the given backlog.
	 * The backlog parameter is the maximum number of pending connections on the socket.
	 */
	public AsyncSupplier<SocketAddress, IOException> bind(SocketAddress local, int backlog) {
		AsyncSupplier<SocketAddress, IOException> result = new AsyncSupplier<>();
		Task.cpu("Bind server", Task.Priority.IMPORTANT, t -> {
			ServerSocketChannel channel;
			try {
				channel = ServerSocketChannel.open();
				channel.configureBlocking(false);
				channel.bind(local, backlog);
			} catch (IOException e) {
				result.error(e);
				return null;
			}
			ServerChannel sc = new ServerChannel(channel);
			AsyncSupplier<SelectionKey, IOException> accept = manager.register(channel, SelectionKey.OP_ACCEPT, sc, 0);
			finalizeBinding(accept, sc, channel, result);
			return null;
		}).start();
		return result;
	}
	
	/** Internal listening channel. */
	protected class ServerChannel extends AbstractServer.AbstractServerChannel<ServerSocketChannel> implements NetworkManager.Server {
		private ServerChannel(ServerSocketChannel channel) {
			super(channel);
		}
		
		@SuppressWarnings("resource")
		@Override
		public void newClient(SocketChannel client) {
			Client c = new Client(client);
			synchronized (clients) {
				if (!channel.isOpen()) return;
				clients.add(c);
			}
			int recvTimeout = protocol.startProtocol(c.publicInterface);
			if (recvTimeout >= 0)
				try { c.publicInterface.waitForData(recvTimeout); }
				catch (ClosedChannelException e) { c.close(); }
		}

		@Override
		public void channelClosed() {
			if (manager.getLogger().info())
				manager.getLogger().info("TCP server closed on channel " + channel.toString());
		}
		
		
		@Override
		public void acceptError(IOException error) {
			if (manager.getLogger().info())
				manager.getLogger().info("Error accepting client on channel " + channel.toString(), error);
		}
	}
	
	class Client implements NetworkManager.TCPReceiver, NetworkManager.Sender, Closeable {

		public Client(SocketChannel channel) {
			this.channel = channel;
			publicInterface = new TCPServerClient(this);
			publicInterface.setAttribute(ServerProtocolCommonAttributes.ATTRIBUTE_CONNECTION_ESTABLISHED_NANOTIME,
				Long.valueOf(System.nanoTime()));
		}
		
		SocketChannel channel;
		private TCPServerClient publicInterface;
		private LinkedList<Pair<ByteBuffer,Async<IOException>>> outputBuffers = new LinkedList<>();
		private int lastSendTimeout = 0;
		private boolean waitToSend = false;
		private boolean closeAfterLastOutput = false;
		private Supplier<List<ByteBuffer>> dataToSendProvider = null;
		private Async<IOException> dataToSendSP = null;
		private LockPoint<NoException> sendLock = new LockPoint<>();
		
		public TCPServer getServer() {
			return TCPServer.this;
		}
		
		@Override
		public void close() {
			synchronized (clients) {
				clients.remove(this);
			}
			SocketChannel ch;
			synchronized (this) {
				if (channel == null) return;
				ch = channel;
				channel = null;
			}
			if (manager.getLogger().debug())
				manager.getLogger().debug("Client closed: " + ch);
			if (ch.isOpen())
				try { ch.close(); }
				catch (Exception e) { /* ignore */ }
			for (AutoCloseable c : publicInterface.toClose)
				try { c.close(); }
				catch (Exception e) { /* ignore */ }
			while (!publicInterface.pending.isEmpty()) {
				IAsync<?> sp = publicInterface.pending.pollFirst();
				if (!sp.isDone()) sp.cancel(new CancelException("Client connection closed"));
			}
			publicInterface = null;
			outputBuffers = null;
		}
		
		public boolean isClosed() {
			return channel == null;
		}
		
		@Override
		public void sendTimeout() {
			// nothing, client will be closed
		}
		
		public synchronized void waitForData(int timeout) throws ClosedChannelException {
			if (channel == null) throw new ClosedChannelException();
			manager.register(channel, SelectionKey.OP_READ, this, timeout);
		}
		
		@Override
		public ByteBuffer allocateReceiveBuffer() {
			return ByteBuffer.wrap(bufferCache.get(protocol.getInputBufferSize(), true));
		}
		
		@Override
		public void received(ByteBuffer buffer) {
			protocol.dataReceivedFromClient(publicInterface, buffer);
		}
		
		@Override
		public void endOfInput(ByteBuffer buffer) {
			if (buffer != null && buffer.hasArray())
				bufferCache.free(buffer.array());
			// the client closed the channel
			close();
		}
		
		@Override
		public void receiveError(IOException error, ByteBuffer buffer) {
			if (buffer != null && buffer.hasArray())
				bufferCache.free(buffer.array());
		}
		
		Async<IOException> send(List<ByteBuffer> buf, int timeout, boolean closeAfter) throws ClosedChannelException {
			sendLock.lock();
			try {
				if (channel == null || !channel.isConnected()) throw new ClosedChannelException();
				// ask the protocol to do any needed processing before sending the data
				LinkedList<ByteBuffer> buffers = protocol.prepareDataToSend(publicInterface, buf);
				Async<IOException> sp = new Async<>();
				boolean waitBefore = waitToSend;
				if (!waitToSend) {
					// we can start sending data right away
					send(buffers, closeAfter, sp);
					if (sp.isDone())
						return sp;
					waitToSend = true;
				}
				while (!buffers.isEmpty()) {
					ByteBuffer b = buffers.removeFirst();
					outputBuffers.add(new Pair<>(b, buffers.isEmpty() ? sp : null));
				}
				closeAfterLastOutput = closeAfter;
				lastSendTimeout = timeout;
				if (channel == null || !channel.isConnected())
					return new Async<>(new ClosedChannelException());
				if (!waitBefore && dataToSendProvider == null)
					manager.register(channel, SelectionKey.OP_WRITE, this, timeout);
				return sp;
			} finally {
				sendLock.unlock();
			}
		}
		
		private void send(LinkedList<ByteBuffer> buffers, boolean closeAfter, Async<IOException> sp) {
			ByteBuffer buffer = null;
			while (true) {
				if (buffer == null) {
					if (buffers.isEmpty())
						break;
					buffer = buffers.removeFirst();
				}
				if (manager.getDataLogger().trace())
					traceSendingData(buffer);
				int nb;
				try { nb = channel.write(buffer); }
				catch (IOException e) {
					// error while writing
					close();
					sp.error(e);
					return;
				}
				if (manager.getLogger().debug()) manager.getLogger().debug(nb + " bytes sent on " + channel);
				if (!buffer.hasRemaining()) {
					// done with this buffer
					bufferCache.free(buffer);
					buffer = null;
					if (buffers.isEmpty()) {
						// no more buffer
						if (closeAfter) close();
						sp.unblock();
						return;
					}
					continue;
				}
				if (nb == 0) break; // cannot write anymore
			}
			if (buffer != null) buffers.addFirst(buffer);
		}
		
		private void traceSendingData(ByteBuffer buffer) {
			StringBuilder s = new StringBuilder(128 + buffer.remaining() * 5);
			s.append("Sending ").append(buffer.remaining()).append(" bytes to client:\r\n");
			DebugUtil.dumpHex(s, buffer);
			manager.getDataLogger().trace(s.toString());
		}
		
		private void unblockTask(Async<?> toUnblock) {
			Task.cpu("Send done",  Priority.NORMAL, t -> {
				toUnblock.unblock();
				return null;
			}).start();
		}
		
		@Override
		@SuppressWarnings("java:S3776") // complexity
		public void readyToSend() {
			sendLock.lock();
			try {
				do {
					if (outputBuffers == null || channel == null) return;
					if (outputBuffers.isEmpty() && dataToSendProvider != null) {
						Supplier<List<ByteBuffer>> provider = dataToSendProvider;
						Async<IOException> sp = dataToSendSP;
						dataToSendProvider = null;
						dataToSendSP = null;
						LinkedList<ByteBuffer> buffers = protocol.prepareDataToSend(publicInterface, provider.get());
						if (buffers.isEmpty()) {
							unblockTask(sp);
						} else {
							for (Iterator<ByteBuffer> it = buffers.iterator(); it.hasNext(); ) {
								ByteBuffer b = it.next();
								outputBuffers.add(new Pair<>(b, it.hasNext() ? null : sp));
							}
						}
					}
					boolean canContinue = true;
					while (outputBuffers != null && !outputBuffers.isEmpty()) {
						try {
							if (sendNextBuffer() == 0) {
								// cannot write anymore
								canContinue = false;
								break;
							}
						} catch (IOException e) {
							manager.getLogger().error("Error sending data to client, close it", e);
							close();
							return;
						}
	
					}
					if (outputBuffers == null) return;
					if (outputBuffers.isEmpty()) {
						// we are done with all data to be sent
						if (closeAfterLastOutput) {
							close();
							return;
						}
						if (dataToSendProvider == null) {
							waitToSend = !canContinue;
							return;
						} else if (canContinue) {
							continue;
						}
					}
					// still something to write, we need to register to the network manager
					if (manager.getLogger().debug())
						manager.getLogger().debug("Register to NetworkManager to send data: "
							+ outputBuffers.size() + " buffer(s) remaining");
					manager.register(channel, SelectionKey.OP_WRITE, Client.this, lastSendTimeout);
					break;
				} while (true);
			} finally {
				sendLock.unlock();
			}
		}
		
		private int sendNextBuffer() throws IOException {
			Pair<ByteBuffer,Async<IOException>> toWrite = outputBuffers.getFirst();
			int nb;
			do {
				try { nb = channel.write(toWrite.getValue1()); }
				catch (IOException e) {
					// error while writing
					outputBuffers.removeFirst();
					if (toWrite.getValue2() != null)
						toWrite.getValue2().error(e);
					while (!outputBuffers.isEmpty()) {
						toWrite = outputBuffers.removeFirst();
						if (toWrite.getValue2() != null)
							toWrite.getValue2().error(e);
					}
					throw e;
				}
				if (manager.getLogger().debug())
					manager.getLogger().debug(nb + " bytes sent on " + channel);
				if (!toWrite.getValue1().hasRemaining()) {
					// we are done with this buffer
					bufferCache.free(toWrite.getValue1());
					outputBuffers.removeFirst();
					if (toWrite.getValue2() != null)
						unblockTask(toWrite.getValue2());
					break;
				}
			} while (nb > 0);
			return nb;
		}
		
		public void newDataToSendWhenPossible(Supplier<List<ByteBuffer>> dataProvider, Async<IOException> sp, int timeout) {
			sendLock.lock();
			Async<IOException> prevSP;
			try {
				if (channel == null || !channel.isConnected() || outputBuffers == null) {
					sp.error(new ClosedChannelException());
					return;
				}
				Supplier<List<ByteBuffer>> prevProvider = dataToSendProvider;
				prevSP = dataToSendSP;
				dataToSendProvider = dataProvider;
				dataToSendSP = sp;
				if (!waitToSend && outputBuffers.isEmpty() && prevProvider == null)
					manager.register(channel, SelectionKey.OP_WRITE, Client.this, timeout);
			} finally {
				sendLock.unlock();
			}
			if (prevSP != null)
				prevSP.cancel(new CancelException("Send cancelled: new data to send provided"));
		}
		
		@Override
		public void channelClosed() {
			close();
		}
		
		@Override
		public String toString() {
			return "Connected client[" + channel + "]";
		}
		
	}
	
}
