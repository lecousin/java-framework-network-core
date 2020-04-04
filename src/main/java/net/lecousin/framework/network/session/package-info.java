/**
 * A {@link net.lecousin.framework.network.session.Session} has a unique identifier, and stores a mapping between keys and values.<br/>
 * A {@link net.lecousin.framework.network.session.SessionStorage} allows to load and save a session, associating a session with a unique storage id.
 * A basic implementation of it is {@link net.lecousin.framework.network.session.SessionInMemory} which simply keeps active sessions is memory.<br/>
 * A {@link net.lecousin.framework.network.session.SessionProvider} is designed for a specific type of client, and allows to create, destroy or
 * retrieve an existing session for a given client. An implementation of it is {@link net.lecousin.framework.network.session.NetworkSessionProvider}
 * which provides sessions for {@link net.lecousin.framework.network.server.TCPServerClient}, ensuring
 * that a session can only be accessed for a client having the same IP address.<br/>
 * A {@link net.lecousin.framework.network.session.SessionProvider} is usually created with a given
 * {@link net.lecousin.framework.network.session.SessionStorage} that it will use
 * to store its sessions.
 */
package net.lecousin.framework.network.session;
