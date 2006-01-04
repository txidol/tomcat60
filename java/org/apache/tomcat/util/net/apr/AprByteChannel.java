/*
 */
package org.apache.tomcat.util.net.apr;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;

public class AprByteChannel implements ByteChannel {

    public int read(ByteBuffer dst) throws IOException {
        return 0;
    }

    public boolean isOpen() {
        return false;
    }

    public void close() throws IOException {
    }

    public int write(ByteBuffer src) throws IOException {
        return 0;
    }

}
