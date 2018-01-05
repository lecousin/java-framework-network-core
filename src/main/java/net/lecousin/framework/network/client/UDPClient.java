package net.lecousin.framework.network.client;

import java.io.Closeable;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import net.lecousin.framework.collections.TurnArray;
import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.synch.SynchronizationPoint;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.network.NetworkManager;
import net.lecousin.framework.util.Pair;

/**
 * UPD Client.
 */
public class UDPClient implements Closeable {

	/** Constructor. */
	public UDPClient(SocketAddress target) {
		this.target = target;
		manager = NetworkManager.get();
	}
	
	private NetworkManager manager;
	private SocketAddress target;
	private DatagramChannel channel;
	private TurnArray<Pair<ByteBuffer, SynchronizationPoint<IOException>>> toSend = new TurnArray<>();
	
	private NetworkManager.Sender sender = new NetworkManager.Sender() {
		@Override
		public void channelClosed() {
			// TODO
		}
		
		@Override
		public void sendTimeout() {
		}
		
		@Override
		public void readyToSend() {
			new Task.Cpu<Void, NoException>("Sending datagrams to server", Task.PRIORITY_NORMAL) {
				@Override
				public Void run() {
					synchronized (toSend) {
						while (!toSend.isEmpty()) {
							Pair<ByteBuffer, SynchronizationPoint<IOException>> p = toSend.getFirst();
							int nb;
							try {
								synchronized (UDPClient.this) {
									if (channel == null) throw new ClosedChannelException();
									nb = channel.send(p.getValue1(), target);
								}
							} catch (IOException e) {
								// error while sending data, just skip it
								toSend.removeFirst();
								if (p.getValue2() != null)
									p.getValue2().error(e);
								continue;
							}
							if (nb == 0) break; // cannot write anymore
							if (!p.getValue1().hasRemaining()) {
								if (p.getValue2() != null)
									p.getValue2().unblock();
								toSend.removeFirst();
							}
						}
						if (toSend.isEmpty()) {
							// no more data to send
							return null;
						}
					}
					// still something to write, we need to register to the network manager
					manager.register(channel, SelectionKey.OP_WRITE, sender, 0);
					return null;
				}
			}.start();
		}
	};
	
	private synchronized void openChannel() throws IOException {
		if (channel != null) return;
		channel = DatagramChannel.open();
		channel.configureBlocking(false);
	}
	
	@Override
	public synchronized void close() {
		if (channel != null) {
			try { channel.close(); }
			catch (Throwable e) { /* ignore */ }
			channel = null;
		}
	}
	
	/**
	 * Send data. Data are queued until they can be sent, so multiple calls to this method can be done
	 * without waiting for the previous message to be sent.
	 * @param data the data to send
	 * @param onSent unblocked when the data has been fully sent or an error occured. Can be null.
	 */
	public void send(ByteBuffer data, SynchronizationPoint<IOException> onSent) {
		if (data.remaining() == 0) {
			if (onSent != null) onSent.unblock();
			return;
		}
		try { openChannel(); }
		catch (IOException e) {
			if (onSent != null) onSent.error(e);
			return;
		}
		synchronized (toSend) {
			toSend.push(new Pair<>(data, onSent));
			if (toSend.size() == 1)
				manager.register(channel, SelectionKey.OP_WRITE, sender, 0);
		}
	}

	/** Interface to implement to wait an answer from the server. */
	public static interface AnswerListener {
		/** Called when some data are received.
		 * Must return null if no more data is expected.
		 * Else it can return the same buffer to continue to fill it, or allocate a new one especially if the
		 * current buffer is already full. 
		 */
		public ByteBuffer dataReceived(ByteBuffer buffer);
		
		/** Called if an error occurs while sending the data. */
		public void error(IOException error);
		
		/** Called if a timeout occurs. */
		public void timeout();
	}
	
	private class Receiver implements NetworkManager.UDPReceiver {
		private Receiver(ByteBuffer buffer, AnswerListener listener) {
			this.buffer = buffer;
			this.listener = listener;
		}
		
		private ByteBuffer buffer;
		private AnswerListener listener;
		
		@Override
		public void channelClosed() {
			AnswerListener l;
			synchronized (UDPClient.this) {
				channel = null;
				l = listener;
				listener = null;
			}
			if (l != null) l.error(new ClosedChannelException());
		}
		
		@Override
		public void receiveError(IOException error, ByteBuffer buffer) {
			AnswerListener l;
			synchronized (UDPClient.this) {
				try { if (channel != null) channel.close(); }
				catch (Throwable e) { /* ignore */ }
				channel = null;
				l = listener;
				listener = null;
			}
			if (l != null) l.error(error);
		}
		
		@Override
		public ByteBuffer allocateReceiveBuffer() {
			return buffer;
		}
		
		@SuppressFBWarnings("IS2_INCONSISTENT_SYNC")
		@Override
		public boolean received(ByteBuffer buffer, SocketAddress source) {
			AnswerListener l;
			synchronized (UDPClient.this) {
				l = listener;
				listener = null;
			}
			this.buffer = l.dataReceived(buffer);
			if (this.buffer == null) {
				try { if (channel != null) channel.close(); }
				catch (Throwable e) { /* ignore */ }
				channel = null;
				return false;
			}
			synchronized (UDPClient.this) {
				listener = l;
			}
			return true;
		}
	}
	
	/**
	 * Wait for an answer from the server. 
	 * @param buffer An initial buffer to fill with data received from the server.
	 * @param listener The listener to handle events.
	 * @param timeout a timeout in milliseconds, or 0 for no timeout.
	 */
	public void waitForAnswer(ByteBuffer buffer, AnswerListener listener, int timeout) {
		try { openChannel(); }
		catch (IOException e) {
			listener.error(e);
			return;
		}
		manager.register(channel, SelectionKey.OP_READ, new Receiver(buffer, listener), timeout);
	}
	
}
