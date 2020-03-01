package net.lecousin.framework.network;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Supplier;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.concurrent.util.BufferedAsyncConsumer;

/** Base interface for a TCP connection with a remote end-point. */
public interface TCPRemote {
	
	/** Return the local address. */
	SocketAddress getLocalAddress() throws IOException;
	
	/** Return the remote address. */
	SocketAddress getRemoteAddress() throws IOException;

	/** Send data to the remote end-point. */
	IAsync<IOException> send(List<ByteBuffer> data, int timeout);
	
	/** Send data to the remote end-point. */
	default IAsync<IOException> send(ByteBuffer data, int timeout) {
		LinkedList<ByteBuffer> list = new LinkedList<>();
		list.add(data);
		return send(list, timeout);
	}
	
	/** The data will be sent as soon as possible, however a subsequent call to this method will override
	 * the previous one.
	 * The data will be sent after all data sent through the send method is finished.
	 * It means that if data is sent again using the send method before this data is sent,
	 * this data will be send after the new send is finished.
	 */
	void newDataToSendWhenPossible(Supplier<List<ByteBuffer>> dataProvider, Async<IOException> sp, int timeout);
	
	/** Call the given listener when the TCP connection is closed. */
	void onclosed(Runnable listener);
	
	/** Return true if the remote is closed. */
	boolean isClosed();
	
	/** Create a consumer sending data to this remote end-point. */ 
	default AsyncConsumer<ByteBuffer, IOException> asConsumer(int nbPendingBuffers, int sendTimeout) {
		AsyncConsumer<ByteBuffer, IOException> consumer = new AsyncConsumer<ByteBuffer, IOException>() {
			@Override
			public IAsync<IOException> consume(ByteBuffer data) {
				return send(data, sendTimeout);
			}
			
			@Override
			public IAsync<IOException> end() {
				return new Async<>(true);
			}
			
			@Override
			public void error(IOException error) {
				// ignore
			}
		};
		if (nbPendingBuffers > 1)
			consumer = new BufferedAsyncConsumer<>(nbPendingBuffers, consumer);
		return consumer;
	}
	
}
