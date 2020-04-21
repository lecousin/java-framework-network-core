package net.lecousin.framework.network.server.protocol;

/** Interface to add declaration of the protocol's name for Application-Layer Protocol Negotiation. */
public interface ALPNServerProtocol extends ServerProtocol {

	String getALPNName();
	
}
