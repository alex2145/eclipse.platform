/*******************************************************************************
 * Copyright (c) 2007, 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.core.internal.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Properties;

import org.eclipse.core.net.proxy.IProxyData;
import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.INodeChangeListener;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.IPreferenceChangeListener;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.NodeChangeEvent;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.PreferenceChangeEvent;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;
import org.eclipse.osgi.util.NLS;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

public class ProxyType implements INodeChangeListener, IPreferenceChangeListener {

	/**
	 * Preference keys
	 */
	private static final String PREF_PROXY_DATA_NODE = "proxyData"; //$NON-NLS-1$
	private static final String PREF_PROXY_HOST = "host"; //$NON-NLS-1$
	private static final String PREF_PROXY_PORT = "port"; //$NON-NLS-1$
	private static final String PREF_PROXY_HAS_AUTH = "hasAuth"; //$NON-NLS-1$

	/**
	 * Verification tags used when creating a proxy data
	 */
	public static final int DO_NOT_VERIFY = 1;
	public static final int VERIFY_EMPTY = 2;
	public static final int VERIFY_EQUAL = 4;
	
	/**
	 * Constants that control the setting of the SOCKS system properties
	 */
	private static final String PROP_SOCKS_SYSTEM_PROPERTY_HANDLING = "org.eclipse.net.core.setSocksSystemProperties"; //$NON-NLS-1$
	public static final int ONLY_SET_FOR_1_5_OR_LATER = 0;
	public static final int ALWAYS_SET = 1;
	public static final int NEVER_SET = 2;
	public static int socksSystemPropertySetting;
	
    /**
     * Absolute path to the node for the cached proxy information
     */
    private static final String PREFERENCES_CONTEXT = "/org.eclipse.core.net.proxy.auth"; //$NON-NLS-1$
	/*
	 * Fields used to cache authentication information in the keyring
	 */
    private static final String INFO_PROXY_USER = "user"; //$NON-NLS-1$ 
    private static final String INFO_PROXY_PASS = "pass"; //$NON-NLS-1$ 
    static {
        String value = System.getProperty(PROP_SOCKS_SYSTEM_PROPERTY_HANDLING);
        if (value == null) {
        	socksSystemPropertySetting = ONLY_SET_FOR_1_5_OR_LATER;
        } else if (value.equals("always")) { //$NON-NLS-1$
        	socksSystemPropertySetting = ALWAYS_SET;
        } else if (value.equals("never")) { //$NON-NLS-1$
        	socksSystemPropertySetting = NEVER_SET;
        } else {
        	socksSystemPropertySetting = ONLY_SET_FOR_1_5_OR_LATER;
        }
    }
    
	private String name;
	private boolean updatingPreferences;
	private Preferences netPreferences;

	public static String convertHostsToPropertyString(String[] value) {
		StringBuffer buffer = new StringBuffer();

		if (value == null)
			return ""; //$NON-NLS-1$

		if (value.length > 0) {
			buffer.append(value[0]);
		}

		for (int index = 1; index < value.length; index++) {
			buffer.append('|');
			buffer.append(value[index]);
		}

		return buffer.toString();
	}

	public static String[] convertPropertyStringToHosts(String property) {
		String hosts[] = property.split("\\|"); //$NON-NLS-1$
		ArrayList ret = new ArrayList();
		for (int i = 0; i < hosts.length; i++) {
			if (hosts[i].length() != 0) {
				ret.add(hosts[i]);
			}
		}
		return (String[]) ret.toArray(new String[0]);
	}

	public ProxyType(String name) {
		this.name = name;
		this.netPreferences = Activator.getInstance().getPreferences();
	}	
	
	public ProxyType(String name, Preferences netPreferences) {
		this.name = name;
		this.netPreferences = netPreferences;
	}

	private Preferences getPreferenceNode() {
		return getParentPreferences().node(getName());
	}

	/**
	 * Return the preferences node whose child nodes are the know proxy types
	 * 
	 * @return a preferences node
	 */
	private Preferences getParentPreferences() {
		return netPreferences.node(
				PREF_PROXY_DATA_NODE);
	}

	public IProxyData getProxyData(int verifyFlag) {
		return createProxyData(name, getPreferenceNode(), verifyFlag);
	}

	private IProxyData createProxyData(String type, Preferences node, int verifyFlag) {
		String host = node.get(PREF_PROXY_HOST, null);
		if (host != null && host.length() == 0)
			host = null;
		int port = node.getInt(PREF_PROXY_PORT, -1);
		boolean requiresAuth = node.getBoolean(PREF_PROXY_HAS_AUTH, false);
		ProxyData proxyData = new ProxyData(type, host, port, requiresAuth,
				null);
		loadProxyAuth(proxyData);
		if (verifyFlag == VERIFY_EMPTY) {
			// We are initializing so verify that the system properties are empty
			verifySystemPropertiesEmpty(type);
		} else if (verifyFlag == VERIFY_EQUAL) {
			// Verify that the data in the preferences matches the system properties 
			verifyDataMatchesSystemProperties(proxyData);
		}
		return proxyData;
	}

	public boolean setProxyData(IProxyData proxyData, boolean proxiesEnabled) {
		Assert.isTrue(proxyData.getType().equals(getName()));
		IProxyData oldData = getProxyData(VERIFY_EQUAL);
		if (oldData.equals(proxyData))
			return false;
		saveProxyAuth(proxyData);
		try {
			updatingPreferences = true;
			updatePreferences(proxyData);
		} finally {
			updatingPreferences = false;
		}
		updateSystemProperties(proxyData, proxiesEnabled);
		return true;
	}

	private void updatePreferences(IProxyData proxyData) {
		updatePreferences(getPreferenceNode(), proxyData);
	}
	
	/* package */ void updatePreferencesIfMissing(Preferences node, IProxyData proxyData) {
		Preferences proxyNode = node.node(PREF_PROXY_DATA_NODE).node(getName());
		if (node.get(PREF_PROXY_HOST, null) == null)
			updatePreferences(proxyNode, proxyData);
	}
	
	private void updatePreferences(Preferences node, IProxyData proxyData) {
		if (proxyData.getHost() == null) {
			try {
				Preferences parent = node.parent();
				node.removeNode();
				parent.flush();
			} catch (BackingStoreException e) {
				Activator.logError(NLS.bind(
						"An error occurred removing the {0} proxy node from the preference store", proxyData.getType()), e); //$NON-NLS-1$
			}
		} else {
			node.put(PREF_PROXY_HOST, proxyData.getHost());
			node.putInt(PREF_PROXY_PORT, proxyData.getPort());
			node.putBoolean(PREF_PROXY_HAS_AUTH, proxyData.getUserId() != null);
			try {
				node.flush();
			} catch (BackingStoreException e) {
				Activator.logError(NLS.bind(
					"The {0} proxy node could not be written", proxyData.getType()), e); //$NON-NLS-1$
			}
		}
	}
	
	/* package */void updateSystemProperties(IProxyData proxyData, boolean proxiesEnabled) {
		try {
			if (proxyData.getType().equals(IProxyData.HTTP_PROXY_TYPE)) {
				updateHttpSystemProperties(proxyData, proxiesEnabled);
			} else if (proxyData.getType().equals(IProxyData.HTTPS_PROXY_TYPE)) {
				updateHttpsSystemProperties(proxyData, proxiesEnabled);
			} else if (proxyData.getType().equals(IProxyData.SOCKS_PROXY_TYPE)) {
				updateSocksSystemProperties(proxyData, proxiesEnabled);
			}
		} catch (SecurityException e) {
			Activator.logError("A security exception occurred while trying to put the proxy data into the system properties", e); //$NON-NLS-1$
		}
	}
	
	private boolean verifyDataMatchesSystemProperties(ProxyData proxyData) {
		try {
			boolean proxiesEnabled = ProxyManager.getProxyManager().isProxiesEnabled();
			if (proxyData.getType().equals(IProxyData.HTTP_PROXY_TYPE)) {
				return verifyDataMatchesHttpSystemProperties(proxyData, proxiesEnabled);
			} else if (proxyData.getType().equals(IProxyData.HTTPS_PROXY_TYPE)) {
				return verifyDataMatchesHttpsSystemProperties(proxyData, proxiesEnabled);
			} else if (proxyData.getType().equals(IProxyData.SOCKS_PROXY_TYPE)) {
				return verifyDataMatchesSocksSystemProperties(proxyData, proxiesEnabled);
			}
			
		} catch (SecurityException e) {
			// Just ignore this here since it will be surfaced elsewhere
		}
		return true;
	}

	private boolean verifyDataMatchesHttpSystemProperties(ProxyData proxyData,
			boolean proxiesEnabled) {
		if (proxiesEnabled) {
			boolean verified = true;
			String dHost = proxyData.getHost();
			if (!verifySystemPropertyEquals("http.proxyHost", dHost)) { //$NON-NLS-1$
				verified = false;
			} else if (dHost != null && !Boolean.getBoolean("http.proxySet")) {  //$NON-NLS-1$
				Activator.logInfo("The HTTP proxy is enabled in the preferences but disabled in the system settings", null); //$NON-NLS-1$
				verified = false;
			}
			int port = proxyData.getPort();
			if (!verifySystemPropertyEquals("http.proxyPort", port == -1 ? null : String.valueOf(port))) { //$NON-NLS-1$
				verified = false;
			}
			return verified;
		}
		return verifyHttpSystemPropertiesEmpty();
	}

	private boolean verifyDataMatchesHttpsSystemProperties(ProxyData proxyData,
			boolean proxiesEnabled) {
		if (proxiesEnabled) {
			boolean verified = true;
			String dHost = proxyData.getHost();
			if (!verifySystemPropertyEquals("https.proxyHost", dHost)) { //$NON-NLS-1$
				verified = false;
			} else if (dHost != null && !Boolean.getBoolean("https.proxySet")) {  //$NON-NLS-1$
				Activator.logInfo("The SSL proxy is enabled in the preferences but disabled in the system settings", null); //$NON-NLS-1$
				verified = false;
			}
			int port = proxyData.getPort();
			if (!verifySystemPropertyEquals("https.proxyPort", port == -1 ? null : String.valueOf(port))) { //$NON-NLS-1$
				verified = false;
			}
			return verified;
		}
		return verifyHttpsSystemPropertiesEmpty();
	}

	private boolean verifyDataMatchesSocksSystemProperties(ProxyData proxyData,
			boolean proxiesEnabled) {
		if (proxiesEnabled && shouldSetSocksSystemProperties()) {
			boolean verified = true;
			String dHost = proxyData.getHost();
			if (!verifySystemPropertyEquals("socksProxyHost", dHost)) { //$NON-NLS-1$
				verified = false;
			}
			int port = proxyData.getPort();
			if (!verifySystemPropertyEquals("socksProxyPort", port == -1 ? null : String.valueOf(port))) { //$NON-NLS-1$
				verified = false;
			}
			return verified;
		}
		return verifySocksSystemPropertiesEmpty();
	}
	
	private boolean shouldSetSocksSystemProperties() {
		if (socksSystemPropertySetting == ALWAYS_SET)
			return true;
		if (socksSystemPropertySetting == NEVER_SET)
			return false;
		return hasJavaNetProxyClass();
	}

	private boolean verifySystemPropertyEquals(String key, String expected) {
		String value = System.getProperty(key);
		if (value == expected)
			return true;
		if (value == null && expected != null) {
			Activator.logInfo(NLS.bind("System property {0} is not set but should be {1}.", key, expected), null); //$NON-NLS-1$
			return false;
		}
		if (value != null && expected == null) {
			Activator.logInfo(NLS.bind("System property {0} is set to {1} but should not be set.", key, value), null); //$NON-NLS-1$
			return false;
		}
		if (!value.equals(expected)) {
			Activator.logInfo(NLS.bind("System property {0} is set to {1} but should be {2}.", new Object[] {key, value, expected }), null); //$NON-NLS-1$
			return false;
		}
		return true;
	}

	private boolean verifySystemPropertiesEmpty(String proxyType) {
		try {
			if (proxyType.equals(IProxyData.HTTP_PROXY_TYPE)) {
				return verifyHttpSystemPropertiesEmpty();
			} else if (proxyType.equals(IProxyData.HTTPS_PROXY_TYPE)) {
				return verifyHttpsSystemPropertiesEmpty();
			} else if (proxyType.equals(IProxyData.SOCKS_PROXY_TYPE)) {
				return verifySocksSystemPropertiesEmpty();
			}
		} catch (SecurityException e) {
			// Just ignore this here since it will be surfaced elsewhere
		}
		return true;
	}

	private boolean verifyHttpSystemPropertiesEmpty() {
		boolean verified = true;
		verified &= verifyIsNotSet("http.proxySet"); //$NON-NLS-1$
		verified &= verifyIsNotSet("http.proxyHost"); //$NON-NLS-1$
		verified &= verifyIsNotSet("http.proxyPort"); //$NON-NLS-1$
		verified &= verifyIsNotSet("http.nonProxyHosts"); //$NON-NLS-1$
		verified &= verifyIsNotSet("http.proxyUser"); //$NON-NLS-1$
		verified &= verifyIsNotSet("http.proxyUserName"); //$NON-NLS-1$
		verified &= verifyIsNotSet("http.proxyPassword"); //$NON-NLS-1$
		return verified;
	}

	private boolean verifyIsNotSet(String key) {
		String value = System.getProperty(key);
		if (value != null) {
			Activator.logInfo(NLS.bind("System property {0} has been set to {1} by an external source. This value will be overwritten using the values from the preferences", key, value), null); //$NON-NLS-1$
		}
		return value == null;
	}

	private boolean verifyHttpsSystemPropertiesEmpty() {
		boolean verified = true;
		verified &= verifyIsNotSet("https.proxySet"); //$NON-NLS-1$
		verified &= verifyIsNotSet("https.proxyHost"); //$NON-NLS-1$
		verified &= verifyIsNotSet("https.proxyPort"); //$NON-NLS-1$
		verified &= verifyIsNotSet("https.nonProxyHosts"); //$NON-NLS-1$
		verified &= verifyIsNotSet("https.proxyUser"); //$NON-NLS-1$
		verified &= verifyIsNotSet("https.proxyUserName"); //$NON-NLS-1$
		verified &= verifyIsNotSet("https.proxyPassword"); //$NON-NLS-1$
		return verified;
	}

	private boolean verifySocksSystemPropertiesEmpty() {
		boolean verified = true;
		verified &= verifyIsNotSet("socksProxyHost"); //$NON-NLS-1$
		verified &= verifyIsNotSet("socksProxyPort"); //$NON-NLS-1$
		return verified;
	}

	public String getName() {
		return name;
	}

	private void updateHttpSystemProperties(IProxyData data, boolean proxiesEnabled) {
		Assert.isTrue(data.getType().equals(IProxyData.HTTP_PROXY_TYPE));
		Properties sysProps = System.getProperties();
		if (!proxiesEnabled || data.getHost() == null) {
			sysProps.remove("http.proxySet"); //$NON-NLS-1$
			sysProps.remove("http.proxyHost"); //$NON-NLS-1$
			sysProps.remove("http.proxyPort"); //$NON-NLS-1$
			sysProps.remove("http.nonProxyHosts"); //$NON-NLS-1$
			sysProps.remove("http.proxyUser"); //$NON-NLS-1$
			sysProps.remove("http.proxyUserName"); //$NON-NLS-1$
			sysProps.remove("http.proxyPassword"); //$NON-NLS-1$
		} else {
			sysProps.put("http.proxySet", "true"); //$NON-NLS-1$ //$NON-NLS-2$
			sysProps.put("http.proxyHost", data.getHost()); //$NON-NLS-1$
			int port = data.getPort();
			if (port == -1) {
				sysProps.remove("http.proxyPort"); //$NON-NLS-1$
			} else {
				sysProps.put("http.proxyPort", String.valueOf(port)); //$NON-NLS-1$
			}
			sysProps.put("http.nonProxyHosts",  //$NON-NLS-1$
					convertHostsToPropertyString(ProxyManager.getProxyManager().getNonProxiedHosts()));

			String userid = data.getUserId();
			String password = data.getPassword();
			if (userid == null || password == null || userid.length() == 0
					|| password.length() == 0) {
				sysProps.remove("http.proxyUser"); //$NON-NLS-1$
				sysProps.remove("http.proxyUserName"); //$NON-NLS-1$
				sysProps.remove("http.proxyPassword"); //$NON-NLS-1$
			} else {
				sysProps.put("http.proxyUser", userid); //$NON-NLS-1$
				sysProps.put("http.proxyUserName", userid); //$NON-NLS-1$
				sysProps.put("http.proxyPassword", password); //$NON-NLS-1$
			}
		}
	}
	
	private void updateHttpsSystemProperties(IProxyData data, boolean proxiesEnabled) {
		Assert.isTrue(data.getType().equals(IProxyData.HTTPS_PROXY_TYPE));
		Properties sysProps = System.getProperties();
		if (!proxiesEnabled || data.getHost() == null) {
			sysProps.remove("https.proxySet"); //$NON-NLS-1$
			sysProps.remove("https.proxyHost"); //$NON-NLS-1$
			sysProps.remove("https.proxyPort"); //$NON-NLS-1$
			sysProps.remove("https.nonProxyHosts"); //$NON-NLS-1$
			sysProps.remove("https.proxyUser"); //$NON-NLS-1$
			sysProps.remove("https.proxyUserName"); //$NON-NLS-1$
			sysProps.remove("https.proxyPassword"); //$NON-NLS-1$
		} else {
			sysProps.put("https.proxySet", "true"); //$NON-NLS-1$ //$NON-NLS-2$
			sysProps.put("https.proxyHost", data.getHost()); //$NON-NLS-1$
			int port = data.getPort();
			if (port == -1) {
				sysProps.remove("https.proxyPort"); //$NON-NLS-1$
			} else {
				sysProps.put("https.proxyPort", String.valueOf(port)); //$NON-NLS-1$
			}
			sysProps.put("https.nonProxyHosts",  //$NON-NLS-1$
					convertHostsToPropertyString(ProxyManager.getProxyManager().getNonProxiedHosts()));

			String userid = data.getUserId();
			String password = data.getPassword();
			if (userid == null || password == null || userid.length() == 0
					|| password.length() == 0) {
				sysProps.remove("https.proxyUser"); //$NON-NLS-1$
				sysProps.remove("https.proxyUserName"); //$NON-NLS-1$
				sysProps.remove("https.proxyPassword"); //$NON-NLS-1$
			} else {
				sysProps.put("https.proxyUser", userid); //$NON-NLS-1$
				sysProps.put("https.proxyUserName", userid); //$NON-NLS-1$
				sysProps.put("https.proxyPassword", password); //$NON-NLS-1$
			}
		}
	}
	
	private void updateSocksSystemProperties(IProxyData data, boolean proxiesEnabled) {
		Assert.isTrue(data.getType().equals(IProxyData.SOCKS_PROXY_TYPE));
		Properties sysProps = System.getProperties();
		if (!proxiesEnabled || data.getHost() == null) {
			sysProps.remove("socksProxyHost"); //$NON-NLS-1$
			sysProps.remove("socksProxyPort"); //$NON-NLS-1$
		} else {
			if (!shouldSetSocksSystemProperties()) {
				// Log an error if we are not setting the property because we are using a pre-1.5 JRE
				if (socksSystemPropertySetting == ONLY_SET_FOR_1_5_OR_LATER)
					Activator.logError("Setting the SOCKS system properties for a 1.4 VM can interfere with other proxy services (e.g. JSch). Please upgrade to a 1.5 JRE or later if you need to use Java's SOCKS proxy support.", null); //$NON-NLS-1$
				return;
			}
			sysProps.put("socksProxyHost", data.getHost()); //$NON-NLS-1$
			int port = data.getPort();
			if (port == -1) {
				sysProps.remove("socksProxyPort"); //$NON-NLS-1$
			} else {
				sysProps.put("socksProxyPort", String.valueOf(port)); //$NON-NLS-1$
			}
			// TODO: There does appear to be a way to set the non-proxy hosts for Socks
			// TODO: See http://java.sun.com/j2se/1.5.0/docs/guide/net/properties.html for a description
			// of how to set the Socks user and password
		}
	}

	public void initialize(boolean proxiesEnabled) {
		updateSystemProperties(getProxyData(VERIFY_EMPTY), proxiesEnabled);
		((IEclipsePreferences)getParentPreferences()).addNodeChangeListener(this);
		((IEclipsePreferences)getPreferenceNode()).addPreferenceChangeListener(this);
	}
	
	private ISecurePreferences getNode() {
		ISecurePreferences root = SecurePreferencesFactory.getDefault();
		if (root == null)
			return null;
		ISecurePreferences node = root.node(PREFERENCES_CONTEXT);
		if (getName() != null)
			return node.node(getName());
		return node;
	}

    private void loadProxyAuth(IProxyData data) {
		ISecurePreferences node = getNode();
		if (node == null)
			return;
		try {
			data.setUserid(node.get(INFO_PROXY_USER, null));
			data.setPassword(node.get(INFO_PROXY_PASS, null));
		} catch (StorageException e) {
			Activator.logError(e.getMessage(), e);
		}
	}

	private void saveProxyAuth(IProxyData data) {
		ISecurePreferences node= getNode();
		if (node == null)
			return;
		try {
			if (data.getUserId() != null)
				node.put(INFO_PROXY_USER, data.getUserId(), true /* store encrypted */);
			else
				node.remove(INFO_PROXY_USER);

			if (data.getPassword() != null)
				node.put(INFO_PROXY_PASS, data.getPassword(), true /* store encrypted */);
			else
				node.remove(INFO_PROXY_PASS);
		} catch (StorageException e) {
			Activator.logError(e.getMessage(), e);
			return;
		}

		// optional: save it right away in case something crashes later
		try {
			node.flush();
		} catch (IOException e) {
			Activator.logError(e.getMessage(), e);
			return;
		}
	}

	private synchronized boolean hasJavaNetProxyClass() {
		try {
			Class proxyClass = Class.forName("java.net.Proxy"); //$NON-NLS-1$
			return proxyClass != null;
		} catch (ClassNotFoundException e) {
			// Ignore
		}
		return false;
	}

	public void added(NodeChangeEvent event) {
		// Add a preference listener so we'll get changes to the fields of the node
		if (event.getChild().name().equals(getName()))
			((IEclipsePreferences)event.getChild()).addPreferenceChangeListener(this);
	}

	public void removed(NodeChangeEvent event) {
		// Nothing to do
	}

	public void preferenceChange(PreferenceChangeEvent event) {
		if (updatingPreferences)
			return;
		updateSystemProperties(getProxyData(DO_NOT_VERIFY), ProxyManager.getProxyManager().isProxiesEnabled());
		
	}

}
