package net.lecousin.framework.network.session;

import java.util.HashMap;

import net.lecousin.framework.math.FragmentedRangeLong;
import net.lecousin.framework.math.RangeLong;
import net.lecousin.framework.util.StringUtil;

/**
 * Store sessions in memory.
 */
public class SessionInMemory implements SessionStorage {

	private HashMap<Long,Session> sessions = new HashMap<>(100);
	private FragmentedRangeLong free = new FragmentedRangeLong(new RangeLong(1, Long.MAX_VALUE));
	
	@Override
	public void close() {
		sessions = null;
		free = null;
	}
	
	@Override
	public String allocateId() {
		return StringUtil.encodeHexaPadding(free.removeFirstValue().longValue());
	}
	
	@Override
	public void freeId(String id) {
		long l = StringUtil.decodeHexaLong(id);
		sessions.remove(Long.valueOf(l));
	}
	
	@Override
	public Session load(String id) {
		long l = StringUtil.decodeHexaLong(id);
		return sessions.get(Long.valueOf(l));
	}
	
	@Override
	public void save(String id, Session session) {
		long l = StringUtil.decodeHexaLong(id);
		sessions.put(Long.valueOf(l), session);
	}
	
}
