/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2005 Sun Microsystems Inc. All Rights Reserved
 *
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * https://opensso.dev.java.net/public/CDDLv1.0.html or
 * opensso/legal/CDDLv1.0.txt
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at opensso/legal/CDDLv1.0.txt.
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 *
 * $Id: SSOTokenImpl.java,v 1.6 2009/04/10 17:57:07 manish_rustagi Exp $
 *
 * Portions Copyright 2011-2015 ForgeRock AS.
 */

package com.iplanet.sso.providers.dpro;

import com.iplanet.dpro.session.Session;
import com.iplanet.dpro.session.SessionException;
import com.iplanet.dpro.session.SessionListener;
import com.iplanet.sso.SSOException;
import com.iplanet.sso.SSOToken;
import com.iplanet.sso.SSOTokenID;
import com.iplanet.sso.SSOTokenListener;
import com.sun.identity.authentication.internal.AuthContext;
import com.sun.identity.authentication.internal.InvalidAuthContextException;
import com.sun.identity.shared.Constants;
import org.forgerock.openam.session.SessionURL;

import javax.security.auth.login.LoginException;
import java.net.InetAddress;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.util.HashMap;

import static org.forgerock.openam.session.SessionConstants.INACTIVE;
import static org.forgerock.openam.session.SessionConstants.VALID;

/**
 * This class <code>SSOTokenImpl</code> implements the interface
 * <code>SSOToken</code> represents the sso token created for the given
 * <code>Session</code> or through a ldap bind
 *
 * @see com.iplanet.sso.SSOToken
 */

class SSOTokenImpl implements SSOToken {

    /** session */
     private Session session;

    /** regular LDAP connection for SSOToken, false by default */
     private boolean ldapConnect = false;

    /** ldapbind ssotoken */
     private SSOToken ssoToken = null;

    /** ldapbind */
     private java.security.Principal ldapBindDN;

    /** HashMap for the ldap token property*/
     private HashMap ldapTokenProperty = new HashMap();

    private static final SessionURL sessionURL = SessionURL.getInstance();

    /**
     *
     * Creates <code>SSOTokenImpl</code> for a given <code>Session</code>
     * @param session
     * @see com.iplanet.dpro.session.Session
     *
     */
    SSOTokenImpl(Session session) {
        this.session = session;
        ldapConnect = false;
    }

    /**
     * Creates a <code>SSOTokenImpl</code> with regular LDAP authentication
     * service
     * @param principal representing a Principal object
     * @param password password string.
     * @exception SSOException if the single sign on token cannot be created.
     */
    SSOTokenImpl(java.security.Principal principal, String password)
            throws SSOException {
        try {
            // using AuthContext to authentication against local
            // LDAP server
            AuthContext authContext = new AuthContext(principal, password
                    .toCharArray());
            if (authContext.getLoginStatus() != AuthContext.AUTH_SUCCESS) {
                // Authentication Failed
                if (SSOProviderImpl.debug.messageEnabled()) {
                    SSOProviderImpl.debug.message("SSO Auth failed for "
                            + principal.getName());
                }
                throw new SSOException(SSOProviderBundle.rbName,
                        "ldapauthfail", null);
            }

            /* initialize token variables after successful ldap connection */
            ldapBindDN = authContext.getPrincipal();
            ssoToken = authContext.getSSOToken();
            session = null;
            ldapConnect = true;
            SecureRandom secureRandom;
            try {
                secureRandom = SecureRandom.getInstance("SHA1PRNG", "SUN");
            } catch (NoSuchProviderException e) {
                secureRandom = SecureRandom.getInstance("SHA1PRNG");
            }
            String amCtxId = Long.toHexString(secureRandom.nextLong());
            setProperty(Constants.AM_CTX_ID, amCtxId);
        } catch (LoginException e) {
            SSOProviderImpl.debug.error("Ldap Authentication failed for the user " + principal.getName(), e);
            throw new SSOException(SSOProviderBundle.rbName, "ldapauthfail", null);
        } catch (InvalidAuthContextException e) {
            SSOProviderImpl.debug.error("Ldap Authentication failed for the user " + principal.getName(), e);
            throw new SSOException(SSOProviderBundle.rbName, "ldapauthfail",  null);
        } catch (Exception e) {
            SSOProviderImpl.debug.error("Failed to create the context id for this token " + principal.getName(), e);
            throw new SSOException(SSOProviderBundle.rbName, "ldapauthfail", null);
        }
    }

