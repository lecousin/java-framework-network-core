package net.lecousin.framework.network.server;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.LinkedList;

import net.lecousin.framework.application.Application;
import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.CancelException;
import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.synch.ISynchronizationPoint;
import net.lecousin.framework.concurrent.synch.SynchronizationPoint;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.network.NetworkManager;
import net.lecousin.framework.network.server.protocol.ServerProtocol;
import net.lecousin.framework.util.DebugUtil;
import net.lecousin.framework.util.Pair;
import net.lecousin.framework.util.Provider;

/** TCP Server.<br/>
 * A {@link ServerProtocol} must be associated to handle clients.<br/>
 * A server can listen to several IP addresses and ports.<br/>
 * Each connected client is represented by a {@link TCPServerClient}.<br/>
 * It uses the {@link NetworkManager} to perform asynchronous operations.
 */
public class TCPServer implements Closeable {

	/** Constructor. */
	public TCPServer() {
		app = LCCore.getApplication();
		manager = NetworkManager.get(app);
		app.toClose(this);
	}
	
	protected Application app;
	protected NetworkManager manager;
	protected ServerProtocol protocol;
	protected ArrayList<ServerChannel> channels = new ArrayList<>();
	protected ArrayList<Client> clients = new ArrayList<>();
	
	public ServerProtocol getProtocol() {
		return protocol;
	}
	
	public void setProtocol(ServerProtocol protocol) {
		this.protocol = protocol;
	}
	
	/** Return the list of addresses this server listens to. */
	public ArrayList<InetSocketAddress> getLocalAddresses() {
		ArrayList<InetSocketAddress> addresses = new ArrayList<>(channels.size());
		for (ServerChannel channel : channels)
			try { addresses.add((InetSocketAddress)channel.channel.getLocalAddress()); }
			catch (Throwable e) { /* ignore */ }
		return addresses;
	}
	
	@Override
	public void close() {
		unbindAll();
		app.closed(this);
	}
	
	/** Listen to the given address, with the given backlog.
	 * The backlog parameter is the maximum number of pending connections on the socket.
	 */
	@SuppressWarnings("resource")
	public SocketAddress bind(SocketAddress local, int backlog) throws IOException {
		ServerSocketChannel channel = ServerSocketChannel.open();
		channel.configureBlocking(false);
		channel.bind(local, backlog);
		ServerChannel sc = new ServerChannel(channel);
		channels.add(sc);
		manager.register(channel, SelectionKey.OP_ACCEPT, sc, 0);
		local = channel.getLocalAddress();
		if (NetworkManager.logger.isInfoEnabled())
			NetworkManager.logger.info("New TCP server listening on " + local.toString());
		return local;
	}
	
	/** Stop listening to all ports and addresses. */
	public void unbindAll() {
		for (ServerChannel channel : channels) {
			if (NetworkManager.logger.isInfoEnabled())
				NetworkManager.logger.info("Closing TCP server: " + channel.channel.toString());
			try { channel.channel.close(); }
			catch (IOException e) {
				if (NetworkManager.logger.isErrorEnabled())
					NetworkManager.logger.error("Error closing TCP server", e);
			}
		}
		NetworkManager.get().wakeup();
		channels.clear();
		synchronized (clients) {
			for (Client client : clients)
				client.close();
			clients.clear();
		}
	}
	
	private class ServerChannel implements NetworkManager.Server {
		private ServerChannel(ServerSocketChannel channel) {
			this.channel = channel;
		}
		
		private ServerSocketChannel channel;

		@SuppressWarnings("resource")
		@Override
		public void newClient(SocketChannel client) {
			Client c = new Client(client);
			synchronized (clients) {
				if (!channel.isOpen()) return;
				clients.add(c);
			}
			protocol.startProtocol(c.publicInterface);
		}

		@Override
		public void channelClosed() {
			if (NetworkManager.logger.isInfoEnabled())
				NetworkManager.logger.info("TCP server closed on channel " + channel.toString());
		}
		
		
		@Override
		public void acceptError(IOException error) {
			if (NetworkManager.logger.isInfoEnabled())
				NetworkManager.logger.info("Error accepting client on channel " + channel.toString(), error);
		}
	}
	
