package net.lecousin.framework.network.name;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.ListIterator;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.plugins.Plugin;

/** A name service resolves a name into a list of IP addresses. */
public interface NameService extends Plugin {

	AsyncSupplier<List<Resolution>, IOException> resolve(String name);
	
	/** Name resolution into an IP address. */
	static class Resolution {
		
		private InetAddress ip;
		private long expiration;
		
		public Resolution(InetAddress ip, long expiration) {
			this.ip = ip;
			this.expiration = expiration;
		}
		
		public InetAddress getIp() {
			return ip;
		}
		
		
		public long getExpiration() {
			return expiration;
		}
		
	}
	
	static AsyncSupplier<List<Resolution>, IOException> resolveName(String name) {
		List<NameService> services = NameServiceRegistry.get().getPlugins();
		ListIterator<NameService> it = services.listIterator(services.size());
		AsyncSupplier<List<Resolution>, IOException> result = new AsyncSupplier<>();
		Runnable next = new Runnable() {
			@Override
			public void run() {
				if (!it.hasPrevious()) {
					result.error(new UnknownHostException(name));
					return;
				}
				NameService service = it.previous();
				AsyncSupplier<List<Resolution>, IOException> r = service.resolve(name);
				Runnable that = this;
				r.onDone(
					result::unblockSuccess,
					err -> {
						LCCore.getApplication().getLoggerFactory().getLogger(NameService.class)
						.error("Error resolving name " + name + " using service " + service, err);
						that.run();
					},
					cancel -> that.run()
				);
			}
		};
		next.run();
		return result;
	}
	
}
