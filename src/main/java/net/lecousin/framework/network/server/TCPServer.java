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
import java.util.List;

import net.lecousin.framework.application.Application;
import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.CancelException;
import net.lecousin.framework.concurrent.synch.ISynchronizationPoint;
import net.lecousin.framework.concurrent.synch.SynchronizationPoint;
import net.lecousin.framework.io.IO;
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
	
	/** Return a list of currently connected clients. */
	public ArrayList<Closeable> getConnectedClients() {
		ArrayList<Closeable> list;
		synchronized (clients) {
			list = new ArrayList<>(clients);
		}
		return list;
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
		try { sc.key = manager.register(channel, SelectionKey.OP_ACCEPT, sc, 0).blockResult(0); }
		catch (Exception e) { throw IO.error(e); }
		channels.add(sc);
		local = channel.getLocalAddress();
		if (manager.getLogger().info())
			manager.getLogger().info("New TCP server listening at " + local.toString());
		return local;
	}
	
	/** Stop listening to all ports and addresses. */
	public void unbindAll() {
		List<ISynchronizationPoint<IOException>> sp = new LinkedList<>();
		for (ServerChannel channel : channels) {
			if (manager.getLogger().info())
				manager.getLogger().info("Closing TCP server: " + channel.channel.toString());
			channel.key.cancel();
			try { channel.channel.close(); }
			catch (IOException e) { manager.getLogger().error("Error closing TCP server", e); }
			sp.add(NetworkManager.get().register(channel.channel, 0, null, 0));
		}
		for (ISynchronizationPoint<IOException> s : sp)
			s.block(5000);
		channels.clear();
		ArrayList<Client> list;
		synchronized (clients) {
			list = new ArrayList<>(clients);
			clients.clear();
		}
		for (Client client : list)
			client.close();
	}
	
	private class ServerChannel implements NetworkManager.Server {
		private ServerChannel(ServerSocketChannel channel) {
			this.channel = channel;
		}
		
		private ServerSocketChannel channel;
		private SelectionKey key;

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
				catch (Throwable e) { /* ignore */ }
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
			ArrayList<ByteBuffer> buffers = inputBuffers;
			if (buffers == null) return ByteBuffer.allocate(512); // server closed
			synchronized (buffers) {
				if (!buffers.isEmpty()) {
					ByteBuffer buffer = buffers.remove(buffers.size() - 1);
					buffer.clear();
					return buffer;
				}
			}
			return ByteBuffer.allocate(protocol.getInputBufferSize());
		}
		
		@Override
		public void received(ByteBuffer buffer) {
			protocol.dataReceivedFromClient(publicInterface, buffer, new Runnable() {
				@Override
				public void run() {
					if (channel == null) return; // already closed
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
			if (inputBuffers != null && buffer != null && channel != null)
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
					if (manager.getDataLogger().trace()) {
						StringBuilder s = new StringBuilder(128 + buffer.remaining() * 5);
						s.append("Sending ").append(buffer.remaining()).append(" bytes to client:\r\n");
						DebugUtil.dumpHex(s, buffer);
						manager.getDataLogger().trace(s.toString());
					}
					int nb;
					try { nb = channel.write(buffer); }
					catch (IOException e) {
						// error while writing
						close();
						return new SynchronizationPoint<>(e);
					}
					if (manager.getLogger().debug()) manager.getLogger().debug(nb + " bytes sent on " + channel);
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
			synchronized (Client.this) {
				if (outputBuffers == null || channel == null) return;
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
							return;
						}
						if (manager.getLogger().debug())
							manager.getLogger().debug(nb + " bytes sent on " + channel);
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
				if (outputBuffers == null) return;
				if (outputBuffers.isEmpty()) {
					// we are done with all data to be sent
					if (closeAfterLastOutput) {
						close();
						return;
					}
					waitToSend = false; // do not wait next time
					if (dataToSendProvider == null)
						return;
				}
				// still something to write, we need to register to the network manager
				if (manager.getLogger().debug())
					manager.getLogger().debug("Register to NetworkManager to send data: "
						+ outputBuffers.size() + " buffer(s) remaining");
				manager.register(channel, SelectionKey.OP_WRITE, Client.this, 0);
			}
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
		
		@Override
		public String toString() {
			return "Connected client[" + channel + "]";
		}
		
	}
	
}
