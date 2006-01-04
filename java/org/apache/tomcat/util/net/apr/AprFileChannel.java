/*
 */
package org.apache.tomcat.util.net.apr;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

/** Abstraction for APR sendfile ( and maybe other file operations - later ).
 * 
 * Only transferTo is implemented using APR. 
 * Rest of methods could be implemented by delegating to a java file channel.
 * 
 * @author Costin Manolache
 */
public class AprFileChannel extends FileChannel {
    // fd - for APR, it's a pointer to the apr struct 
    long fd;
    
    public AprFileChannel( long fd ) {
        this.fd=fd;
    }
    
    // -------------- FileChannel implementation ---------------
    
    public long transferTo(long position, long count, WritableByteChannel target)
            throws IOException {
        return 0;
    }

    
    
    public int read(ByteBuffer dst) throws IOException {
        return 0;
    }

    
    public long read(ByteBuffer[] dsts, int offset, int length)
            throws IOException {
        return 0;
    }

    
    public int write(ByteBuffer src) throws IOException {
        return 0;
    }

    
    public long write(ByteBuffer[] srcs, int offset, int length)
            throws IOException {
        return 0;
    }

    
    public long position() throws IOException {
        return 0;
    }

    
    public FileChannel position(long newPosition) throws IOException {
        return null;
    }

    
    public long size() throws IOException {
        return 0;
    }

    
    public FileChannel truncate(long size) throws IOException {
        return null;
    }

    
    public void force(boolean metaData) throws IOException {
    }

    
    public long transferFrom(ReadableByteChannel src, long position, long count)
            throws IOException {
        return 0;
    }

    
    public int read(ByteBuffer dst, long position) throws IOException {
        return 0;
    }

    
    public int write(ByteBuffer src, long position) throws IOException {
        return 0;
    }

    
    public MappedByteBuffer map(MapMode mode, long position, long size)
            throws IOException {
        return null;
    }

    
    public FileLock lock(long position, long size, boolean shared)
            throws IOException {
        return null;
    }

    
    public FileLock tryLock(long position, long size, boolean shared)
            throws IOException {
        return null;
    }

    
    protected void implCloseChannel() throws IOException {
    }

}
