package net.lecousin.framework.network.server.protocol;

import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.network.server.TCPServerClient;

/** Interface to implement a protocol on server-side. */
public interface ServerProtocol {

	/**
	 * Called when a new client connects on the server.<br/>
	 * The implementation should initialize the status and may send first data to the client.<br/>
	 * If it returns a positive or zero value, the caller will automatically call the method {@link TCPServerClient#waitForData(int)}
	 * with the returned value as timeout. Else nothing is done.
	 * @param client the newly connected client
	 * @return the first receive data timeout, or negative value to do not expect data from the client
	 */
	int startProtocol(TCPServerClient client);
	
	/**
	 * Returns the size of the buffer which will be allocated to receive data from the client.
	 * @return the size of the buffer which will be allocated to receive data from the client
	 */
	int getInputBufferSize();
	
	/**
	 * Called when data has been received from the client.
	 * The data received may be incomplete. In that case, the data should be kept, then the method waitForData
	 * should be called on the client to receive more.
	 * Once the given buffer has been processed, the data should be free using ByteArrayCache.free so the buffer can be reused.
	 * If more data is expected, the method waitForData should be called, else this method won't be called again.
	 * @param client the connected client
	 * @param data the data received
	 */
	void dataReceivedFromClient(TCPServerClient client, ByteBuffer data);
	
	/**
	 * Called before to send data to the client. It allows a protocol to do any needed transformation before sending it.
	 * For example, the SSL protocol will encrypt the data.
	 * @param client the connected client
	 * @param data the data which has to be sent
	 * @return the new data to be sent
	 */
	default LinkedList<ByteBuffer> prepareDataToSend(TCPServerClient client, List<ByteBuffer> data) {
		if (data instanceof LinkedList)
			return (LinkedList<ByteBuffer>)data;
		return new LinkedList<>(data);
	}
	
}
