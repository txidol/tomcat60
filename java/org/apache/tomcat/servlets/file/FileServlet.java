package org.apache.tomcat.servlets.file;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Properties;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.coyote.Adapter;
import org.apache.coyote.Request;
import org.apache.coyote.Response;
import org.apache.coyote.servlet.util.MessageWriter;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.C2BConverter;

/**
 * Serve a static file. This is the traditional method, a separate adapter could
 * use Sendfile.
 * 
 * No fancy things. Not sure if it should have dir support even.
 */
public class FileServlet  extends HttpServlet {
    Log log = LogFactory.getLog("coyote.file");

    private String baseDir = "html/";

    private File baseDirF;

    public FileServlet() {
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

    protected void doGet(HttpServletRequest req, HttpServletResponse res) 
            throws ServletException, IOException {

        String uri = req.getRequestURI();
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

        res.setContentLength((int) f.length());

        //res.sendHeaders();

        // not used - writes directly to response
        // MessageWriter out = MessageWriter.getWriter(req, res, 0);
        OutputStream os = res.getOutputStream();
        FileInputStream fis = new FileInputStream(f);
        byte b[] = new byte[4096];
        int rd = 0;
        while ((rd = fis.read(b)) > 0) {
            os.write(b,0,rd);
        }
        os.close(); 
    }

    static Properties contentTypes=new Properties();
    static {
        initContentTypes();
    }
    static void initContentTypes() {
        contentTypes.put("xhtml", "text/html");
        contentTypes.put("html", "text/html");
        contentTypes.put("txt", "text/plain");
        contentTypes.put("css", "text/css");
        contentTypes.put("xul", "application/vnd.mozilla.xul+xml");
    }
    
    public String getContentType( String ext ) {
        return contentTypes.getProperty( ext, "text/plain" );
    }

    public boolean event(Request req, Response res, boolean error) throws Exception {
        // TODO Auto-generated method stub
        return false;
    }
    
}