    /**
     * Returns the principal name of the SSOToken
     *
     * @return The Principal name
     * @throws SSOException if the SSOToken is not VALID or if
     *         there are errors in getting the Principal.
     */
    public java.security.Principal getPrincipal() throws SSOException {
        try {
            if (ldapConnect) {
                return ldapBindDN;
            }
            String name = session.getProperty("Principal");
            java.security.Principal principal = new SSOPrincipal(name);
            return principal;
        } catch (Exception e) {
            SSOProviderImpl.debug.message("Can't get token principal name");
            throw new SSOException(e);
        }
    }

    /**
     * Returns the authentication method used for the authentication.
     *
     * @return The authentication method.
     * @throws SSOException if the SSOToken is not VALID or if
     *         there are errors in getting the authentication method.
     */
    public String getAuthType() throws SSOException {
        try {
            if (ldapConnect) {
                return ("LDAP");
            }
            // auth type may be a list of auth types separated by "|". This can
            // happen because of session upgrade. The list is assumed to have
            // a format like "Ldap|Cert|Radius" with no space between separator.
            // this method simply returns the first auth method in that list.
            String types = session.getProperty("AuthType");
            int index = types.indexOf("|");
            if (index != -1) {
                return (types.substring(0, index));
            } else {
                return (types);
            }
        } catch (Exception e) {
            SSOProviderImpl.debug.error("Can't get token authentication type");
            throw new SSOException(e);
        }
    }

    /**
     * Returns the authentication level of the authentication method used for
     * for authentication.
     *
     * @return The authentication level.
     * @throws SSOException if the SSOToken is not VALID or if
     *         there are errors in getting the authentication level.
     */
    public int getAuthLevel() throws SSOException {
        checkTokenType("getAuthLevel");
        try {
            // The property AuthLevel may contain realm information, e.g. "/:10". If so, strip this out.
            String authLevelFull = session.getProperty("AuthLevel");
            int indexOfStartOfIntegerPart = authLevelFull.lastIndexOf(":") + 1;
            String authLevelInteger = authLevelFull.substring(indexOfStartOfIntegerPart);
            return Integer.valueOf(authLevelInteger);
        } catch (Exception e) {
            SSOProviderImpl.debug.error("Can't get token authentication level");
            throw new SSOException(e);
        }
    }

    /**
     * Returns the IP Address of the client(browser) which sent the request.
     *
     * @return The IP Address of the client
     * @throws SSOException if the SSOToken is not VALID or if
     *         there are errors in getting the IP Address of the client.
     */
    public InetAddress getIPAddress() throws SSOException {
        try {
            if (ldapConnect == true) {
                return InetAddress.getLocalHost();
            }
            String host = session.getProperty("Host");
            if ((host == null) || (host.length() == 0)) {
                throw new SSOException(SSOProviderBundle.rbName,
                    "ipaddressnull", null);
            }
            return InetAddress.getByName(host);
        } catch (Exception e) {
            SSOProviderImpl.debug.error("Can't get client's IPAddress");
            throw new SSOException(e);
        }
    }

    /**
     * Returns the host name of the client(browser) which sent the request.
     *
     * @return The host name of the client
     * @throws SSOException if the SSOToken is not VALID or if
     *         there are errors in getting the host name of the client.
     */
    public String getHostName() throws SSOException {
        try {
            if (ldapConnect) {
                return (InetAddress.getLocalHost()).getHostName();
            }
            String hostName = session.getProperty("HostName");
            if ((hostName == null) || (hostName.length() == 0)) {
                throw new SSOException(SSOProviderBundle.rbName, "hostnull",
                    null);
            }
            return hostName;
        } catch (Exception e) {
            SSOProviderImpl.debug.error("Can't get client's token Host name");
            throw new SSOException(e);
        }
    }

    /**
     * Returns the time left for this session based on max session time
     *
     * @return The time left for this session
     * @throws SSOException if the SSOToken is not VALID or if there are errors in getting the maximum session time.
     */
    public long getTimeLeft() throws SSOException {
        checkTokenType("getTimeLeft");
        try {
            return session.getTimeLeft();
        } catch (Exception e) {
            SSOProviderImpl.debug.error("Can't get token maximum time");
            throw new SSOException(e);
        }
    }

    /**
     * Returns the maximum session time in minutes.
     *
     * @return The maximum session time.
     * @throws SSOException if the SSOToken is not VALID or if
     *         there are errors in getting the maximum session time.
     */
    public long getMaxSessionTime() throws SSOException {
        checkTokenType("getMaxSessionTime");
        try {
            return session.getMaxSessionTime();
        } catch (Exception e) {
            SSOProviderImpl.debug.error("Can't get token maximum time");
            throw new SSOException(e);
        }
    }