	class Client implements NetworkManager.TCPReceiver, NetworkManager.Sender, Closeable {

		public Client(SocketChannel channel) {
			this.channel = channel;
			publicInterface = new TCPServerClient(this);
			publicInterface.setAttribute(ServerProtocol.ATTRIBUTE_CONNECTION_ESTABLISHED_NANOTIME, Long.valueOf(System.nanoTime()));
		}
		
		SocketChannel channel;
		private TCPServerClient publicInterface;
		private ArrayList<ByteBuffer> inputBuffers = new ArrayList<>();
		private LinkedList<Pair<ByteBuffer,SynchronizationPoint<IOException>>> outputBuffers = new LinkedList<>();
		private boolean waitToSend = false;
		private boolean closeAfterLastOutput = false;
		private Provider<ByteBuffer> dataToSendProvider = null;
		private SynchronizationPoint<IOException> dataToSendSP = null;
		
		public TCPServer getServer() {
			return TCPServer.this;
		}
		
		@Override
		public synchronized void close() {
			if (channel == null) return;
			if (NetworkManager.logger.isDebugEnabled())
				NetworkManager.logger.debug("Client closed: " + channel);
			if (channel.isOpen())
				try { channel.close(); }
				catch (Throwable e) { /* ignore */ }
			channel = null;
			for (AutoCloseable c : publicInterface.toClose)
				try { c.close(); }
				catch (Throwable e) { /* ignore */ }
			while (!publicInterface.pending.isEmpty()) {
				ISynchronizationPoint<?> sp = publicInterface.pending.pollFirst();
				if (!sp.isUnblocked()) sp.cancel(new CancelException("Client connection closed"));
			}
			publicInterface = null;
			inputBuffers = null;
			outputBuffers = null;
		}
		
		public boolean isClosed() {
			return channel == null;
		}
		
		@Override
		public void sendTimeout() {
		}
		
		public synchronized void waitForData(int timeout) throws ClosedChannelException {
			if (channel == null) throw new ClosedChannelException();
			manager.register(channel, SelectionKey.OP_READ, this, timeout);
		}
		
		@Override
		public ByteBuffer allocateReceiveBuffer() {
			synchronized (inputBuffers) {
				if (!inputBuffers.isEmpty()) {
					ByteBuffer buffer = inputBuffers.remove(inputBuffers.size() - 1);
					buffer.clear();
					return buffer;
				}
			}
			return ByteBuffer.allocate(protocol.getInputBufferSize());
		}
		
		@Override
		public boolean received(ByteBuffer buffer) {
			return protocol.dataReceivedFromClient(publicInterface, buffer, new Runnable() {
				@Override
				public void run() {
					if (channel == null) return; // already closed
					if (buffer.hasRemaining()) {
						// if some data are still there, call again the protocol to process remaining data
						received(buffer);
						return;
					}
					synchronized (inputBuffers) {
						inputBuffers.add(buffer);
					}
				}
			});
		}
		
		@Override
		public void endOfInput(ByteBuffer buffer) {
			// the client closed the channel
			close();
		}
		
		@Override
		public void receiveError(IOException error, ByteBuffer buffer) {
			if (inputBuffers != null && buffer != null)
				synchronized (inputBuffers) { inputBuffers.add(buffer); }
		}
		
