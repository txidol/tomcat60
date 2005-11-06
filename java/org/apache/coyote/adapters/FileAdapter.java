package org.apache.coyote.adapters;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.coyote.Adapter;
import org.apache.coyote.Request;
import org.apache.coyote.Response;
import org.apache.coyote.standalone.MessageWriter;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.C2BConverter;

/**
 * Serve a static file. This is the traditional method, a separate adapter could
 * use Sendfile.
 * 
 * No fancy things. Not sure if it should have dir support even.
 */
public class FileAdapter implements Adapter {
    Log log = LogFactory.getLog("coyote.file");

    private String baseDir = "html/";

    private File baseDirF;

    public FileAdapter() {
        init();
    }

    public void setBaseDir(String s) {
        baseDir = s;
    }

    public void init() {
        baseDirF = new File(baseDir);
        try {
            baseDir = baseDirF.getCanonicalPath();
        } catch (IOException e) {
        }
    }

    public void service(Request req, final Response res) throws Exception {

        String uri = req.requestURI().toString();
        if (uri.indexOf("..") >= 0) {
            // not supported, too dangerous
            // what else to escape ?
            log.info("Invalid .. in  " + uri);
            res.setStatus(404);
            return;
        }

        // local file
        File f = new File(baseDirF, uri);

        // extra check
        if (!f.getCanonicalPath().startsWith(baseDir)) {
            log.info("File outside basedir " + baseDir + " " + f);
            res.setStatus(404);
            return;
        }

        if (f.isDirectory()) {
            // check for index.html, redirect if exists
            // list dir if not

            f = new File(f, "index.html");
        }

        if (!f.exists()) {
            log.info("File not found  " + f);
            res.setStatus(404);
            return;
        }

        res.setStatus(200);

        // TODO: read from a resources in classpath !
        // TODO: refactor to allow sendfile
        // TODO: read mime types

        int dot=uri.lastIndexOf(".");
        if( dot > 0 ) {
            String ext=uri.substring(dot+1);
            String ct=getContentType(ext);
            if( ct!=null) {
                res.setContentType(ct);
            }
        }

        res.setContentLength(f.length());

        res.sendHeaders();

        // not used - writes directly to response
        // MessageWriter out = MessageWriter.getWriter(req, res, 0);

        FileInputStream fis = new FileInputStream(f);
        byte b[] = new byte[4096];
        ByteChunk mb = new ByteChunk();
        int rd = 0;
        while ((rd = fis.read(b)) > 0) {
            mb.setBytes(b, 0, rd);
            res.doWrite(mb);
        }

    }

    static Properties contentTypes=new Properties();
    static {
        initContentTypes();
    }
    static void initContentTypes() {
        contentTypes.put("html", "text/html");
        contentTypes.put("txt", "text/plain");
        contentTypes.put("xul", "application/vnd.mozilla.xul+xml");
    }
    
    public String getContentType( String ext ) {
        return contentTypes.getProperty( ext, "text/plain" );
    }
    
}