    /**
     * Returns the session idle time in seconds.
     *
     * @return The session idle time.
     * @throws SSOException if the SSOToken is not VALID or if
     *         there are errors in getting the idle time.
     */
    public long getIdleTime() throws SSOException {
        checkTokenType("getIdleTime");
        try {
            return session.getIdleTime();
        } catch (Exception e) {
            SSOProviderImpl.debug.error("Can't get token idle time");
            throw new SSOException(e);
        }
    }

    /**
     * Returns the maximum session idle time in minutes.
     *
     * @return The maximum session idle time.
     * @throws SSOException if the SSOToken is not VALID or if
     *         there are errors in getting the maximum idle time.
     */
    public long getMaxIdleTime() throws SSOException {
        checkTokenType("getMaxIdleTime");
        try {
            return session.getMaxIdleTime();
        } catch (Exception e) {
            SSOProviderImpl.debug.error("Can't get token maximum idle time");
            throw new SSOException(e);
        }
    }

    /**
     * Returns SSOToken ID object
     *
     * @return SSOTokenID
     */
    public SSOTokenID getTokenID() {
        if (ldapConnect) {
            if (ssoToken != null) {
                return (ssoToken.getTokenID());
            }
            return null;
        }
        return (new SSOTokenIDImpl(session.getID()));
    }

    /**
     * Sets a property for this token.
     *
     * @param name
     *            The property name.
     * @param value
     *            The property value.
     * @throws SSOException if the SSOToken is not VALID or if
     *         there are errors in setting the property name and value.
     */
    public void setProperty(String name, String value) throws SSOException {
        if (ldapConnect) {
            ldapTokenProperty.put(name, value);
            return;
        }
        try {
            session.setProperty(name, value);
        } catch (Exception e) {
            SSOProviderImpl.debug.error("Can't set property:  " + name + " "
                    + value);
            throw new SSOException(e);
        }
    }

    private String getPropertyInternal(String name, boolean logError) throws SSOException {

        String property = null;
        if (ssoToken != null) {
            property = ssoToken.getProperty(name);
        }
        if (property == null) {
            if (ldapConnect) {
                property = ((String) ldapTokenProperty.get(name));
            } else {
                try {
                    property = session.getProperty(name);
                } catch (Exception e) {
                    if(logError){
                	    SSOProviderImpl.debug.error("Can't get property: " + name);
                    }else{
                        if (SSOProviderImpl.debug.messageEnabled()) {
                            SSOProviderImpl.debug.message("Can't get property: " + name);
                        }
                    }
                    throw new SSOException(e);
                }
            }
        }
        return property;
    }


    /**
     * Returns the property stored in this token.
     *
     * @param name
     *            The property name.
     * @return The property value in String format.
     * @throws SSOException if the SSOToken is not VALID or if
     *         there are errors in getting the property value.
     */
    public String getProperty(String name) throws SSOException {
        return getPropertyInternal(name, true);
    }

    /**
     * Returns the property stored in this token.
     *
     * @param name
     *            The property name.
     * @param ignoreState
	 *            ignoreState flag.
     * @return The property value in String format.
     * @throws SSOException if the SSOToken is not VALID and if
     *         ignoreState is set to false.
     */
    public String getProperty(String name, boolean ignoreState) throws SSOException {

        String property;
		try {
            if (SSOProviderImpl.debug.messageEnabled()) {
                SSOProviderImpl.debug.message("SSOTokenImpl.getProperty():" +
                        " Calling getProperty(name)");
            }
            property = getPropertyInternal(name, false);
	    } catch (SSOException e) {
            if(ignoreState) {
                if (SSOProviderImpl.debug.messageEnabled()) {
                    SSOProviderImpl.debug.message("SSOTokenImpl.getProperty():  getProperty(name) failed because of:" +
                            e.getMessage());
                    SSOProviderImpl.debug.message("SSOTokenImpl.getProperty():"
                        + " Falling back to getPropertyWithoutValidation()");
                }
                property = session.getPropertyWithoutValidation(name);
                if (SSOProviderImpl.debug.messageEnabled()) {
                    SSOProviderImpl.debug.message("SSOTokenImpl.getProperty(): Value of " + name + " is: " + property);
                }
            } else {
                throw e;
            }
        }
        return property;
    }