		synchronized SynchronizationPoint<IOException> send(ByteBuffer buf, boolean closeAfter) throws ClosedChannelException {
			if (channel == null) throw new ClosedChannelException();
			// ask the protocol to do any needed processing before sending the data
			LinkedList<ByteBuffer> buffers = protocol.prepareDataToSend(publicInterface, buf);
			boolean waitBefore = waitToSend;
			if (!waitToSend) {
				// we can start sending data right away
				ByteBuffer buffer = null;
				while (buffer != null || !buffers.isEmpty()) {
					if (buffer == null)
						buffer = buffers.removeFirst();
					if (NetworkManager.dataLogger.isTraceEnabled()) {
						StringBuilder s = new StringBuilder(128 + buffer.remaining() * 5);
						s.append("Sending ").append(buffer.remaining()).append(" bytes to client:\r\n");
						DebugUtil.dumpHex(s, buffer);
						NetworkManager.dataLogger.trace(s.toString());
					}
					int nb;
					try { nb = channel.write(buffer); }
					catch (IOException e) {
						// error while writing
						close();
						return new SynchronizationPoint<>(e);
					}
					if (NetworkManager.logger.isDebugEnabled()) NetworkManager.logger.debug(nb + " bytes sent on " + channel);
					if (!buffer.hasRemaining()) {
						// done with this buffer
						buffer = null;
						if (buffers.isEmpty()) {
							// no more buffer
							if (closeAfter) close();
							return new SynchronizationPoint<>(true);
						}
						continue;
					}
					if (nb == 0) break; // cannot write anymore
				}
				if (buffer != null) buffers.addFirst(buffer);
				waitToSend = true;
			}
			SynchronizationPoint<IOException> sp = new SynchronizationPoint<>();
			while (!buffers.isEmpty()) {
				ByteBuffer b = buffers.removeFirst();
				outputBuffers.add(new Pair<>(b, buffers.isEmpty() ? sp : null));
			}
			closeAfterLastOutput = closeAfter;
			if (!waitBefore && dataToSendProvider == null)
				manager.register(channel, SelectionKey.OP_WRITE, this, 0);
			return sp;
		}
		
		@Override
		public void readyToSend() {
			new Task.Cpu<Void,NoException>("Sending data to server client", Task.PRIORITY_RATHER_IMPORTANT) {
				@Override
				public Void run() {
					synchronized (Client.this) {
						if (outputBuffers == null) return null;
						if (outputBuffers.isEmpty() && dataToSendProvider != null) {
							outputBuffers.add(new Pair<>(dataToSendProvider.provide(), dataToSendSP));
							dataToSendProvider = null;
							dataToSendSP = null;
						}
						while (outputBuffers != null && !outputBuffers.isEmpty()) {
							Pair<ByteBuffer,SynchronizationPoint<IOException>> toWrite = outputBuffers.getFirst();
							int nb;
							do {
								try { nb = channel.write(toWrite.getValue1()); }
								catch (IOException e) {
									// error while writing
									close();
									outputBuffers.removeFirst();
									if (toWrite.getValue2() != null)
										toWrite.getValue2().error(e);
									while (!outputBuffers.isEmpty()) {
										toWrite = outputBuffers.removeFirst();
										if (toWrite.getValue2() != null)
											toWrite.getValue2().error(e);
									}
									return null;
								}
								if (NetworkManager.logger.isDebugEnabled())
									NetworkManager.logger.debug(nb + " bytes sent on " + channel);
								if (!toWrite.getValue1().hasRemaining()) {
									// we are done with this buffer
									outputBuffers.removeFirst();
									if (toWrite.getValue2() != null)
										toWrite.getValue2().unblock();
									break;
								}
							} while (nb > 0);
							if (nb == 0) break; // cannot write anymore
						}
						if (outputBuffers == null) return null;
						if (outputBuffers.isEmpty()) {
							// we are done with all data to be sent
							if (closeAfterLastOutput) {
								close();
								return null;
							}
							waitToSend = false; // do not wait next time
							if (dataToSendProvider == null)
								return null;
						}
						// still something to write, we need to register to the network manager
						manager.register(channel, SelectionKey.OP_WRITE, Client.this, 0);
					}
					return null;
				}
			}.start();
		}
		
		public void newDataToSendWhenPossible(Provider<ByteBuffer> dataProvider, SynchronizationPoint<IOException> sp) {
			synchronized (this) {
				Provider<ByteBuffer> prevProvider = dataToSendProvider;
				SynchronizationPoint<IOException> prevSP = dataToSendSP;
				dataToSendProvider = dataProvider;
				dataToSendSP = sp;
				if (!waitToSend && outputBuffers.isEmpty() && prevProvider == null)
					manager.register(channel, SelectionKey.OP_WRITE, Client.this, 0);
				if (prevProvider != null)
					prevSP.unblock();
			}
		}
		
		@Override
		public void channelClosed() {
			close();
		}
		
	}
	
}
