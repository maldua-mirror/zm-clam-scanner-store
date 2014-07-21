/*
 * ***** BEGIN LICENSE BLOCK *****
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2005, 2006, 2007, 2009, 2010, 2011, 2012, 2013 Zimbra Software, LLC.
 *
 * The contents of this file are subject to the Zimbra Public License
 * Version 1.4 ("License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the License at
 * http://www.zimbra.com/license.
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied.
 * ***** END LICENSE BLOCK *****
 */

package com.zimbra.clam;

import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.Socket;
import java.net.UnknownHostException;

import com.google.common.net.HostAndPort;
import com.zimbra.common.io.TcpServerInputStream;
import com.zimbra.common.service.ServiceException;
import com.zimbra.common.util.ByteUtil;
import com.zimbra.common.util.CliUtil;
import com.zimbra.common.util.Log;
import com.zimbra.common.util.ZimbraLog;
import com.zimbra.cs.extension.ZimbraExtension;
import com.zimbra.cs.service.mail.UploadScanner;

public class ClamScanner extends UploadScanner implements ZimbraExtension {

    private static final String DEFAULT_URL = "clam://localhost:3310/";

    private static final Log LOG = ZimbraLog.extensions;

    private boolean mInitialized;

    private String mClamdHost;

    private int mClamdPort;

    public ClamScanner() {
    }

    @Override
    public synchronized void init() {
        if (mInitialized) {
            return;
        }

        try {
            mConfig = new ClamScannerConfig();
            if (!mConfig.getEnabled()) {
                LOG.info("attachment scan is disabled");
                mInitialized = true;
                return;
            }

            setURL(mConfig.getURL());
            LOG.info("attachment scan enabled host=[%s] port=[%s]", mClamdHost, mClamdPort);

            UploadScanner.registerScanner(this);
        } catch (ServiceException e) {
            LOG.error("error creating scanner", e);
        } catch (MalformedURLException e) {
            LOG.error("error creating scanner", e);
        }
    }

    @Override
    public void destroy() {
        mInitialized = false;
        UploadScanner.unregisterScanner(this);
    }

    @Override
    public void setURL(String urlArg) throws MalformedURLException {
        if (urlArg == null) {
            urlArg = DEFAULT_URL;
        }
        String protocolPrefix = "clam://";
        if (!urlArg.toLowerCase().startsWith(protocolPrefix)) {
            throw new MalformedURLException("invalid clamd url " + urlArg);
        }
        try {
            HostAndPort hostPort = HostAndPort.fromString(urlArg.substring(protocolPrefix.length()));
            hostPort.requireBracketsForIPv6();
            mClamdPort = hostPort.getPort();
            mClamdHost = hostPort.getHostText();
        } catch (IllegalArgumentException iae) {
            LOG.error("cannot parse clamd url due to illegal arg exception", iae);
            throw new MalformedURLException("cannot parse clamd url due to illegal arg exception: " + iae.getMessage());
        }

        mInitialized = true;
    }

    private ClamScannerConfig mConfig;

    @Override
    protected Result accept(byte[] array, StringBuffer info) {
        if (!mInitialized) {
            return ERROR;
        }

        try {
            return accept0(array, null, info);
        } catch (Exception e) {
            LOG.error("exception communicating with clamd", e);
            return ERROR;
        }
    }

    @Override
    protected Result accept(InputStream is, StringBuffer info) {
        if (!mInitialized) {
            return ERROR;
        }

        try {
            return accept0(null, is, info);
        } catch (Exception e) {
            LOG.error("exception communicating with clamd", e);
            return ERROR;
        }
    }

    private static final byte[] lineSeparator = { '\r', '\n' };

    private Result accept0(byte[] data, InputStream is, StringBuffer info) throws UnknownHostException, IOException {
        Socket commandSocket = null;
        Socket dataSocket = null;

        try {
            if (LOG.isDebugEnabled()) { LOG.debug("connecting to " + mClamdHost + ":" + mClamdPort); }
            commandSocket = new Socket(mClamdHost, mClamdPort);

            BufferedOutputStream out = new BufferedOutputStream(commandSocket.getOutputStream());
            TcpServerInputStream in = new TcpServerInputStream(commandSocket.getInputStream());

            if (LOG.isDebugEnabled()) { LOG.debug("writing STREAM command"); }
            out.write("STREAM".getBytes("iso-8859-1"));
            out.write(lineSeparator);
            out.flush();

            if (LOG.isDebugEnabled()) { LOG.debug("reading PORT"); }
            // REMIND - should have timeout's on this...
            String portLine = in.readLine();
            if (portLine == null) {
                throw new ProtocolException("EOF from clamd when looking for PORT repsonse");
            }
            if (!portLine.startsWith("PORT ")) {
                throw new ProtocolException("Got '" + portLine + "' from clamd, was expecting PORT <n>");
            }
            int port = 0;
            try {
                port = Integer.valueOf(portLine.substring("PORT ".length())).intValue();
            } catch (NumberFormatException nfe) {
                throw new ProtocolException("No port number in: " + portLine);
            }

            if (LOG.isDebugEnabled()) { LOG.debug("stream connect to " + mClamdHost + ":" + port); }
            dataSocket = new Socket(mClamdHost, port);
            if (data != null) {
                dataSocket.getOutputStream().write(data);
                if (LOG.isDebugEnabled()) { LOG.debug("wrote " + data.length + " bytes"); }
            } else {
                long count = ByteUtil.copy(is, false, dataSocket.getOutputStream(), false);
                if (LOG.isDebugEnabled()) { LOG.debug("copied " + count + " bytes"); }
            }
            dataSocket.close();

            if (LOG.isDebugEnabled()) { LOG.debug("reading result"); }
            String answer = in.readLine();
            if (answer == null) {
                throw new ProtocolException("EOF from clamd when looking for result");
            }
            info.setLength(0);
            if (answer.startsWith("stream: ")) {
                answer = answer.substring("stream: ".length());
            }
            info.append(answer);
            if (answer.equals("OK")) {
                return ACCEPT;
            } else {
                return REJECT;
            }
        } finally {
            if (dataSocket != null && !dataSocket.isClosed()) {
                LOG.warn("deffered close of stream connection");
                dataSocket.close();
            }
            if (commandSocket != null) {
                commandSocket.close();
            }
        }
    }

    @Override
    public boolean isEnabled() {
        return mInitialized && mConfig.getEnabled();
    }

    public static void main(String[] args) throws IOException {
        CliUtil.toolSetup();
        StringBuffer info = new StringBuffer();

        ClamScanner scanner = new ClamScanner();
        scanner.setURL(args[0]);

        int count = Integer.getInteger("clam.test.count", 1).intValue();

        for (int iter = 0; iter < count; iter++) {
            for (int i = 1; i < args.length; i++) {
                info.setLength(0);
                InputStream is = new FileInputStream(args[i]);
                Result result = scanner.accept(new FileInputStream(args[i]), info);
                is.close();
                System.out.println("result=" + result + " file=" + args[i] + " info=" + info.toString());
            }

        }

        if (System.getProperty("clam.test.sleep") != null) {
            System.out.println("Sleeping...");
            System.out.flush();
            try {
                Thread.sleep(Integer.MAX_VALUE);
            } catch (InterruptedException ie) {
                ie.printStackTrace();
            }
        }
    }

    @Override
    public String getName() {
        return "clamscanner";
    }
}
