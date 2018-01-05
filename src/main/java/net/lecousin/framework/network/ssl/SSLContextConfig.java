package net.lecousin.framework.network.ssl;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import net.lecousin.framework.application.Application;

public class SSLContextConfig {

	public String algorithm = "TLS";
	
	public Store keyStore;
	public Store trustStore;
	
	public static class Store {
		
		public Store() {}
		public Store(String type, String url, String password) {
			this.type = type;
			this.url = url;
			this.password = password;
		}
		
		public String type = "JKS";
		public String url;
		public String password;
		
	}
	
	public static SSLContext create(Application app, SSLContextConfig config) throws GeneralSecurityException, IOException {
		SSLContext context = SSLContext.getInstance(config.algorithm);

		KeyManager[] keyManagers = null;
		TrustManager[] trustManagers = null;

		if (config.keyStore != null) {
			KeyStore ks = loadStore(config.keyStore);
			
			KeyManagerFactory kmf = null;
			String algo = KeyManagerFactory.getDefaultAlgorithm();
			if (algo != null)
				kmf = KeyManagerFactory.getInstance(algo);
			
			if (kmf != null) {
				kmf.init(ks, "password".toCharArray());
				keyManagers = kmf.getKeyManagers();
			}
		}
		
		if (config.trustStore != null) {
			KeyStore ts = loadStore(config.trustStore);

			TrustManagerFactory tmf = null;
			String algo = TrustManagerFactory.getDefaultAlgorithm();
			if (algo != null) tmf = TrustManagerFactory.getInstance(algo);
	
			if (tmf != null) {
				tmf.init(ts);
				trustManagers = tmf.getTrustManagers();
			}
		}
		
		context.init(keyManagers, trustManagers, null);
		return context;
	}
	
	public static KeyStore loadStore(Store store) throws GeneralSecurityException, IOException {
		return loadStore(store.type, new URL(store.url), store.password);
	}
	
	public static KeyStore loadStore(String type, URL url, String password) throws GeneralSecurityException, IOException {
		KeyStore ks = KeyStore.getInstance(type);
		try (InputStream in = url.openStream()) {
			ks.load(in, password.toCharArray());
		}
		return ks;
	}
	
}
