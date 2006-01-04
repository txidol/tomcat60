/*
 */
package org.apache.tomcat.util.net.apr;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;


/** Wrapper around apr socket handle. Only a subset of the Socket methods 
 * will be supported.
 * 
 * This allows the apr connector to pass around Socket objects, and should 
 * support all call made by endpoint and tomcat, so Apr can be used in the same
 * way. 
 * 
 * @author Costin Manolache
 */
public class AprSocket extends Socket {
    public long aprHandle;

    public InputStream getInputStream() throws IOException {
        return super.getInputStream();
    }

    
    public OutputStream getOutputStream() throws IOException {
        return super.getOutputStream();
    }

    
    public void setKeepAlive(boolean on) throws SocketException {
        super.setKeepAlive(on);
    }

    
    public void setSoLinger(boolean on, int linger) throws SocketException {
        super.setSoLinger(on, linger);
    }

    
    public synchronized void setSoTimeout(int timeout) throws SocketException {
        super.setSoTimeout(timeout);
    }

    
    public void setTcpNoDelay(boolean on) throws SocketException {
        super.setTcpNoDelay(on);
    }
    
}
