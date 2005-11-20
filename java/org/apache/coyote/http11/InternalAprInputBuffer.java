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

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;

import org.apache.coyote.InputBuffer;
import org.apache.coyote.Request;
import org.apache.tomcat.jni.Socket;
import org.apache.tomcat.jni.Status;
import org.apache.tomcat.util.buf.ByteChunk;

/**
 * Implementation of InputBuffer which provides HTTP request header parsing as
 * well as transfer decoding.
 *
 * @author <a href="mailto:remm@apache.org">Remy Maucherat</a>
 */
public class InternalAprInputBuffer extends InternalInputBuffer {


    // -------------------------------------------------------------- Constants


    // ----------------------------------------------------------- Constructors


    /**
     * Alternate constructor.
     */
    public InternalAprInputBuffer(Request request, int headerBufferSize, 
                                  long readTimeout) {
        super(request, headerBufferSize);

        bbuf = ByteBuffer.allocateDirect(headerBufferSize);

        inputStreamInputBuffer = new SocketInputBuffer();

        this.readTimeout = readTimeout * 1000;

    }


    // -------------------------------------------------------------- Variables

    /**
     * Direct byte buffer used to perform actual reading.
     * Used instead of super.inputStream
     */
    protected ByteBuffer bbuf;


    /**
     * Underlying socket.
     */
    protected long socket;


    /**
     * The socket timeout used when reading the first block of the request
     * header.
     */
    protected long readTimeout;
    
    
    // ------------------------------------------------------------- Properties


    /**
     * Set the underlying socket.
     */
    public void setSocket(long socket) {
        this.socket = socket;
        Socket.setrbb(this.socket, bbuf);
    }


    /**
     * Get the underlying socket input stream.
     */
    public long getSocket() {
        return socket;
    }


    // --------------------------------------------------------- Public Methods


    /**
     * Recycle the input buffer. This should be called when closing the 
     * connection.
     */
    public void recycle() {
        super.recycle();

        // Recycle Request object
        request.recycle();

        socket = 0;
    }


