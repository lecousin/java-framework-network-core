package net.lecousin.framework.network.name;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.concurrent.threads.Task.Priority;

public class JdkNameService implements NameService {

	@Override
	public AsyncSupplier<List<Resolution>, IOException> resolve(String name) {
		AsyncSupplier<List<Resolution>, IOException> result = new AsyncSupplier<>();
		Task.unmanaged("Name resolution", Priority.NORMAL, t -> {
			try {
				InetAddress[] ips = InetAddress.getAllByName(name);
				long expiration = System.currentTimeMillis() + 5 * 60 * 1000;
				List<Resolution> list = new ArrayList<>(ips.length);
				for (InetAddress ip : ips)
					list.add(new Resolution(ip, expiration));
				result.unblockSuccess(list);
			} catch (IOException e) {
				result.error(e);
			}
			return null;
		}).start();
		return result;
	}
	
}
