package net.lecousin.framework.network.server;

import java.io.Closeable;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.application.Application;
import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.collections.TurnArray;
import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.synch.ISynchronizationPoint;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.network.NetworkManager;
import net.lecousin.framework.util.Pair;

/** UDP Server.<br/>
 * A server can listen to several IP addresses and ports.<br/>
 * It uses the {@link NetworkManager} to perform asynchronous operations.
 */
public class UDPServer implements Closeable {

	/** Constructor. */
	public UDPServer(int receiveBufferSize, MessageListener messageListener) {
		app = LCCore.getApplication();
		manager = NetworkManager.get(app);
		app.toClose(this);
		this.receiveBufferSize = receiveBufferSize;
		this.messageListener = messageListener;
	}
	
	protected Application app;
	protected NetworkManager manager;
	protected ArrayList<Channel> channels = new ArrayList<>();
	protected int receiveBufferSize;
	protected MessageListener messageListener;
	
	/** Interface to implement to handle received messages. */
	public static interface MessageListener {
		/** Called each time a new datagram is received on the server. */
		public void newMessage(ByteBuffer message, SocketAddress source, MessageSender reply);
	}
	
	/** Interface given to the MessageListener so it can reply to a specific message/client. */
	public static interface MessageSender {
		/** Method to call to send a reply to a client. */
		public void reply(ByteBuffer reply);
	}
	
	@Override
	public void close() {
		List<ISynchronizationPoint<IOException>> sp = new LinkedList<>();
		for (Channel channel : channels) {
			if (manager.getLogger().info())
				manager.getLogger().info("Closing UDP server: " + channel.channel.toString());
			channel.key.cancel();
			try { channel.channel.close(); }
			catch (IOException e) {
				if (manager.getLogger().error())
					manager.getLogger().error("Error closing UDP server", e);
			}
			sp.add(NetworkManager.get().register(channel.channel, 0, null, 0));
		}
		for (ISynchronizationPoint<IOException> s : sp)
			s.block(5000);
		channels.clear();
		app.closed(this);
	}
	
	/** Listen to the given address. */
	@SuppressWarnings("resource")
	public SocketAddress bind(SocketAddress local) throws IOException {
		DatagramChannel channel = DatagramChannel.open();
		channel.bind(local);
		channel.configureBlocking(false);
		Channel c = new Channel(channel);
		try { c.key = manager.register(channel, SelectionKey.OP_READ, c, 0).blockResult(0); }
		catch (Exception e) { throw IO.error(e); }
		channels.add(c);
		local = channel.getLocalAddress();
		if (manager.getLogger().info())
			manager.getLogger().info("New UDP server listening on " + local.toString());
		return local;
	}
	
	/** A channel the server is listening to. */
	protected class Channel implements NetworkManager.UDPReceiver, NetworkManager.Sender {

		protected Channel(DatagramChannel channel) {
			this.channel = channel;
		}
		
		protected DatagramChannel channel;
		protected SelectionKey key;
		
		@Override
		public void channelClosed() {
		}

		@Override
		public void sendTimeout() {
		}
		
		@Override
		public ByteBuffer allocateReceiveBuffer() {
			return ByteBuffer.allocate(receiveBufferSize);
		}

		@Override
		public void received(ByteBuffer buffer, SocketAddress source) {
			if (buffer.hasRemaining())
				messageListener.newMessage(buffer, source, new MessageSender() {
					@Override
					public void reply(ByteBuffer reply) {
						synchronized (sendQueue) {
							boolean first = sendQueue.isEmpty();
							sendQueue.push(new Pair<>(source, reply));
							if (first)
								manager.register(channel, SelectionKey.OP_WRITE, Channel.this, 0);
						}
					}
				});
			manager.register(channel, SelectionKey.OP_READ, this, 0);
		}

		@Override
		public void receiveError(IOException error, ByteBuffer buffer) {
		}
		
		protected TurnArray<Pair<SocketAddress,ByteBuffer>> sendQueue = new TurnArray<>(10);
		
		@Override
		public void readyToSend() {
			new Task.Cpu<Void, NoException>("Sending datagrams to UDP clients", Task.PRIORITY_RATHER_IMPORTANT) {
				@Override
				public Void run() {
					synchronized (sendQueue) {
						while (!sendQueue.isEmpty()) {
							Pair<SocketAddress,ByteBuffer> toSend = sendQueue.getFirst();
							int nb;
							try {
								nb = channel.send(toSend.getValue2(), toSend.getValue1());
							} catch (IOException e) {
								// error while sending data, just skip it
								sendQueue.removeFirst();
								continue;
							}
							if (nb == 0) break; // cannot write anymore
							if (!toSend.getValue2().hasRemaining())
								sendQueue.removeFirst();
						}
						if (sendQueue.isEmpty()) {
							// no more data to send
							return null;
						}
					}
					// still something to write, we need to register to the network manager
					manager.register(channel, SelectionKey.OP_WRITE, Channel.this, 0);
					return null;
				}
			}.start();
		}
		
	}

}
