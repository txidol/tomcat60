/*
 *  Copyright 1999-2004 The Apache Software Foundation
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.coyote.http11.apr;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import org.apache.coyote.ActionCode;
import org.apache.coyote.ActionHook;
import org.apache.coyote.Request;
import org.apache.coyote.RequestInfo;
import org.apache.coyote.Response;
import org.apache.coyote.http11.Constants;
import org.apache.coyote.http11.Http11Processor;
import org.apache.coyote.http11.InputFilter;
import org.apache.coyote.http11.InternalOutputBuffer;
import org.apache.coyote.http11.OutputFilter;
import org.apache.coyote.http11.filters.BufferedInputFilter;
import org.apache.tomcat.jni.Address;
import org.apache.tomcat.jni.SSL;
import org.apache.tomcat.jni.SSLSocket;
import org.apache.tomcat.jni.Sockaddr;
import org.apache.tomcat.jni.Socket;
import org.apache.tomcat.util.buf.HexUtils;
import org.apache.tomcat.util.http.FastHttpDateFormat;
import org.apache.tomcat.util.net.AprEndpoint;
import org.apache.tomcat.util.threads.ThreadWithAttributes;


/**
 * Processes HTTP requests.
 *
 * @author Remy Maucherat
 */
public class Http11AprProcessor extends Http11Processor implements ActionHook {

 
    // ----------------------------------------------------------- Constructors


    public Http11AprProcessor(int headerBufferSize, AprEndpoint endpoint) {

        this.endpoint = endpoint;
        
        request = new Request();
        int readTimeout = endpoint.getFirstReadTimeout();
        if (readTimeout <= 0) {
            readTimeout = 100;
        }
        inputBuffer = new InternalAprInputBuffer(request, headerBufferSize,
                readTimeout);
        request.setInputBuffer(inputBuffer);

        response = new Response();
        response.setHook(this);
        outputBuffer = new InternalAprOutputBuffer(response, headerBufferSize);
        response.setOutputBuffer(outputBuffer);
        request.setResponse(response);
        
        ssl = !"off".equalsIgnoreCase(endpoint.getSSLEngine());

        initializeFilters();

        // Cause loading of HexUtils
        int foo = HexUtils.DEC[0];

        // Cause loading of FastHttpDateFormat
        FastHttpDateFormat.getCurrentDate();

    }


    // ----------------------------------------------------- Instance Variables
    /**
     * Sendfile data.
     */
    protected AprEndpoint.SendfileData sendfileData = null;


    /**
     * SSL enabled ?
     */
    protected boolean ssl = false;
    

    /**
     * Socket associated with the current connection.
     */
    protected long socket;


    /**
     * Associated endpoint.
     */
    //protected AprEndpoint endpoint;

    // ------------------------------------------------------------- Properties
    /**
     * Process pipelined HTTP requests using the specified input and output
     * streams.
     *
     * @throws IOException error during an I/O operation
     */
    public boolean process(long socket)
        throws IOException {
        ThreadWithAttributes thrA=
                (ThreadWithAttributes)Thread.currentThread();
        RequestInfo rp = request.getRequestProcessor();
        thrA.setCurrentStage(endpoint, "parsing http request");
        rp.setStage(org.apache.coyote.Constants.STAGE_PARSE);

        // Set the remote address
        remoteAddr = null;
        remoteHost = null;
        localAddr = null;
        remotePort = -1;
        localPort = -1;

        // Setting up the socket
        this.socket = socket;
        ((InternalAprInputBuffer)inputBuffer).setSocket(socket);
        ((InternalAprOutputBuffer)outputBuffer).setSocket(socket);

        // Error flag
        error = false;
        keepAlive = true;

        int keepAliveLeft = maxKeepAliveRequests;
        long soTimeout = endpoint.getSoTimeout();
        
        int limit = 0;
        if (((AprEndpoint)endpoint).getFirstReadTimeout() > 0) {
            limit = endpoint.getMaxThreads() / 2;
        }

        boolean keptAlive = false;
        boolean openSocket = false;

        while (started && !error && keepAlive) {

            // Parsing the request header
            try {
                if( !disableUploadTimeout && keptAlive && soTimeout > 0 ) {
                    Socket.timeoutSet(socket, soTimeout * 1000);
                }
                if (!inputBuffer.parseRequestLine
                        (keptAlive && (endpoint.getCurrentThreadsBusy() > limit))) {
                    // This means that no data is available right now
                    // (long keepalive), so that the processor should be recycled
                    // and the method should return true
                    openSocket = true;
                    // Add the socket to the poller
                    ((AprEndpoint)endpoint).getPoller().add(socket);
                    break;
                }
                request.setStartTime(System.currentTimeMillis());
                thrA.setParam(endpoint, request.requestURI());
                keptAlive = true;
                if (!disableUploadTimeout) {
                    Socket.timeoutSet(socket, timeout * 1000);
                }
                inputBuffer.parseHeaders();
            } catch (IOException e) {
                error = true;
                break;
            } catch (Throwable t) {
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("http11processor.header.parse"), t);
                }
                // 400 - Bad Request
                response.setStatus(400);
                error = true;
            }