    /**
     * Adds a sso token listener for the token change events.
     *
     * @param listener
     *            A reference to a SSOTokenListener object.
     * @throws SSOException if the SSOToken is not VALID or if
     *         there are errors in adding the sso token listener.
     */
    public void addSSOTokenListener(SSOTokenListener listener)
        throws SSOException {
        if (!ldapConnect) {
            try {
                SessionListener ssoListener = new SSOSessionListener(listener);
                session.addSessionListener(ssoListener);
            } catch (Exception e) {
                SSOProviderImpl.debug.error("Couldn't add listener to the token"
                    + getTokenID().toString());
                throw new SSOException(e);
            }
        }
    }

    /**
     * Returns true if the SSOToken is valid, allowing the token to be refreshed if necessary.
     *
     * @return true if the SSOToken is valid.
     */
    public boolean isValid() {
        return isValid(true);
    }

    /**
     * Returns true if the SSOToken is valid, allowing the idle time to be reset only if the flag is true.
     *
     * @param possiblyResetIdleTime possibly reset the idle time if true, never reset it if false
     * @return true if the SSOToken is valid, false otherwise.
     */
    public boolean isValid(boolean possiblyResetIdleTime) {
        try {
            if (ldapConnect) {
                return true;
            }
            int state = session.getState(possiblyResetIdleTime);
            return (state == VALID) || (state == INACTIVE);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Checks if the SSOTOken is valid
     *
     * @throws SSOException is thrown if the SSOToken is not valid
     * @deprecated THIS METHOD WILL BE REMOVED ON 3/15/01. INSTEAD USE
     *             SSOTokenManager.getInstance().validateToken(SSOToken)
     */
    public void validate() throws SSOException {
        try {
            if (ldapConnect) {
                return;
            }
            int state = session.getState(true);
            if (state != VALID && state != INACTIVE) {
                throw new SSOException(SSOProviderBundle.rbName, "invalidstate", null);
            }
        } catch (Exception e) {
            throw new SSOException(e);
        }
    }

    /**
     * Returns true if the token is for ldap connection.
     *
     * @return true if the token is for ldap connection.
     */
    public boolean isLdapConnection() {
        return ldapConnect;
    }

    /**
     * Sets the value of ldapConnect. It is used to destroy this token.
     *
     * @param status LDAP Connection status.
     */
    protected void setStatus(boolean status) {
        ldapConnect = status;
    }

    /**
     * Returns the encoded URL , rewritten to include the session id.
     *
     * @param url
     *            the URL to be encoded
     * @return the encoded URL if cookies are not supported or the url if
     *         cookies are supported.
     */
    public String encodeURL(String url) {
        checkTokenType("encodeURL");
        return sessionURL.encodeURL(url, session);
    }

    /**
     * Check if the token is created by direct ldap connection. If yes then
     * throw unsupported exception
     *
     * @param methodName Name of the method calling this check.
     */
    public void checkTokenType(String methodName) {
        if (ldapConnect) {
            String str = methodName
                    + "is an unsupported operation for tokens created"
                    + "by direct ldap connection";
            SSOProviderImpl.debug.error(str);
            throw new UnsupportedOperationException(str);
        }
    }

    /**
     * Returns the Session Object.
     *
     * @return Session object.
     */
    Session getSession() {
        return session;
    }

    /**
     * Returns true if the SSOTokenID associated with this SSOToken is a
     * restricted token, false otherwise.
     *
     * @return true if the token is restricted
     * @throws SSOException If we are unable to determine if the session is
     *              restricted
     */
    public boolean isTokenRestricted() throws SSOException {
        try {
            return session.isRestricted();
        } catch (SessionException se) {
            throw new SSOException(se);
        }

    }

    /**
     * Given a restricted token, returns the SSOTokenID of the master token
     * can only be used if the requester is an app token
     *
     * @param requester Must be an app token
     * @param restrictedId The SSOTokenID of the restricted token
     * @return The SSOTokenID string of the master token
     * @throws SSOException If the master token cannot be dereferenced
     */
    public String  dereferenceRestrictedTokenID(SSOToken requester, String restrictedId) throws SSOException {
        String masterSID;

        try {
            masterSID = session.dereferenceRestrictedTokenID(((SSOTokenImpl)requester).getSession(), restrictedId);
        } catch (Exception e) {
            SSOProviderImpl.debug.error("Can't dereference master token for id :  " + restrictedId, e);
            throw new SSOException(e);
        }

        return masterSID;
    }
}
