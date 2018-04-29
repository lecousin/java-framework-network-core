package net.lecousin.framework.network.server.protocol;

import java.nio.ByteBuffer;
import java.util.LinkedList;

import net.lecousin.framework.network.server.TCPServerClient;

/** Interface to implement a protocol on server-side. */
public interface ServerProtocol {

	public static final String ATTRIBUTE_CONNECTION_ESTABLISHED_NANOTIME = "protocol.connection_started";
	
	/**
	 * Called when a new client connects on the server.
	 * The implementation should initialise the status, may send first data to the client,
	 * and then should call the method waitForData on the given client. 
	 * @param client the newly connected client
	 */
	public void startProtocol(TCPServerClient client);
	
	/**
	 * Returns the size of the buffer which will be allocated to receive data from the client.
	 * @return the size of the buffer which will be allocated to receive data from the client
	 */
	public int getInputBufferSize();
	
	/**
	 * Called when data has been received from the client.
	 * The data received may be incomplete. In that case, the data should be kept, then the method waitForData
	 * should be called on the client to receive more (or return true).
	 * The implementation should get the data, then process it in a separate thread/task to avoid blocking the
	 * network manager thread.
	 * Once the given buffer has been processed, the onbufferavailable should be called to signal the given buffer can be reused.
	 * When calling onbufferavailable, if some data is remaining it will be ignored,
	 * so the implementation must ensure there is no remaining data in the buffer.
	 * If more data is expected, the method waitForData should be called, else this method won't be called again.
	 * @param client the connected client
	 * @param data the data received
	 * @param onbufferavailable to be called to signal the given buffer can be reused to receive new data
	 */
	public void dataReceivedFromClient(TCPServerClient client, ByteBuffer data, Runnable onbufferavailable);
	
	/**
	 * Called before to send data to the client. It allows a protocol to do any needed transformation before sending it.
	 * For example, the SSL protocol will encrypt the data.
	 * @param client the connected client
	 * @param data the data which has to be sent
	 * @return the new data to be sent
	 */
	public LinkedList<ByteBuffer> prepareDataToSend(TCPServerClient client, ByteBuffer data);
	
}
