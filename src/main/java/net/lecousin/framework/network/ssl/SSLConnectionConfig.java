package net.lecousin.framework.network.ssl;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

import net.lecousin.framework.log.LoggerFactory;

/** Configuration to connect using SSL. */
public class SSLConnectionConfig {
	
	/** ALPN was added since JDK 1.8.0_251 in April 2020 */
	public static final boolean ALPN_SUPPORTED;
	
	private static final Method ALPN_METHOD_SET;
	private static final Method ALPN_METHOD_GET;
	
	public static void setALPNProtocols(SSLParameters params, String[] protocols) {
		try {
			ALPN_METHOD_SET.invoke(params, (Object)protocols);
		} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
			LoggerFactory.get(SSLConnectionConfig.class).error("Error setting ALPN protocols", e);
		}
	}
	
	public static String getALPNProtocol(SSLEngine engine) {
		try {
			return (String)ALPN_METHOD_GET.invoke(engine);
		} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
			LoggerFactory.get(SSLConnectionConfig.class).error("Error setting ALPN protocols", e);
			return null;
		}
	}
	
	static {
		Method methodGet;
		Method methodSet;
		try {
			methodGet = SSLEngine.class.getMethod("getApplicationProtocol");
			methodSet = SSLParameters.class.getMethod("setApplicationProtocols", String[].class);
		} catch (Exception e) {
			methodGet = null;
			methodSet = null;
		}
		ALPN_SUPPORTED = methodGet != null && methodSet != null;
		ALPN_METHOD_GET = methodGet;
		ALPN_METHOD_SET = methodSet;
		System.out.println("ALPN support: " + ALPN_SUPPORTED);
	}
	
	private SSLContext context = null;
	private List<String> hostNames = null;
	private List<String> applicationProtocols = null;
	
	public SSLConnectionConfig() {
		// nothing
	}
	
	public SSLConnectionConfig(SSLConnectionConfig copy) {
		this.context = copy.context;
		this.hostNames = copy.hostNames;
		this.applicationProtocols = copy.applicationProtocols;
	}
	
	public void setContext(SSLContext context) {
		this.context = context;
	}

	public void setHostNames(List<String> hostNames) {
		this.hostNames = hostNames;
	}
	
	public void setApplicationProtocols(List<String> alpnNames) {
		this.applicationProtocols = alpnNames;
	}
	
	public SSLEngine createEngine(boolean clientMode) throws NoSuchAlgorithmException {
		if (context == null)
			context = SSLContext.getDefault();
		SSLEngine engine = context.createSSLEngine();
		SSLParameters params = new SSLParameters();
		if (hostNames != null && !hostNames.isEmpty()) {
			ArrayList<SNIServerName> list = new ArrayList<>(hostNames.size());
			for (String name : hostNames)
				list.add(new SNIHostName(name));
			params.setServerNames(list);
		}
		if (applicationProtocols != null && !applicationProtocols.isEmpty() && ALPN_SUPPORTED) {
			setALPNProtocols(params, applicationProtocols.toArray(new String[applicationProtocols.size()]));
		}
		engine.setSSLParameters(params);
		engine.setUseClientMode(clientMode);
		return engine;
	}
	
}