    /**
     * Read the request line. This function is meant to be used during the 
     * HTTP request header parsing. Do NOT attempt to read the request body 
     * using it.
     *
     * @throws IOException If an exception occurs during the underlying socket
     * read operations, or if the given buffer is not big enough to accomodate
     * the whole line.
     * @return true if data is properly fed; false if no data is available 
     * immediately and thread should be freed
     */
    public boolean parseRequestLine(boolean useAvailableData)
        throws IOException {

        int start = 0;

        //
        // Skipping blank lines
        //

        byte chr = 0;
        do {

            // Read new bytes if needed
            if (pos >= lastValid) {
                if (useAvailableData) {
                    return false;
                }
                // Do a simple read with a short timeout
                bbuf.clear();
                int nRead = Socket.recvbbt
                    (socket, 0, buf.length - lastValid, readTimeout);
                if (nRead > 0) {
                    bbuf.limit(nRead);
                    bbuf.get(buf, pos, nRead);
                    lastValid = pos + nRead;
                } else {
                    if ((-nRead) == Status.ETIMEDOUT || (-nRead) == Status.TIMEUP) {
                        return false;
                    } else {
                        throw new IOException(sm.getString("iib.failedread"));
                    }
                }
            }

            chr = buf[pos++];

        } while ((chr == Constants.CR) || (chr == Constants.LF));

        pos--;

        // Mark the current buffer position
        start = pos;

        if (pos >= lastValid) {
            if (useAvailableData) {
                return false;
            }
            // Do a simple read with a short timeout
            bbuf.clear();
            int nRead = Socket.recvbbt
                (socket, 0, buf.length - lastValid, readTimeout);
            if (nRead > 0) {
                bbuf.limit(nRead);
                bbuf.get(buf, pos, nRead);
                lastValid = pos + nRead;
            } else {
                if ((-nRead) == Status.ETIMEDOUT || (-nRead) == Status.TIMEUP) {
                    return false;
                } else {
                    throw new IOException(sm.getString("iib.failedread"));
                }
            }
        }

        //
        // Reading the method name
        // Method name is always US-ASCII
        //

        boolean space = false;

        while (!space) {

            // Read new bytes if needed
            if (pos >= lastValid) {
                if (!fill())
                    throw new EOFException(sm.getString("iib.eof.error"));
            }

            ascbuf[pos] = (char) buf[pos];

            if (buf[pos] == Constants.SP) {
                space = true;
                request.method().setChars(ascbuf, start, pos - start);
            }

            pos++;

        }

        // Mark the current buffer position
        start = pos;
        int end = 0;
        int questionPos = -1;

        //
        // Reading the URI
        //

        space = false;
        boolean eol = false;

        while (!space) {

            // Read new bytes if needed
            if (pos >= lastValid) {
                if (!fill())
                    throw new EOFException(sm.getString("iib.eof.error"));
            }

            if (buf[pos] == Constants.SP) {
                space = true;
                end = pos;
            } else if ((buf[pos] == Constants.CR) 
                       || (buf[pos] == Constants.LF)) {
                // HTTP/0.9 style request
                eol = true;
                space = true;
                end = pos;
            } else if ((buf[pos] == Constants.QUESTION) 
                       && (questionPos == -1)) {
                questionPos = pos;
            }

            pos++;

        }

        request.unparsedURI().setBytes(buf, start, end - start);
        if (questionPos >= 0) {
            request.queryString().setBytes(buf, questionPos + 1, 
                                           end - questionPos - 1);
            request.requestURI().setBytes(buf, start, questionPos - start);
        } else {
            request.requestURI().setBytes(buf, start, end - start);
        }

        // Mark the current buffer position
        start = pos;
        end = 0;

        //
        // Reading the protocol
        // Protocol is always US-ASCII
        //

        while (!eol) {

            // Read new bytes if needed
            if (pos >= lastValid) {
                if (!fill())
                    throw new EOFException(sm.getString("iib.eof.error"));
            }

            ascbuf[pos] = (char) buf[pos];

            if (buf[pos] == Constants.CR) {
                end = pos;
            } else if (buf[pos] == Constants.LF) {
                if (end == 0)
                    end = pos;
                eol = true;
            }

            pos++;

        }

        if ((end - start) > 0) {
            request.protocol().setChars(ascbuf, start, end - start);
        } else {
            request.protocol().setString("");
        }
        
        return true;

    }



    // ------------------------------------------------------ Protected Methods


    /**
     * Fill the internal buffer using data from the undelying input stream.
     * 
     * @return false if at end of stream
     */
    protected boolean fill()
        throws IOException {

        int nRead = 0;

        if (parsingHeader) {

            if (lastValid == buf.length) {
                throw new IOException
                    (sm.getString("iib.requestheadertoolarge.error"));
            }

            bbuf.clear();
            nRead = Socket.recvbb
                (socket, 0, buf.length - lastValid);
            if (nRead > 0) {
                bbuf.limit(nRead);
                bbuf.get(buf, pos, nRead);
                lastValid = pos + nRead;
            } else {
                if ((-nRead) == Status.EAGAIN) {
                    return false;
                } else {
                    throw new IOException(sm.getString("iib.failedread"));
                }
            }

        } else {

            buf = bodyBuffer;
            pos = 0;
            lastValid = 0;
            bbuf.clear();
            nRead = Socket.recvbb
                (socket, 0, buf.length);
            if (nRead > 0) {
                bbuf.limit(nRead);
                bbuf.get(buf, 0, nRead);
                lastValid = nRead;
            } else {
                throw new IOException(sm.getString("iib.failedread"));
            }

        }

        return (nRead > 0);

    }


    // ------------------------------------- InputStreamInputBuffer Inner Class


    /**
     * This class is an input buffer which will read its data from an input
     * stream.
     */
    protected class SocketInputBuffer 
        implements InputBuffer {


        /**
         * Read bytes into the specified chunk.
         */
        public int doRead(ByteChunk chunk, Request req ) 
            throws IOException {

            if (pos >= lastValid) {
                if (!fill())
                    return -1;
            }

            int length = lastValid - pos;
            chunk.setBytes(buf, pos, length);
            pos = lastValid;

            return (length);

        }


    }


}