            // Setting up filters, and parse some request headers
            thrA.setCurrentStage(endpoint, "prepareRequest");
            rp.setStage(org.apache.coyote.Constants.STAGE_PREPARE);
            try {
                prepareRequest();
            } catch (Throwable t) {
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("http11processor.request.prepare"), t);
                }
                // 400 - Internal Server Error
                response.setStatus(400);
                error = true;
            }

            if (maxKeepAliveRequests > 0 && --keepAliveLeft == 0)
                keepAlive = false;

            // Process the request in the adapter
            if (!error) {
                try {
                    thrA.setCurrentStage(endpoint, "service");
                    rp.setStage(org.apache.coyote.Constants.STAGE_SERVICE);
                    adapter.service(request, response);
                    // Handle when the response was committed before a serious
                    // error occurred.  Throwing a ServletException should both
                    // set the status to 500 and set the errorException.
                    // If we fail here, then the response is likely already
                    // committed, so we can't try and set headers.
                    if(keepAlive && !error) { // Avoid checking twice.
                        error = response.getErrorException() != null ||
                                statusDropsConnection(response.getStatus());
                    }

                } catch (InterruptedIOException e) {
                    error = true;
                } catch (Throwable t) {
                    log.error(sm.getString("http11processor.request.process"), t);
                    // 500 - Internal Server Error
                    response.setStatus(500);
                    error = true;
                }
            }

            // Finish the handling of the request
            try {
                thrA.setCurrentStage(endpoint, "endRequestIB");
                rp.setStage(org.apache.coyote.Constants.STAGE_ENDINPUT);
                inputBuffer.endRequest();
            } catch (IOException e) {
                error = true;
            } catch (Throwable t) {
                log.error(sm.getString("http11processor.request.finish"), t);
                // 500 - Internal Server Error
                response.setStatus(500);
                error = true;
            }
            try {
                thrA.setCurrentStage(endpoint, "endRequestOB");
                rp.setStage(org.apache.coyote.Constants.STAGE_ENDOUTPUT);
                outputBuffer.endRequest();
            } catch (IOException e) {
                error = true;
            } catch (Throwable t) {
                log.error(sm.getString("http11processor.response.finish"), t);
                error = true;
            }

            // If there was an error, make sure the request is counted as
            // and error, and update the statistics counter
            if (error) {
                response.setStatus(500);
            }
            request.updateCounters();

            thrA.setCurrentStage(endpoint, "ended");
            rp.setStage(org.apache.coyote.Constants.STAGE_KEEPALIVE);

            // Don't reset the param - we'll see it as ended. Next request
            // will reset it
            // thrA.setParam(null);
            // Next request
            inputBuffer.nextRequest();
            outputBuffer.nextRequest();

            // Do sendfile as needed: add socket to sendfile and end
            if (sendfileData != null) {
                sendfileData.socket = socket;
                sendfileData.keepAlive = keepAlive;
                if (!((AprEndpoint)endpoint).getSendfile().add(sendfileData)) {
                    openSocket = true;
                    break;
                }
            }
            
        }

        rp.setStage(org.apache.coyote.Constants.STAGE_ENDED);

        // Recycle
        inputBuffer.recycle();
        outputBuffer.recycle();

        return openSocket;
        
    }


    // ----------------------------------------------------- ActionHook Methods

    
    protected void endpointAction(ActionCode actionCode, Object param) {
        if (actionCode == ActionCode.ACTION_REQ_HOST_ADDR_ATTRIBUTE) {
        

            // Get remote host address
            if (remoteAddr == null) {
                try {
                    long sa = Address.get(Socket.APR_REMOTE, socket);
                    remoteAddr = Address.getip(sa);
                } catch (Exception e) {
                    log.warn(sm.getString("http11processor.socket.info"), e);
                }
            }
            request.remoteAddr().setString(remoteAddr);

        } else if (actionCode == ActionCode.ACTION_REQ_LOCAL_NAME_ATTRIBUTE) {

            // Get local host name
            if (localName == null) {
                try {
                    long sa = Address.get(Socket.APR_LOCAL, socket);
                    localName = Address.getnameinfo(sa, 0);
                } catch (Exception e) {
                    log.warn(sm.getString("http11processor.socket.info"), e);
                }
            }
            request.localName().setString(localName);

        } else if (actionCode == ActionCode.ACTION_REQ_HOST_ATTRIBUTE) {

            // Get remote host name
            if (remoteHost == null) {
                try {
                    long sa = Address.get(Socket.APR_REMOTE, socket);
                    remoteHost = Address.getnameinfo(sa, 0);
                } catch (Exception e) {
                    log.warn(sm.getString("http11processor.socket.info"), e);
                }
            }
            request.remoteHost().setString(remoteHost);

        } else if (actionCode == ActionCode.ACTION_REQ_LOCAL_ADDR_ATTRIBUTE) {

            // Get local host address
            if (localAddr == null) {
                try {
                    long sa = Address.get(Socket.APR_LOCAL, socket);
                    Sockaddr addr = new Sockaddr();
                    if (Address.fill(addr, sa)) {
                        localAddr = addr.hostname;
                        localPort = addr.port;
                    }
                } catch (Exception e) {
                    log.warn(sm.getString("http11processor.socket.info"), e);
                }
            }

            request.localAddr().setString(localAddr);

        } else if (actionCode == ActionCode.ACTION_REQ_REMOTEPORT_ATTRIBUTE) {

            // Get remote port
            if (remotePort == -1) {
                try {
                    long sa = Address.get(Socket.APR_REMOTE, socket);
                    Sockaddr addr = Address.getInfo(sa);
                    remotePort = addr.port;
                } catch (Exception e) {
                    log.warn(sm.getString("http11processor.socket.info"), e);
                }
            }
            request.setRemotePort(remotePort);

        } else if (actionCode == ActionCode.ACTION_REQ_LOCALPORT_ATTRIBUTE) {

            // Get local port
            if (localPort == -1) {
                try {
                    long sa = Address.get(Socket.APR_LOCAL, socket);
                    Sockaddr addr = new Sockaddr();
                    if (Address.fill(addr, sa)) {
                        localAddr = addr.hostname;
                        localPort = addr.port;
                    }
                } catch (Exception e) {
                    log.warn(sm.getString("http11processor.socket.info"), e);
                }
            }
            request.setLocalPort(localPort);

        } else if (actionCode == ActionCode.ACTION_REQ_SSL_ATTRIBUTE ) {

            try {
                if (ssl) {
                    // Cipher suite
                    Object sslO = SSLSocket.getInfoS(socket, SSL.SSL_INFO_CIPHER);
                    if (sslO != null) {
                        request.setAttribute
                            (AprEndpoint.CIPHER_SUITE_KEY, sslO);
                    }
                    // Client certificate chain if present
                    int certLength = SSLSocket.getInfoI(socket, SSL.SSL_INFO_CLIENT_CERT_CHAIN);
                    X509Certificate[] certs = null;
                    if (certLength > 0) {
                        certs = new X509Certificate[certLength];
                        for (int i = 0; i < certLength; i++) {
                            byte[] data = SSLSocket.getInfoB(socket, SSL.SSL_INFO_CLIENT_CERT_CHAIN + i);
                            CertificateFactory cf =
                                CertificateFactory.getInstance("X.509");
                            ByteArrayInputStream stream = new ByteArrayInputStream(data);
                            certs[i] = (X509Certificate) cf.generateCertificate(stream);
                        }
                    }
                    if (certs != null) {
                        request.setAttribute
                            (AprEndpoint.CERTIFICATE_KEY, certs);
                    }
                    // User key size
                    sslO = new Integer(SSLSocket.getInfoI(socket, SSL.SSL_INFO_CIPHER_USEKEYSIZE));
                    if (sslO != null) {
                        request.setAttribute
                            (AprEndpoint.KEY_SIZE_KEY, sslO);
                    }
                    // SSL session ID
                    sslO = SSLSocket.getInfoS(socket, SSL.SSL_INFO_SESSION_ID);
                    if (sslO != null) {
                        request.setAttribute
                            (AprEndpoint.SESSION_ID_KEY, sslO);
                    }
                }
            } catch (Exception e) {
                log.warn(sm.getString("http11processor.socket.ssl"), e);
            }

        } else if (actionCode == ActionCode.ACTION_REQ_SSL_CERTIFICATE) {

            if (ssl) {
                 // Consume and buffer the request body, so that it does not
                 // interfere with the client's handshake messages
                InputFilter[] inputFilters = inputBuffer.getFilters();
                ((BufferedInputFilter) inputFilters[Constants.BUFFERED_FILTER])
                    .setLimit(maxSavePostSize);
                inputBuffer.addActiveFilter
                    (inputFilters[Constants.BUFFERED_FILTER]);
                try {
                    // Renegociate certificates
                    SSLSocket.renegotiate(socket);
                    // Client certificate chain if present
                    int certLength = SSLSocket.getInfoI(socket, SSL.SSL_INFO_CLIENT_CERT_CHAIN);
                    X509Certificate[] certs = null;
                    if (certLength > 0) {
                        certs = new X509Certificate[certLength];
                        for (int i = 0; i < certLength; i++) {
                            byte[] data = SSLSocket.getInfoB(socket, SSL.SSL_INFO_CLIENT_CERT_CHAIN + i);
                            CertificateFactory cf =
                                CertificateFactory.getInstance("X.509");
                            ByteArrayInputStream stream = new ByteArrayInputStream(data);
                            certs[i] = (X509Certificate) cf.generateCertificate(stream);
                        }
                    }
                    if (certs != null) {
                        request.setAttribute
                            (AprEndpoint.CERTIFICATE_KEY, certs);
                    }
                } catch (Exception e) {
                    log.warn(sm.getString("http11processor.socket.ssl"), e);
                }
            }

        }

    }

    // ------------------------------------------------------ Protected Methods


    /**
     * After reading the request headers, we have to setup the request filters.
     */
    protected void prepareRequest() {
        super.prepareRequest();
        
        sendfileData = null;
        // Advertise sendfile support through a request attribute
        if (((AprEndpoint)endpoint).getUseSendfile()) {
            request.setAttribute("org.apache.tomcat.sendfile.support", Boolean.TRUE);
        }
        
    }


    protected void setDefaultHost() {
        // HTTP/1.0
        // Default is what the socket tells us. Overriden if a host is
        // found/parsed
        request.setServerPort(endpoint.getPort());        
    }
    

    protected void sendfileSupport(OutputFilter[] outputFilters) {
        // Sendfile support
        if (((AprEndpoint)endpoint).getUseSendfile()) {
            String fileName = (String) request.getAttribute("org.apache.tomcat.sendfile.filename");
            if (fileName != null) {
                // No entity body sent here
                outputBuffer.addActiveFilter
                    (outputFilters[Constants.VOID_FILTER]);
                contentDelimitation = true;
                sendfileData = new AprEndpoint.SendfileData();
                sendfileData.fileName = fileName;
                sendfileData.start = 
                    ((Long) request.getAttribute("org.apache.tomcat.sendfile.start")).longValue();
                sendfileData.end = 
                    ((Long) request.getAttribute("org.apache.tomcat.sendfile.end")).longValue();
            }
        }
    }

}
