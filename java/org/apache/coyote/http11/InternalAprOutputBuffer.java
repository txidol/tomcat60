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

package org.apache.coyote.http11;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.apache.coyote.ActionCode;
import org.apache.coyote.OutputBuffer;
import org.apache.coyote.Response;
import org.apache.tomcat.jni.Socket;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.http.HttpMessages;

/**
 * Output buffer.
 * 
 * @author <a href="mailto:remm@apache.org">Remy Maucherat</a>
 */
public class InternalAprOutputBuffer extends InternalOutputBuffer {


    // -------------------------------------------------------------- Constants


    // ----------------------------------------------------------- Constructors


    /**
     * Default constructor.
     */
    public InternalAprOutputBuffer(Response response) {
        super(response, Constants.DEFAULT_HTTP_HEADER_BUFFER_SIZE);
    }


    /**
     * Alternate constructor.
     */
    public InternalAprOutputBuffer(Response response, int headerBufferSize) {
        super( response, headerBufferSize);

        bbuf = ByteBuffer.allocateDirect((headerBufferSize / 1500 + 1) * 1500);

        outputStreamOutputBuffer = new SocketOutputBuffer();

        // Cause loading of HttpMessages
        HttpMessages.getMessage(200);
    }

    // ----------------------------------------------------- Instance Variables
    /**
     * Underlying socket. - instead of outputStream
     */
    protected long socket;


    /** instead of socketBuffer
     * Direct byte buffer used for writing.
     */
    protected ByteBuffer bbuf = null;

    
    // ------------------------------------------------------------- Properties


    /**
     * Set the underlying socket.
     */
    public void setSocket(long socket) {
        this.socket = socket;
        Socket.setsbb(this.socket, bbuf);
    }


    /**
     * Get the underlying socket input stream.
     */
    public long getSocket() {
        return socket;
    }

    // --------------------------------------------------------- Public Methods


    /**
     * Flush the response.
     * 
     * @throws IOException an undelying I/O error occured
     */
    public void flush()
        throws IOException {

        if (!committed) {

            // Send the connector a request for commit. The connector should
            // then validate the headers, send them (using sendHeader) and 
            // set the filters accordingly.
            response.action(ActionCode.ACTION_COMMIT, null);

        }

        // Flush the current buffer
        flushBuffer();

    }
    /**
     * Recycle the output buffer. This should be called when closing the 
     * connection.
     */
    public void recycle() {
        super.recycle();

        bbuf.clear();
        socket = 0;
        
    }


    /**
     * End request.
     * 
     * @throws IOException an undelying I/O error occured
     */
    public void endRequest()
        throws IOException {

        if (!committed) {

            // Send the connector a request for commit. The connector should
            // then validate the headers, send them (using sendHeader) and 
            // set the filters accordingly.
            response.action(ActionCode.ACTION_COMMIT, null);

        }

        if (finished)
            return;

        if (lastActiveFilter != -1)
            activeFilters[lastActiveFilter].end();

        flushBuffer();

        finished = true;

    }


    // ------------------------------------------------ HTTP/1.1 Output Methods


    /**
     * Send an acknoledgement.
     */
    public void sendAck()
        throws IOException {

        if (!committed) {
            if (Socket.send(socket, Constants.ACK_BYTES, 0, Constants.ACK_BYTES.length) < 0)
                throw new IOException(sm.getString("iib.failedwrite"));
        }

    }

    // ------------------------------------------------------ Protected Methods


    /**
     * Commit the response.
     * 
     * @throws IOException an undelying I/O error occured
     */
    protected void commit()
        throws IOException {

        // The response is now committed
        committed = true;
        response.setCommitted(true);

        if (pos > 0) {
            // Sending the response header buffer
            bbuf.put(buf, 0, pos);
        }

    }

    /**
     * Callback to write data from the buffer.
     */
    protected void flushBuffer()
        throws IOException {
        if (bbuf.position() > 0) {
            if (Socket.sendbb(socket, 0, bbuf.position()) < 0) {
                throw new IOException(sm.getString("iib.failedwrite"));
            }
            bbuf.clear();
        }
    }


    // ----------------------------------- OutputStreamOutputBuffer Inner Class


    /**
     * This class is an output buffer which will write data to an output
     * stream.
     */
    protected class SocketOutputBuffer 
        implements OutputBuffer {


        /**
         * Write chunk.
         */
        public int doWrite(ByteChunk chunk, Response res) 
            throws IOException {

            // FIXME: It would likely be more efficient to do a number of writes
            // through the direct BB; however, the case should happen very rarely.
            // An algorithm similar to ByteChunk.append may also be better.
            if (chunk.getLength() > bbuf.capacity()) {
                if (Socket.send(socket, chunk.getBuffer(), chunk.getStart(), 
                        chunk.getLength()) < 0) {
                    throw new IOException(sm.getString("iib.failedwrite"));
                }
            } else {
                if (bbuf.position() + chunk.getLength() > bbuf.capacity()) {
                    flushBuffer();
                }
                bbuf.put(chunk.getBuffer(), chunk.getStart(), chunk.getLength());
            }
            return chunk.getLength();

        }


    }


}
